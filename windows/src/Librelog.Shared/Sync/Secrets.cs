namespace Librelog.Sync;

/// Where the two sync secrets live: the journal key and the Supabase
/// session. On Windows that's the Credential Locker (Librelog.App's
/// CredentialSecrets); tests use MemorySecrets. Neither is ever synced.
public interface ISecrets
{
    string? Read(string name);
    void Write(string name, string value);
    void Delete(string name);
}

public static class SecretNames
{
    public const string JournalKey = "journal-key";
    public const string Session = "supabase-session";
}

/// Small device-local settings (not secrets), such as the snapshot counter.
public interface ISettings
{
    long GetLong(string key);
    void SetLong(string key, long value);
}

public sealed class MemorySecrets : ISecrets
{
    private readonly Dictionary<string, string> values = [];
    public string? Read(string name) => values.GetValueOrDefault(name);
    public void Write(string name, string value) => values[name] = value;
    public void Delete(string name) => values.Remove(name);
}

public sealed class MemorySettings : ISettings
{
    private readonly Dictionary<string, long> values = [];
    public long GetLong(string key) => values.GetValueOrDefault(key);
    public void SetLong(string key, long value) => values[key] = value;
}
