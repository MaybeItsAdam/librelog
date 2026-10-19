using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Librelog.Core;

namespace Librelog.Sync;

public enum SyncPhase
{
    /// Not signed in. The journal works fully; nothing leaves the device.
    Off,
    /// Signed in, but this device doesn't hold the account's journal key.
    NeedsKey,
    /// Signed in with the key: syncing.
    On,
}

/// Something that wakes sync when another device pushes (RealtimeChannel).
public interface IRealtime : IDisposable
{
    void Connect();
}

/// Drives text sync (SPEC.md §12.5): sign-in, journal-key setup, and the
/// push → pull → snapshot loop. A port of apple/App/Sync/SyncEngine.swift.
/// The core seals and merges; this only moves sealed strings between it and
/// Supabase.
///
/// Call it from one thread (the UI thread): awaits resume there in WinUI, and
/// PropertyChanged is raised on the calling thread.
public sealed class SyncEngine(
    CoreStore store,
    SupabaseClient client,
    ISecrets secrets,
    ISettings settings,
    string deviceName,
    Func<string, SupabaseClient, Action, IRealtime>? realtime = null) : INotifyPropertyChanged
{
    public const int BatchSize = 500;
    public const int SnapshotEvery = 5_000;
    private const string PulledKey = "sync.pulledSinceSnapshot";

    private readonly Func<string, SupabaseClient, Action, IRealtime> makeRealtime =
        realtime ?? ((topic, c, ping) => new RealtimeChannel(topic, c, ping));

    private IRealtime? channel;
    private CancellationTokenSource? pushDelay;
    private bool syncAgain;

    public event PropertyChangedEventHandler? PropertyChanged;

    /// Raised after a pull merged something, so screens reload.
    public event Action? Merged;

    private SyncPhase phase = SyncPhase.Off;
    private string? email;
    private bool isSyncing;
    private DateTimeOffset? lastSynced;
    private SyncException? problem;
    private uint pending;
    private string? newRecoveryPhrase;

    public SyncPhase Phase { get => phase; private set => Set(ref phase, value); }
    public string? Email { get => email; private set => Set(ref email, value); }
    public bool IsSyncing { get => isSyncing; private set => Set(ref isSyncing, value); }
    public DateTimeOffset? LastSynced { get => lastSynced; private set => Set(ref lastSynced, value); }
    public SyncException? Problem { get => problem; private set => Set(ref problem, value); }
    public uint Pending { get => pending; private set => Set(ref pending, value); }

    /// Set once, right after the first device creates the key, so the
    /// recovery phrase can be shown before the user moves on.
    public string? NewRecoveryPhrase { get => newRecoveryPhrase; set => Set(ref newRecoveryPhrase, value); }

    /// How often a new device checks whether its pairing code was answered.
    public TimeSpan PairingPollInterval { get; init; } = TimeSpan.FromSeconds(5);

    private long PulledSinceSnapshot
    {
        get => settings.GetLong(PulledKey);
        set => settings.SetLong(PulledKey, value);
    }

    // --- Lifecycle --------------------------------------------------------------

    /// At launch and when the window comes back to the front.
    public async Task Start()
    {
        if (client.Session is not { } session)
        {
            Phase = SyncPhase.Off;
            return;
        }
        Email = session.Email;
        if (secrets.Read(SecretNames.JournalKey) is { } key)
        {
            store.SetJournalKey(key);
            Phase = SyncPhase.On;
            ConnectRealtime(session.UserId);
            await SyncNow();
        }
        else
        {
            await AdoptAccount();
        }
    }

    /// After a local write: sync a second after typing settles.
    public void SchedulePush()
    {
        if (Phase != SyncPhase.On) return;
        Pending = store.PendingCount();
        pushDelay?.Cancel();
        pushDelay = new CancellationTokenSource();
        _ = SyncAfterPause(pushDelay.Token);
    }

    private async Task SyncAfterPause(CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(1), ct); // resumes on the caller's context
        }
        catch (OperationCanceledException)
        {
            return;
        }
        await SyncNow();
    }

    public async Task SyncNow()
    {
        if (Phase != SyncPhase.On) return;
        if (IsSyncing)
        {
            syncAgain = true;
            return;
        }
        IsSyncing = true;
        try
        {
            do
            {
                syncAgain = false;
                try
                {
                    await Push();
                    var merged = await Pull();
                    Problem = null;
                    LastSynced = DateTimeOffset.Now;
                    if (merged) Merged?.Invoke();
                    await SnapshotIfDue();
                }
                catch (SyncException e) when (e.Problem == SyncProblem.SignedOut)
                {
                    await SignOut();
                    return;
                }
                catch (SyncException e)
                {
                    Problem = e;
                    return;
                }
                catch (CoreException e)
                {
                    Problem = new SyncException(SyncProblem.Server, e.Message);
                    return;
                }
            } while (syncAgain);
            Pending = store.PendingCount();
        }
        finally
        {
            IsSyncing = false;
        }
    }

    // --- Sign-in ----------------------------------------------------------------

    public async Task SendSignInEmail(string address)
    {
        await client.SendSignInEmail(address.Trim());
        Email = address.Trim();
    }

    public async Task Verify(string code)
    {
        var email = Email ?? throw new SyncException(SyncProblem.SignedOut, "no email to verify");
        await client.Verify(email, new string(code.Where(char.IsAsciiDigit).ToArray()));
        await AdoptAccount();
    }

    /// The PKCE verifier of the web sign-in in progress. It lives only in
    /// this process: the second launch the redirect starts hands its
    /// activation to this one (Program.cs).
    private string? oauthVerifier;

    /// Starts Sign in with Google: the address to open in the browser. Its
    /// redirect comes back through <see cref="HandleRedirect"/>.
    public Uri BeginGoogleSignIn()
    {
        var verifier = oauthVerifier = Pkce.Verifier();
        return client.AuthorizeUri("google", Backend.OAuthCallbackUri, Pkce.Challenge(verifier));
    }

    /// The `librelog://auth#…` link from the sign-in email, or the
    /// `librelog://auth/callback?code=…` one from a web sign-in, delivered by
    /// protocol activation.
    public async Task HandleRedirect(Uri uri)
    {
        if (uri.Scheme != "librelog" || uri.Host != "auth") return;
        try
        {
            var query = uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
                .Select(pair => pair.Split('=', 2))
                .Where(kv => kv.Length == 2)
                .ToDictionary(kv => kv[0], kv => Uri.UnescapeDataString(kv[1].Replace('+', ' ')));
            if (query.TryGetValue("code", out var code))
            {
                var verifier = oauthVerifier ?? throw new SyncException(SyncProblem.SignedOut, "no sign-in in progress");
                oauthVerifier = null;
                await client.ExchangeCode(code, verifier);
            }
            else
            {
                client.HandleRedirect(uri);
            }
            await AdoptAccount();
        }
        catch (SyncException e)
        {
            Problem = e;
        }
    }

    public async Task SignOut()
    {
        channel?.Dispose();
        channel = null;
        await client.SignOut();
        secrets.Delete(SecretNames.JournalKey);
        store.ForgetJournalKey();
        Phase = SyncPhase.Off;
        Email = null;
    }

    /// Deletes the account and everything synced to it, then forgets it
    /// here. The journal on this device is untouched, and is queued to upload
    /// in full if the user signs in to a new account later.
    public async Task DeleteAccount()
    {
        await client.Rpc("delete_account", []);
        await SignOut();
        store.ResetSync();
        Pending = store.PendingCount();
    }

    // --- Journal key ------------------------------------------------------------

    /// After sign-in: create the account's key if it has none, or use this
    /// device's stored key if it matches; otherwise ask for it.
    private async Task AdoptAccount()
    {
        try
        {
            var reg = await Register("");
            Email = client.Session?.Email ?? Email;

            if (reg.KeyCheck is { } check)
            {
                if (secrets.Read(SecretNames.JournalKey) is { } stored && store.KeyMatches(stored, check))
                    await Activate(stored);
                else
                    Phase = SyncPhase.NeedsKey;
                return;
            }

            // A brand-new account: this device makes the key.
            var key = store.GenerateJournalKey();
            store.SetJournalKey(key);
            var saved = (await client.Rpc("set_key_check", new JsonObject { ["p_key_check"] = store.KeyCheck() }))?.GetValue<string>();
            if (saved is not null && store.KeyMatches(key, saved))
            {
                NewRecoveryPhrase = store.RecoveryPhrase();
                await Activate(key);
            }
            else
            {
                // Another device set it first, a moment ago.
                store.ForgetJournalKey();
                Phase = SyncPhase.NeedsKey;
            }
        }
        catch (SyncException e)
        {
            Problem = e;
            if (e.Problem == SyncProblem.SignedOut) Phase = SyncPhase.Off;
        }
    }

    /// Restores the key from the 24 words. Throws if they're not this account's.
    public async Task UseRecoveryPhrase(string phrase)
    {
        var key = store.KeyFromRecoveryPhrase(phrase);
        var reg = await Register("");
        if (reg.KeyCheck is not { } check || !store.KeyMatches(key, check))
            throw new SyncException(SyncProblem.Server, "Those words don't match this account's journal key.");
        await Activate(key);
    }

    /// New device: wait for an existing device to answer the code shown.
    public async Task ReceiveKeyByPairing(PairingSession session, CancellationToken ct = default)
    {
        await client.Rpc("pairing_open", new JsonObject { ["p_id"] = session.Id }, ct);
        var deadline = DateTimeOffset.UtcNow.AddMinutes(10); // the server's expiry
        while (DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(PairingPollInterval, ct);
            var payload = await client.Rpc("pairing_take", new JsonObject { ["p_id"] = session.Id }, ct);
            if (payload?.GetValue<string>() is { } p)
            {
                await Activate(session.Receive(p));
                return;
            }
        }
        throw new SyncException(SyncProblem.Server, "No device answered the pairing code.", 408);
    }

    /// Existing device: answers a pasted (or scanned) pairing code.
    public async Task ApprovePairing(string code)
    {
        var parsed = CoreFunctions.ParsePairingCode(code);
        var payload = store.SealKeyForPairing(parsed.PublicKey);
        await client.Rpc("pairing_fulfil", new JsonObject { ["p_id"] = parsed.Id, ["p_payload"] = payload });
    }

    private async Task Activate(string key)
    {
        store.SetJournalKey(key);
        secrets.Write(SecretNames.JournalKey, key);
        // Register again, now with the device's name sealed.
        await Register(store.SealDeviceName(deviceName));
        Phase = SyncPhase.On;
        if (client.Session?.UserId is { Length: > 0 } user) ConnectRealtime(user);
        await SyncNow();
    }

    private async Task<Registration> Register(string sealedName)
    {
        var node = await client.Rpc("register_device",
            new JsonObject { ["p_device_id"] = store.DeviceId(), ["p_name_enc"] = sealedName });
        var rows = node?.Deserialize(SyncJson.Default.ListRegistration) ?? [];
        return rows.FirstOrDefault() ?? new Registration(0, null, null, 0, 0);
    }

    // --- The loop ---------------------------------------------------------------

    private async Task Push()
    {
        while (store.OutgoingBatch(BatchSize) is { } batch)
        {
            await client.Rpc("push_ops", new JsonObject
            {
                ["p_device_id"] = store.DeviceId(),
                ["p_batch_id"] = batch.BatchId,
                ["p_payloads"] = new JsonArray(batch.Payload),
            });
            store.PushSucceeded(batch.BatchId);
            Pending = store.PendingCount();
        }
    }

    /// Returns true if anything merged.
    private async Task<bool> Pull()
    {
        var merged = false;
        while (true)
        {
            List<PulledRow> rows;
            try
            {
                var node = await client.Rpc("pull_ops", new JsonObject
                {
                    ["p_device_id"] = store.DeviceId(),
                    ["p_after"] = store.SyncCursor(),
                    ["p_limit"] = BatchSize,
                });
                rows = node?.Deserialize(SyncJson.Default.ListPulledRow) ?? [];
            }
            catch (SyncException e) when (e.Problem == SyncProblem.BehindCompaction)
            {
                await LoadSnapshot();
                merged = true;
                continue;
            }
            if (rows.Count == 0) return merged;
            var result = store.ApplyIncoming([.. rows.Select(r => new IncomingOps(r.Seq, r.DeviceId, r.Payload))]);
            PulledSinceSnapshot += rows.Count;
            merged |= result.Changed > 0;
            if (rows.Count < BatchSize) return merged;
        }
    }

    private async Task LoadSnapshot()
    {
        var node = await client.Rpc("get_snapshot", new JsonObject { ["p_device_id"] = store.DeviceId() });
        var snap = (node?.Deserialize(SyncJson.Default.ListSnapshotRow) ?? []).FirstOrDefault()
            ?? throw new SyncException(SyncProblem.Server, "no snapshot to catch up from", 404);
        store.ApplySnapshot(snap.Seq, snap.Payload);
        PulledSinceSnapshot = 0;
    }

    private async Task SnapshotIfDue()
    {
        if (PulledSinceSnapshot < SnapshotEvery) return;
        var snap = store.Snapshot();
        await client.Rpc("put_snapshot", new JsonObject
        {
            ["p_device_id"] = store.DeviceId(),
            ["p_seq"] = snap.Seq,
            ["p_payload"] = snap.Payload,
        });
        PulledSinceSnapshot = 0;
    }

    private void ConnectRealtime(string userId)
    {
        if (channel is not null) return;
        var context = SynchronizationContext.Current;
        channel = makeRealtime($"user:{userId}", client, () =>
        {
            // Pings arrive on a socket thread; sync on the caller's thread.
            if (context is null) _ = SyncNow();
            else context.Post(_ => _ = SyncNow(), null);
        });
        channel.Connect();
    }

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
