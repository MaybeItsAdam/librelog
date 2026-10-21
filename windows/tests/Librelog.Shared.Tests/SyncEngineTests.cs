using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Librelog.Core;
using Librelog.Sync;

namespace Librelog.Tests;

/// SyncEngine end to end: real cores on both sides, a fake Supabase in the
/// middle that behaves like supabase/migrations (seq-ordered log, idempotent
/// batches, PT402 / PT409, one-shot pairing rows).
public sealed class SyncEngineTests : IDisposable
{
    private readonly FakeSupabase server = new();
    private readonly List<IDisposable> cleanup = [];
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-sync").FullName;

    public void Dispose()
    {
        foreach (var d in cleanup) d.Dispose();
        Directory.Delete(dir, recursive: true);
    }

    private sealed class NoRealtime : IRealtime
    {
        public void Connect() { }
        public void Dispose() { }
    }

    private (SyncEngine Engine, CoreStore Store) Device(string user, string name, DateTimeOffset? expires = null)
    {
        var store = CoreStore.Open(Path.Combine(dir, $"{name}.sqlite"));
        cleanup.Add(store);
        var secrets = new MemorySecrets();
        if (user.Length > 0)
        {
            var session = new Session(FakeSupabase.Token(user), $"refresh:{user}", expires ?? DateTimeOffset.UtcNow.AddHours(1), user, $"{user}@example.com");
            secrets.Write(SecretNames.Session, JsonSerializer.Serialize(session, SyncJson.Default.Session));
        }
        var client = new SupabaseClient(new HttpClient(server), secrets, new Uri("http://fake/"), "key");
        var engine = new SyncEngine(store, client, secrets, new MemorySettings(), name, (_, _, _) => new NoRealtime())
        {
            PairingPollInterval = TimeSpan.FromMilliseconds(10),
        };
        return (engine, store);
    }

    private static string Write(CoreStore store, string text)
    {
        var e = store.CreateEntry("2026-10-03", "08:30");
        store.SaveBody(e.Id, [new Block(BlockKind.Paragraph, text)]);
        return e.Id;
    }

    [Fact]
    public async Task FirstDeviceMakesTheKeyAndASecondJoinsWithTheRecoveryPhrase()
    {
        var (a, aStore) = Device("u1", "laptop");
        await a.Start();
        Assert.Equal(SyncPhase.On, a.Phase);
        var phrase = Assert.IsType<string>(a.NewRecoveryPhrase);
        Assert.Equal(24, phrase.Split(' ').Length);

        var id = Write(aStore, "Coffee by the canal");
        await a.SyncNow();
        Assert.Equal(0u, a.Pending);
        Assert.DoesNotContain(server.AllPayloads(), p => p.Contains("canal")); // ciphertext only

        var (b, bStore) = Device("u1", "desktop");
        await b.Start();
        Assert.Equal(SyncPhase.NeedsKey, b.Phase);
        await Assert.ThrowsAsync<SyncException>(() => b.UseRecoveryPhrase(new CoreStoreKeyless(aStore).OtherPhrase()));
        var merged = false;
        b.Merged += () => merged = true;
        await b.UseRecoveryPhrase(phrase);
        Assert.Equal(SyncPhase.On, b.Phase);
        Assert.True(merged);
        Assert.Equal("Coffee by the canal", bStore.Entry(id).Body[0].Text);

        // And back the other way.
        bStore.SetTitle(id, "Saturday");
        await b.SyncNow();
        await a.SyncNow();
        Assert.Equal("Saturday", aStore.Entry(id).Title);
    }

    [Fact]
    public async Task PairingMovesTheKeyToANewDevice()
    {
        var (a, aStore) = Device("u2", "laptop");
        await a.Start();
        var id = Write(aStore, "paired");
        await a.SyncNow();

        var (b, bStore) = Device("u2", "desktop");
        await b.Start();
        Assert.Equal(SyncPhase.NeedsKey, b.Phase);

        using var session = new PairingSession();
        var waiting = b.ReceiveKeyByPairing(session);
        await Task.Delay(50);
        await a.ApprovePairing(session.Code);
        await waiting;

        Assert.Equal(SyncPhase.On, b.Phase);
        Assert.Equal("paired", bStore.Entry(id).Body[0].Text);
        Assert.Empty(server.Pairings); // pairing_take deletes the row
    }

    [Fact]
    public async Task ADeviceBehindCompactionCatchesUpFromTheSnapshot()
    {
        var (a, aStore) = Device("u3", "laptop");
        await a.Start();
        var phrase = a.NewRecoveryPhrase!;
        var ids = Enumerable.Range(0, 5).Select(i => Write(aStore, $"entry {i}")).ToList();
        await a.SyncNow();

        // What the nightly job does once a snapshot covers the log.
        var snap = aStore.Snapshot();
        server.Compact("u3", snap.Seq, snap.Payload);

        var (b, bStore) = Device("u3", "desktop");
        await b.Start();
        await b.UseRecoveryPhrase(phrase);
        Assert.Null(b.Problem);
        Assert.All(ids, id => Assert.StartsWith("entry", bStore.Entry(id).Body[0].Text));
    }

    [Fact]
    public async Task AnAccountWithoutSyncReportsItAndKeepsTheChangesQueued()
    {
        server.Inactive.Add("u4");
        var (a, aStore) = Device("u4", "laptop");
        await a.Start();
        Write(aStore, "kept locally");
        await a.SyncNow();
        Assert.Equal(SyncProblem.NotActive, a.Problem?.Problem);
        Assert.True(aStore.PendingCount() > 0);
    }

    [Fact]
    public async Task AReviewAccountSignsInWithItsPassword()
    {
        server.Passwords["appreview@example.com"] = "correct horse";
        var (a, _) = Device("", "laptop");
        await a.Start();
        Assert.Equal(SyncPhase.Off, a.Phase);

        await Assert.ThrowsAsync<SyncException>(() => a.SignIn("appreview@example.com", "wrong"));
        Assert.Equal(SyncPhase.Off, a.Phase);

        await a.SignIn(" appreview@example.com ", "correct horse");
        Assert.Equal(SyncPhase.On, a.Phase);
        Assert.Equal("appreview@example.com", a.Email);
    }

    [Fact]
    public async Task DeletingTheAccountEmptiesTheServerAndKeepsTheJournal()
    {
        var (a, aStore) = Device("u1", "laptop");
        await a.Start();
        var id = Write(aStore, "Coffee by the canal");
        await a.SyncNow();
        Assert.Equal(0u, a.Pending);

        await a.DeleteAccount();
        Assert.False(server.HasAccount("u1"));
        Assert.Equal(SyncPhase.Off, a.Phase);
        Assert.Null(a.Email);
        Assert.Equal("Coffee by the canal", aStore.Entry(id).Body[0].Text);
        Assert.True(a.Pending > 0); // queued again for whichever account comes next
    }

    [Fact]
    public async Task WithoutASessionSyncIsOffAndNothingIsSent()
    {
        var (a, _) = Device("", "offline");
        await a.Start();
        Assert.Equal(SyncPhase.Off, a.Phase);
        await a.SyncNow();
        Assert.Equal(0, server.Requests);
    }

    [Fact]
    public void TheSignInLinkBecomesASession()
    {
        var secrets = new MemorySecrets();
        var client = new SupabaseClient(new HttpClient(server), secrets, new Uri("http://fake/"), "key");
        client.HandleRedirect(new Uri($"librelog://auth#access_token={FakeSupabase.Token("u9")}&refresh_token=r&expires_in=3600&type=magiclink"));
        Assert.Equal("u9", client.Session?.UserId);
        Assert.NotNull(secrets.Read(SecretNames.Session));
        Assert.Throws<SyncException>(() => client.HandleRedirect(new Uri("librelog://auth#error_description=Link+expired")));
    }

    [Fact]
    public async Task ALinkWithARepeatedParameterIsReportedNotThrown()
    {
        var secrets = new MemorySecrets();
        var client = new SupabaseClient(new HttpClient(server), secrets, new Uri("http://fake/"), "key");
        client.HandleRedirect(new Uri($"librelog://auth#access_token={FakeSupabase.Token("u9")}&access_token=x&refresh_token=r&refresh_token=s"));
        Assert.Equal("u9", client.Session?.UserId);

        var (a, _) = Device("", "laptop");
        await a.HandleRedirect(new Uri("librelog://auth?a=1&a=2"));
        Assert.NotNull(a.Problem);
        Assert.Equal(SyncPhase.Off, a.Phase);
    }

    [Fact]
    public async Task ASessionThatCanNoLongerRefreshIsSignedOut()
    {
        server.RefreshFails = true;
        var (a, _) = Device("u1", "laptop", expires: DateTimeOffset.UtcNow.AddMinutes(-5));
        await a.Start();
        Assert.Equal(SyncPhase.Off, a.Phase);
        Assert.Equal(SyncProblem.SignedOut, a.Problem?.Problem);
    }

    [Fact]
    public async Task ARejectedTokenIsRefreshedBeforeSigningOut()
    {
        var (a, aStore) = Device("u1", "laptop");
        await a.Start();
        Assert.Equal(SyncPhase.On, a.Phase);

        server.Rejected.Add(FakeSupabase.Token("u1"));
        Write(aStore, "Coffee by the canal");
        await a.SyncNow();
        Assert.Equal(SyncPhase.On, a.Phase);
        Assert.Null(a.Problem);
        Assert.Equal(0u, a.Pending);
    }

    [Fact]
    public async Task SigningOutEndsOnlyThisDevicesSession()
    {
        var (a, _) = Device("u1", "laptop");
        await a.Start();
        await a.SignOut();
        Assert.Equal("?scope=local", server.LogoutQuery);
        Assert.Equal(SyncPhase.Off, a.Phase);
    }

    [Fact]
    public void RealtimeMessages()
    {
        var join = RealtimeChannel.JoinMessage("user:u1", "tok", "1");
        Assert.Equal("realtime:user:u1", join["topic"]!.GetValue<string>());
        Assert.True(join["payload"]!["config"]!["private"]!.GetValue<bool>());
        Assert.True(RealtimeChannel.IsOpsPing("""{"event":"broadcast","payload":{"event":"ops","payload":{"seq":3}}}"""));
        Assert.False(RealtimeChannel.IsOpsPing("""{"event":"phx_reply","payload":{}}"""));
        Assert.StartsWith("wss://", RealtimeChannel.SocketUri().ToString());
    }

    /// A key that isn't the account's, to check a wrong phrase is refused.
    private sealed class CoreStoreKeyless(CoreStore store)
    {
        public string OtherPhrase()
        {
            using var other = CoreStore.Open(Path.Combine(Path.GetTempPath(), $"dl-other-{Guid.NewGuid()}.sqlite"));
            other.SetJournalKey(other.GenerateJournalKey());
            _ = store;
            return other.RecoveryPhrase();
        }
    }
}

/// The librelog RPCs from supabase/migrations, in memory.
internal sealed class FakeSupabase : HttpMessageHandler
{
    private sealed class Account
    {
        public string? KeyCheck;
        public long LastSeq;
        public long CompactedThrough;
        public (long Seq, string Payload)? Snapshot;
        public readonly List<(long Seq, string Device, string Batch, string Payload)> Ops = [];
    }

    private readonly Dictionary<string, Account> accounts = [];
    public readonly Dictionary<string, string?> Pairings = [];
    public readonly HashSet<string> Inactive = [];
    public int Requests;

    /// Refreshing a session fails as Supabase does once the refresh token
    /// is gone (400 invalid_grant).
    public bool RefreshFails;

    /// Access tokens answered with 401, as if revoked before they expire.
    public readonly HashSet<string> Rejected = [];

    /// The query of the last logout call.
    public string? LogoutQuery;

    public static string Token(string user, string? generation = null)
    {
        static string B64(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var claims = generation is null ? $$"""{"sub":"{{user}}"}""" : $$"""{"sub":"{{user}}","gen":"{{generation}}"}""";
        return $"{B64("{}")}.{B64(claims)}.sig";
    }

    public bool HasAccount(string user) => accounts.ContainsKey(user);

    /// Accounts that can sign in with a password, by email.
    public readonly Dictionary<string, string> Passwords = [];

    public IEnumerable<string> AllPayloads() => accounts.Values.SelectMany(a => a.Ops.Select(o => o.Payload));

    public void Compact(string user, long through, string snapshot)
    {
        var a = accounts[user];
        a.Snapshot = (through, snapshot);
        a.Ops.RemoveAll(o => o.Seq <= through);
        a.CompactedThrough = through;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        lock (this) Requests++;
        var path = request.RequestUri!.AbsolutePath;
        if (path == "/auth/v1/token" && request.RequestUri.Query.Contains("grant_type=password"))
        {
            var creds = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!.AsObject();
            var email = creds["email"]!.GetValue<string>();
            if (!Passwords.TryGetValue(email, out var pw) || pw != creds["password"]!.GetValue<string>())
                return Reply(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"Invalid login credentials"}""");
            var reviewer = email.Split('@')[0];
            return Json(new JsonObject
            {
                ["access_token"] = Token(reviewer), ["refresh_token"] = $"refresh:{reviewer}", ["expires_in"] = 3600,
                ["user"] = new JsonObject { ["id"] = reviewer, ["email"] = email },
            });
        }
        if (path == "/auth/v1/token" && request.RequestUri.Query.Contains("grant_type=refresh_token"))
        {
            if (RefreshFails)
                return Reply(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}""");
            var refresh = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!["refresh_token"]!.GetValue<string>();
            var owner = refresh["refresh:".Length..];
            return Json(new JsonObject
            {
                ["access_token"] = Token(owner, "refreshed"), ["refresh_token"] = refresh, ["expires_in"] = 3600,
                ["user"] = new JsonObject { ["id"] = owner, ["email"] = $"{owner}@example.com" },
            });
        }
        if (path == "/auth/v1/logout")
        {
            LogoutQuery = request.RequestUri.Query;
            return Reply(HttpStatusCode.NoContent, "");
        }
        if (!path.StartsWith("/rest/v1/rpc/", StringComparison.Ordinal)) return Reply(HttpStatusCode.NotFound, "{}");
        Assert.Equal("librelog", request.Headers.GetValues("Content-Profile").Single());
        if (Rejected.Contains(request.Headers.Authorization!.Parameter!))
            return Reply(HttpStatusCode.Unauthorized, """{"message":"JWT expired"}""");
        var user = SupabaseClient.JwtClaims(request.Headers.Authorization!.Parameter!)["sub"];
        var args = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!.AsObject();
        lock (this) return Handle(path["/rest/v1/rpc/".Length..], user, args);
    }

    private HttpResponseMessage Handle(string rpc, string user, JsonObject args)
    {
        var a = accounts.TryGetValue(user, out var found) ? found : accounts[user] = new Account();
        var active = !Inactive.Contains(user);
        switch (rpc)
        {
            case "register_device":
                return Json(new JsonArray(new JsonObject
                {
                    ["last_seq"] = a.LastSeq, ["sync_until"] = active ? "2027-10-03T00:00:00Z" : null,
                    ["key_check"] = a.KeyCheck, ["quota_bytes"] = 524288000, ["bytes_used"] = 0,
                }));
            case "set_key_check":
                a.KeyCheck ??= args["p_key_check"]!.GetValue<string>();
                return Json(JsonValue.Create(a.KeyCheck));
            case "push_ops":
            {
                if (!active) return Reply((HttpStatusCode)402, """{"message":"sync is not active for this account"}""");
                var batch = args["p_batch_id"]!.GetValue<string>();
                if (!a.Ops.Any(o => o.Batch == batch))
                    foreach (var p in args["p_payloads"]!.AsArray())
                        a.Ops.Add((++a.LastSeq, args["p_device_id"]!.GetValue<string>(), batch, p!.GetValue<string>()));
                return Json(JsonValue.Create(a.LastSeq));
            }
            case "pull_ops":
            {
                if (!active) return Reply((HttpStatusCode)402, """{"message":"sync is not active for this account"}""");
                var after = args["p_after"]!.GetValue<long>();
                if (after < a.CompactedThrough) return Reply(HttpStatusCode.Conflict, """{"message":"cursor is behind compaction"}""");
                var limit = args["p_limit"]?.GetValue<int>() ?? 500;
                var rows = new JsonArray();
                foreach (var o in a.Ops.Where(o => o.Seq > after).Take(limit))
                    rows.Add(new JsonObject { ["seq"] = o.Seq, ["device_id"] = o.Device, ["payload"] = o.Payload });
                return Json(rows);
            }
            case "get_snapshot":
                return Json(a.Snapshot is { } s ? new JsonArray(new JsonObject { ["seq"] = s.Seq, ["payload"] = s.Payload }) : []);
            case "put_snapshot":
                a.Snapshot = (args["p_seq"]!.GetValue<long>(), args["p_payload"]!.GetValue<string>());
                return Reply(HttpStatusCode.NoContent, "");
            case "pairing_open":
                Pairings[args["p_id"]!.GetValue<string>()] = null;
                return Json(JsonValue.Create("2026-10-03T10:10:00Z"));
            case "pairing_fulfil":
            {
                var id = args["p_id"]!.GetValue<string>();
                if (!Pairings.ContainsKey(id)) return Reply(HttpStatusCode.NotFound, """{"message":"unknown or expired pairing"}""");
                Pairings[id] = args["p_payload"]!.GetValue<string>();
                return Reply(HttpStatusCode.NoContent, "");
            }
            case "pairing_take":
            {
                var id = args["p_id"]!.GetValue<string>();
                if (!Pairings.TryGetValue(id, out var payload)) return Reply(HttpStatusCode.NotFound, """{"message":"unknown or expired pairing"}""");
                if (payload is null) return Json(null);
                Pairings.Remove(id);
                return Json(JsonValue.Create(payload));
            }
            case "delete_account":
                accounts.Remove(user);
                return Reply(HttpStatusCode.NoContent, "");
            default:
                return Reply(HttpStatusCode.NotFound, $$"""{"message":"no function {{rpc}}"}""");
        }
    }

    private static HttpResponseMessage Json(JsonNode? body) => Reply(HttpStatusCode.OK, body?.ToJsonString() ?? "null");

    private static HttpResponseMessage Reply(HttpStatusCode status, string body) =>
        new(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
}
