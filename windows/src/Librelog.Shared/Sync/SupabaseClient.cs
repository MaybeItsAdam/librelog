using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Librelog.Sync;

/// The Supabase project the apps sync through (supabase/README.md). The
/// publishable key is public by design: it only identifies the project;
/// row-level security and the sync functions decide what a caller may do.
public static class Backend
{
    public static readonly Uri Url = new("https://wzkhuilypzapqkrjxeku.supabase.co/");
    public const string PublishableKey = "sb_publishable_tNzC2ZUmrDOFk_QTBWYY7A_KDfU-8aY";
    public const string RedirectUri = "librelog://auth";
    /// Where a provider's web sign-in (Google) comes back to, with `?code=…`.
    public const string OAuthCallbackUri = "librelog://auth/callback";
}

public sealed record Session(string AccessToken, string RefreshToken, DateTimeOffset ExpiresAt, string UserId, string? Email)
{
    [JsonIgnore] public bool NeedsRefresh => ExpiresAt - DateTimeOffset.UtcNow < TimeSpan.FromSeconds(60);
}

public enum SyncProblem
{
    SignedOut,
    NotActive,         // PT402: no paid-through date
    UnknownDevice,     // PT404 from a device-scoped call
    BehindCompaction,  // PT409: load the snapshot first
    OverQuota,         // PT413
    Server,
    Offline,
}

/// What a sync call failed with. The database raises PTxxx codes, which
/// PostgREST returns as HTTP status xxx (supabase/README.md).
public sealed class SyncException(SyncProblem problem, string message, int status = 0) : Exception(message)
{
    public SyncProblem Problem { get; } = problem;
    public int Status { get; } = status;

    public string Describe() => Problem switch
    {
        SyncProblem.SignedOut => "Signed out",
        SyncProblem.NotActive => "Sync isn't active for this account",
        SyncProblem.UnknownDevice => "This device was removed from sync",
        SyncProblem.BehindCompaction => "Catching up from a snapshot",
        SyncProblem.OverQuota => "Sync storage is full",
        SyncProblem.Offline => "Offline",
        _ => $"{Status}: {Message}",
    };
}

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.SnakeCaseLower)]
[JsonSerializable(typeof(Session))]
[JsonSerializable(typeof(List<Registration>))]
[JsonSerializable(typeof(List<PulledRow>))]
[JsonSerializable(typeof(List<SnapshotRow>))]
internal sealed partial class SyncJson : JsonSerializerContext;

internal sealed record Registration(long LastSeq, string? SyncUntil, string? KeyCheck, long QuotaBytes, long BytesUsed);
internal sealed record PulledRow(long Seq, string DeviceId, string Payload);
internal sealed record SnapshotRow(long Seq, string Payload);

/// Supabase Auth and the `librelog` RPCs over plain HttpClient, mirroring
/// apple/App/Sync/SupabaseClient.swift. No SDK: the surface is a handful of
/// POSTs, and owning it keeps the app small and AOT-friendly.
public sealed class SupabaseClient(HttpClient http, ISecrets secrets, Uri? url = null, string? key = null)
{
    private readonly Uri url = url ?? Backend.Url;
    private readonly string key = key ?? Backend.PublishableKey;
    private readonly SemaphoreSlim refreshing = new(1, 1);

    public Session? Session { get; private set; } = Load(secrets);

    private static Session? Load(ISecrets secrets)
    {
        try
        {
            return secrets.Read(SecretNames.Session) is { } json ? JsonSerializer.Deserialize(json, SyncJson.Default.Session) : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    // --- Auth -------------------------------------------------------------------

    /// Emails a sign-in message: a link back into the app with the default
    /// template, a 6-digit code with the custom one.
    public async Task SendSignInEmail(string email, CancellationToken ct = default) =>
        await Post($"auth/v1/otp?redirect_to={Uri.EscapeDataString(Backend.RedirectUri)}",
            new JsonObject { ["email"] = email, ["create_user"] = true }, auth: false, ct);

    public async Task Verify(string email, string code, CancellationToken ct = default) =>
        StoreSession(await Post("auth/v1/verify",
            new JsonObject { ["type"] = "email", ["email"] = email, ["token"] = code }, auth: false, ct));

    /// Email and password, for accounts made with one in the dashboard (the
    /// App Review account). Everyone else signs in with a code or a provider.
    public async Task SignIn(string email, string password, CancellationToken ct = default) =>
        StoreSession(await Post("auth/v1/token?grant_type=password",
            new JsonObject { ["email"] = email, ["password"] = password }, auth: false, ct));

    /// Where to send the browser to sign in with `provider` through Supabase's
    /// web flow (PKCE). It comes back to `redirect` with `?code=…`, which
    /// <see cref="ExchangeCode"/> turns into a session.
    public Uri AuthorizeUri(string provider, string redirect, string challenge) =>
        new(url, "auth/v1/authorize"
            + $"?provider={Uri.EscapeDataString(provider)}"
            + $"&redirect_to={Uri.EscapeDataString(redirect)}"
            + $"&code_challenge={Uri.EscapeDataString(challenge)}"
            + "&code_challenge_method=s256");

    /// The second half of the PKCE web flow: the code from the redirect and
    /// the verifier whose hash went into <see cref="AuthorizeUri"/>.
    public async Task ExchangeCode(string code, string verifier, CancellationToken ct = default) =>
        StoreSession(await Post("auth/v1/token?grant_type=pkce",
            new JsonObject { ["auth_code"] = code, ["code_verifier"] = verifier }, auth: false, ct));

    /// The `librelog://auth#access_token=…` link from the sign-in email.
    public void HandleRedirect(Uri redirect)
    {
        var fragment = redirect.Fragment.TrimStart('#');
        if (fragment.Length == 0) fragment = redirect.Query.TrimStart('?');
        var p = FormParams(fragment);
        if (p.TryGetValue("error_description", out var error)) throw new SyncException(SyncProblem.Server, error, 400);
        if (!p.TryGetValue("access_token", out var access) || !p.TryGetValue("refresh_token", out var refresh))
            throw new SyncException(SyncProblem.SignedOut, "the sign-in link had no session");
        var expiresIn = double.TryParse(p.GetValueOrDefault("expires_in"), NumberStyles.Float, CultureInfo.InvariantCulture, out var e) ? e : 3600;
        var claims = JwtClaims(access);
        Save(new Session(access, refresh, DateTimeOffset.UtcNow.AddSeconds(expiresIn),
            claims.GetValueOrDefault("sub") ?? "", claims.GetValueOrDefault("email")));
    }

    public async Task SignOut()
    {
        if (Session is { } s)
        {
            try
            {
                // `local`: end this device's session only. The default,
                // `global`, would sign every other device out too.
                using var req = new HttpRequestMessage(HttpMethod.Post, new Uri(url, "auth/v1/logout?scope=local"));
                req.Headers.Add("apikey", key);
                req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", s.AccessToken);
                using var _ = await http.SendAsync(req);
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
            {
                // Offline or timed out: signing out locally still counts.
            }
        }
        Session = null;
        secrets.Delete(SecretNames.Session);
    }

    /// A current access token, refreshing first if it's about to expire.
    public async Task<string> AccessToken(CancellationToken ct = default)
    {
        var s = Session ?? throw new SyncException(SyncProblem.SignedOut, "signed out");
        if (!s.NeedsRefresh) return s.AccessToken;
        return await Refresh(rejected: null, ct);
    }

    /// Swaps the refresh token for a new session, once across concurrent
    /// callers. With `rejected`, refreshes if the session still holds that
    /// access token (the server turned it down); otherwise only if it's
    /// about to expire.
    private async Task<string> Refresh(string? rejected, CancellationToken ct)
    {
        await refreshing.WaitAsync(ct);
        try
        {
            var s = Session ?? throw new SyncException(SyncProblem.SignedOut, "signed out");
            if (rejected is null ? s.NeedsRefresh : s.AccessToken == rejected)
            {
                string json;
                try
                {
                    json = await Post("auth/v1/token?grant_type=refresh_token",
                        new JsonObject { ["refresh_token"] = s.RefreshToken }, auth: false, ct);
                }
                catch (SyncException e) when (e.Problem == SyncProblem.Server && e.Status is >= 400 and < 500 and not 408 and not 429)
                {
                    // invalid_grant, "Refresh Token Not Found", revoked: the
                    // session is over, and no retry will bring it back.
                    throw new SyncException(SyncProblem.SignedOut, e.Message, e.Status);
                }
                StoreSession(json);
            }
            return Session?.AccessToken ?? throw new SyncException(SyncProblem.SignedOut, "signed out");
        }
        finally
        {
            refreshing.Release();
        }
    }

    // --- RPC --------------------------------------------------------------------

    /// Calls `librelog.<name>` and returns its JSON result.
    public async Task<JsonNode?> Rpc(string name, JsonObject args, CancellationToken ct = default)
    {
        var body = await Post($"rest/v1/rpc/{name}", args, auth: true, ct);
        return body.Length == 0 ? null : JsonNode.Parse(body);
    }

    // --- Plumbing ---------------------------------------------------------------

    private async Task<string> Post(string path, JsonObject body, bool auth, CancellationToken ct, bool retried = false)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, new Uri(url, path))
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        req.Headers.Add("apikey", key);
        string? token = null;
        if (auth)
        {
            token = await AccessToken(ct);
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            req.Headers.Add("Content-Profile", "librelog");
        }

        HttpResponseMessage response;
        try
        {
            response = await http.SendAsync(req, ct);
        }
        catch (HttpRequestException e)
        {
            throw new SyncException(SyncProblem.Offline, e.Message);
        }
        catch (TaskCanceledException e) when (!ct.IsCancellationRequested)
        {
            throw new SyncException(SyncProblem.Offline, e.Message); // timed out
        }
        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(ct);
            var status = (int)response.StatusCode;
            if (response.IsSuccessStatusCode) return text;
            if (status == (int)HttpStatusCode.Unauthorized && token is not null && !retried)
            {
                // A token the server turned down before its time (revoked, a
                // clock out of step): refresh and try once more before
                // treating it as signed out, which forgets the journal key.
                await Refresh(rejected: token, ct);
                return await Post(path, body, auth, ct, retried: true);
            }
            var message = ErrorMessage(text);
            throw status switch
            {
                (int)HttpStatusCode.Unauthorized when auth => new SyncException(SyncProblem.SignedOut, message, status),
                402 => new SyncException(SyncProblem.NotActive, message, status),
                404 when message.Contains("unknown device", StringComparison.Ordinal) =>
                    new SyncException(SyncProblem.UnknownDevice, message, status),
                409 => new SyncException(SyncProblem.BehindCompaction, message, status),
                413 => new SyncException(SyncProblem.OverQuota, message, status),
                _ => new SyncException(SyncProblem.Server, message, status),
            };
        }
    }

    private static string ErrorMessage(string body)
    {
        try
        {
            if (JsonNode.Parse(body) is JsonObject o)
                foreach (var k in new[] { "message", "msg", "error_description" })
                    if (o[k]?.GetValue<string>() is { } m) return m;
        }
        catch (JsonException)
        {
        }
        return body;
    }

    private void StoreSession(string json)
    {
        var o = JsonNode.Parse(json) as JsonObject;
        var access = o?["access_token"]?.GetValue<string>();
        var refresh = o?["refresh_token"]?.GetValue<string>();
        if (access is null || refresh is null) throw new SyncException(SyncProblem.Server, "unexpected auth response", 500);
        var expiresIn = o!["expires_in"]?.GetValue<double>() ?? 3600;
        var user = o["user"] as JsonObject;
        Save(new Session(access, refresh, DateTimeOffset.UtcNow.AddSeconds(expiresIn),
            user?["id"]?.GetValue<string>() ?? JwtClaims(access).GetValueOrDefault("sub") ?? "",
            user?["email"]?.GetValue<string>()));
    }

    private void Save(Session s)
    {
        Session = s;
        secrets.Write(SecretNames.Session, JsonSerializer.Serialize(s, SyncJson.Default.Session));
    }

    /// `a=1&b=2` from a query or fragment. A key given twice keeps its first
    /// value: the link comes from outside, so it mustn't be able to throw.
    internal static Dictionary<string, string> FormParams(string s)
    {
        var p = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in s.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var kv = pair.Split('=', 2);
            if (kv.Length == 2) p.TryAdd(kv[0], Uri.UnescapeDataString(kv[1].Replace('+', ' ')));
        }
        return p;
    }

    internal static Dictionary<string, string> JwtClaims(string jwt)
    {
        var parts = jwt.Split('.');
        if (parts.Length < 2) return [];
        var b64 = parts[1].Replace('-', '+').Replace('_', '/');
        b64 += new string('=', (4 - b64.Length % 4) % 4);
        try
        {
            var o = JsonNode.Parse(Convert.FromBase64String(b64)) as JsonObject;
            return o?.Where(kv => kv.Value is JsonValue v && v.TryGetValue<string>(out _))
                .ToDictionary(kv => kv.Key, kv => kv.Value!.GetValue<string>()) ?? [];
        }
        catch (Exception e) when (e is FormatException or JsonException or ArgumentException or InvalidOperationException)
        {
            return []; // not a JWT we can read, or one with a claim given twice
        }
    }
}
