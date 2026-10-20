namespace Librelog.Privacy;

/// Choosing a lock password (docs/locked-notes.md): at least four
/// characters, typed twice. The core enforces the length too.
public static class LockPassword
{
    public const int MinLength = 4;

    /// What's wrong, or null when it can be used.
    public static string? Problem(string password, string again)
    {
        // Counted as the core counts: characters, not UTF-16 units.
        if (password.EnumerateRunes().Count() < MinLength) return $"Use at least {MinLength} characters.";
        if (password != again) return "The two passwords don't match.";
        return null;
    }

    /// The core's error kind for a wrong lock password.
    public static bool IsWrongPassword(Core.CoreException e) => e.Kind == "crypto";

    public const string WrongPassword = "That's not the lock password";
}
