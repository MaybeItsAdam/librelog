using System.Security.Cryptography;
using System.Text;

namespace Librelog.Sync;

/// Proof Key for Code Exchange (RFC 7636): Supabase is shown the verifier's
/// hash when a web sign-in starts and the verifier itself when it ends.
public static class Pkce
{
    public static string Verifier() => Base64Url(RandomNumberGenerator.GetBytes(32));

    public static string Challenge(string verifier) => Base64Url(SHA256.HashData(Encoding.UTF8.GetBytes(verifier)));

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
