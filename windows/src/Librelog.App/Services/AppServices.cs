using System.Security.Cryptography;
using System.Text;
using Librelog.Core;
using Librelog.Sync;
using Windows.Storage;

namespace Librelog.App.Services;

/// The app's single store and sync engine, plus helpers that keep core calls
/// off the UI thread (SPEC.md §3.2).
public static class AppServices
{
    public static CoreStore Store { get; private set; } = null!;
    public static SyncEngine Sync { get; private set; } = null!;

    /// Raised on the UI thread after a local write or a merge, so pages reload.
    public static event Action? DataChanged;

    public static void Start()
    {
        var folder = ApplicationData.Current.LocalFolder.Path;
        Store = CoreStore.Open(Path.Combine(folder, "journal.sqlite"));
        var secrets = new ProtectedFileSecrets(Path.Combine(folder, "secrets"));
        var client = new SupabaseClient(new HttpClient { Timeout = TimeSpan.FromSeconds(30) }, secrets);
        Sync = new SyncEngine(Store, client, secrets, new LocalSettings(), Environment.MachineName);
        Sync.Merged += () => DataChanged?.Invoke();
    }

    /// Reads from the core on a worker thread; resumes on the caller's.
    public static Task<T> Run<T>(Func<CoreStore, T> read) => Task.Run(() => read(Store));

    /// Writes through the core, then refreshes screens and schedules a push.
    public static async Task Write(Action<CoreStore> write)
    {
        await Task.Run(() => write(Store));
        DataChanged?.Invoke();
        Sync.SchedulePush();
    }

    public static async Task<T> Write<T>(Func<CoreStore, T> write)
    {
        var result = await Task.Run(() => write(Store));
        DataChanged?.Invoke();
        Sync.SchedulePush();
        return result;
    }

    /// Tells every page to reload, as a write would.
    public static void NotifyChanged() => DataChanged?.Invoke();

    /// Leaving the editor: an entry nothing was ever put in is deleted rather
    /// than left behind as an empty row. True if it went.
    public static async Task<bool> DiscardIfEmpty(string id)
    {
        var gone = await Task.Run(() => Store.DiscardIfEmpty(id));
        if (gone)
        {
            DataChanged?.Invoke();
            Sync.SchedulePush();
        }
        return gone;
    }

    public static string Today => DateTime.Now.ToString("yyyy-MM-dd");
}

/// The journal key and session, encrypted with DPAPI for the current Windows
/// user and kept in the app's local folder. Never roamed or synced.
public sealed class ProtectedFileSecrets(string folder) : ISecrets
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("uk.co.maybeitssoftware.librelog");

    public string? Read(string name)
    {
        var path = PathFor(name);
        if (!File.Exists(path)) return null;
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser));
        }
        catch (CryptographicException)
        {
            return null; // written by another Windows user or machine
        }
    }

    public void Write(string name, string value)
    {
        Directory.CreateDirectory(folder);
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), Entropy, DataProtectionScope.CurrentUser);
        var tmp = PathFor(name) + ".tmp";
        File.WriteAllBytes(tmp, bytes);
        File.Move(tmp, PathFor(name), overwrite: true);
    }

    public void Delete(string name)
    {
        if (File.Exists(PathFor(name))) File.Delete(PathFor(name));
    }

    private string PathFor(string name) => Path.Combine(folder, name + ".bin");
}

public sealed class LocalSettings : ISettings
{
    private static ApplicationDataContainer Values => ApplicationData.Current.LocalSettings;

    public long GetLong(string key) => Values.Values.TryGetValue(key, out var v) && v is long l ? l : 0;

    public void SetLong(string key, long value) => Values.Values[key] = value;
}
