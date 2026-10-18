namespace Librelog.Settings;

// Settings → Appearance (docs/appearance.md). Synced through the core's
// setting/set_setting, so every device shows the same choices. On the wire
// each value is its lower-case name; anything unset or unknown is the default.

public enum ThemeChoice { System, Light, Dark }

public enum AccentChoice { Azure, Emerald, Raspberry, Amber, Purple, Pink, Orange }

public enum FontChoice { Arvo, Sans, Serif, Mono }

public enum TextSizeChoice { Small, Default, Large, Larger }

public sealed record AppearanceSettings(ThemeChoice Theme, AccentChoice Accent, FontChoice Font, TextSizeChoice TextSize)
{
    public const string ThemeKey = "appearance.theme";
    public const string AccentKey = "appearance.accent";
    public const string FontKey = "appearance.font";
    public const string TextSizeKey = "appearance.text_size";

    public static readonly AppearanceSettings Default =
        new(ThemeChoice.System, AccentChoice.Azure, FontChoice.Arvo, TextSizeChoice.Default);

    /// Reads all four through `setting` (the store's Setting).
    public static AppearanceSettings Read(Func<string, string?> setting) => new(
        Parse(setting(ThemeKey), Default.Theme),
        Parse(setting(AccentKey), Default.Accent),
        Parse(setting(FontKey), Default.Font),
        Parse(setting(TextSizeKey), Default.TextSize));

    /// The stored form of a choice: "dark", "emerald", "larger".
    public static string Wire<T>(T value) where T : struct, Enum => value.ToString().ToLowerInvariant();

    /// A stored value back to a choice; null, unknown or differently-cased
    /// values (from a newer app, say) fall back.
    public static T Parse<T>(string? value, T fallback) where T : struct, Enum
    {
        if (value is null) return fallback;
        foreach (var v in Enum.GetValues<T>())
            if (Wire(v) == value) return v;
        return fallback;
    }

    /// Multiplies the reading face's sizes, on top of the system's text scale.
    public double Scale => TextSize switch
    {
        TextSizeChoice.Small => 0.9,
        TextSizeChoice.Large => 1.15,
        TextSizeChoice.Larger => 1.3,
        _ => 1.0,
    };

    /// The accent's hex from the house palette (SPEC.md §14), 0xRRGGBB.
    public static uint AccentHex(AccentChoice accent) => accent switch
    {
        AccentChoice.Emerald => 0x4CC38E,
        AccentChoice.Raspberry => 0xD62246,
        AccentChoice.Amber => 0xFFBF00,
        AccentChoice.Purple => 0x7A4DE8,
        AccentChoice.Pink => 0xFF88DC,
        AccentChoice.Orange => 0xFF6B2B,
        _ => 0x007FFF,
    };

    public uint AccentColor => AccentHex(Accent);

    /// Amber is too light for white text: draw on it in ink.
    public static bool InkOnAccent(AccentChoice accent) => accent == AccentChoice.Amber;

    /// The family name of the reading face, as the editor asks for it.
    /// `sans` and `serif` are Windows' own; `arvo` and `mono` ship with the app.
    public string ReadingFamily => Font switch
    {
        FontChoice.Sans => "Segoe UI",
        FontChoice.Serif => "Cambria",
        FontChoice.Mono => "Geist Mono",
        _ => "Arvo",
    };
}
