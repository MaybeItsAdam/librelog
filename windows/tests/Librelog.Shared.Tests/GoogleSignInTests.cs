using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Librelog.Core;
using Librelog.Sync;

namespace Librelog.Tests;

/// Sign in with Google through Supabase's web flow (PKCE): the address the
/// browser is sent to, and the code that comes back through the protocol
/// handler being exchanged for a session.
public sealed class GoogleSignInTests : IDisposable
{
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-google").FullName;

    public void Dispose() => Directory.Delete(dir, recursive: true);

    /// Answers the PKCE token grant with a session for `u1`, and hands the
    /// sync RPCs on to the fake database.
    private sealed class Auth() : DelegatingHandler(new FakeSupabase())
    {
        public JsonObject? TokenBody;
        public string? TokenQuery;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (request.RequestUri!.AbsolutePath != "/auth/v1/token") return await base.SendAsync(request, ct);
            TokenQuery = request.RequestUri.Query;
            TokenBody = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!.AsObject();
            var session = new JsonObject
            {
                ["access_token"] = FakeSupabase.Token("u1"), ["refresh_token"] = "refresh", ["expires_in"] = 3600,
                ["user"] = new JsonObject { ["id"] = "u1", ["email"] = "u1@example.com" },
            };
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(session.ToJsonString(), Encoding.UTF8, "application/json"),
            };
        }
    }

    private sealed class NoRealtime : IRealtime
    {
        public void Connect() { }
        public void Dispose() { }
    }

    [Fact]
    public void TheChallengeIsTheVerifiersSha256InBase64Url()
    {
        // RFC 7636, appendix B.
        Assert.Equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        var verifier = Pkce.Verifier();
        Assert.Equal(43, verifier.Length);
        Assert.DoesNotContain('=', verifier);
        Assert.NotEqual(verifier, Pkce.Verifier());
    }

    [Fact]
    public async Task TheCodeFromTheRedirectSignsIn()
    {
        var auth = new Auth();
        var store = CoreStore.Open(Path.Combine(dir, "pc.sqlite"));
        using var _ = store;
        var secrets = new MemorySecrets();
        var client = new SupabaseClient(new HttpClient(auth), secrets, new Uri("http://fake/"), "key");
        var engine = new SyncEngine(store, client, secrets, new MemorySettings(), "pc", (_, _, _) => new NoRealtime());

        var start = engine.BeginGoogleSignIn();
        Assert.Equal("/auth/v1/authorize", start.AbsolutePath);
        var query = start.Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2))
            .ToDictionary(kv => kv[0], kv => Uri.UnescapeDataString(kv[1]));
        Assert.Equal("google", query["provider"]);
        Assert.Equal("librelog://auth/callback", query["redirect_to"]);
        Assert.Equal("s256", query["code_challenge_method"]);

        await engine.HandleRedirect(new Uri("librelog://auth/callback?code=the-code"));

        Assert.Null(engine.Problem);
        Assert.Equal("?grant_type=pkce", auth.TokenQuery);
        Assert.Equal("the-code", auth.TokenBody!["auth_code"]!.GetValue<string>());
        var verifier = auth.TokenBody["code_verifier"]!.GetValue<string>();
        Assert.Equal(query["code_challenge"], Pkce.Challenge(verifier));
        Assert.Equal(SyncPhase.On, engine.Phase);
        Assert.Equal("u1@example.com", engine.Email);
    }

    [Fact]
    public async Task ACodeWithNoSignInInProgressIsRefused()
    {
        var store = CoreStore.Open(Path.Combine(dir, "pc.sqlite"));
        using var _ = store;
        var secrets = new MemorySecrets();
        var client = new SupabaseClient(new HttpClient(new Auth()), secrets, new Uri("http://fake/"), "key");
        var engine = new SyncEngine(store, client, secrets, new MemorySettings(), "pc", (_, _, _) => new NoRealtime());

        await engine.HandleRedirect(new Uri("librelog://auth/callback?code=stray"));

        Assert.Equal(SyncProblem.SignedOut, engine.Problem?.Problem);
        Assert.Equal(SyncPhase.Off, engine.Phase);
    }
}
