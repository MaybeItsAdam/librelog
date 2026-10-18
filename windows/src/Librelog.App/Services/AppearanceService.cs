using Librelog.Settings;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace Librelog.App.Services;

/// Settings → Appearance applied to the running app (docs/appearance.md):
/// the theme on the window's root, the accent by recolouring the accent
/// brushes in place, and the reading face for entries, titles and the editor.
/// Read at launch and after every sync pull; a change shows at once.
public static class AppearanceService
{
    /// Every brush that carries the primary role, ours and WinUI's.
    private static readonly string[] AccentKeys =
    [
        "AccentBrush",
        "AzureTintBrush", // keeps its own 10% opacity: the multi-day band
        "AccentFillColorDefaultBrush",
        "AccentFillColorSecondaryBrush",
        "AccentFillColorTertiaryBrush",
        "AccentTextFillColorPrimaryBrush",
        "AccentTextFillColorSecondaryBrush",
        "AccentTextFillColorTertiaryBrush",
        "TextControlSelectionHighlightColor",
    ];

    public static AppearanceSettings Current { get; private set; } = AppearanceSettings.Default;

    /// The reading and writing face, and its bold for titles.
    public static FontFamily ReadingFont { get; private set; } = null!;
    public static FontFamily TitleFont { get; private set; } = null!;

    /// Multiplies reading sizes (on top of Windows' own text scaling).
    public static double Scale => Current.Scale;

    /// Before the window exists: the choices, the accent and the fonts.
    public static void Load()
    {
        Apply(AppearanceSettings.Read(AppServices.Store.Setting), notify: false);
        AppServices.Sync.Merged += () => _ = Refresh();
    }

    /// Re-reads the settings (another device may have changed them).
    public static async Task Refresh()
    {
        var next = await AppServices.Run(s => AppearanceSettings.Read(s.Setting));
        if (next != Current) Apply(next, notify: true);
    }

    /// Stores one choice (null for the default) and applies it.
    public static async Task Set(string key, string? value)
    {
        await AppServices.Write(s => s.SetSetting(key, value));
        await Refresh();
    }

    /// The window's root takes the theme; everything under it follows.
    public static void ApplyTheme()
    {
        if (App.Current?.Window?.Content is not FrameworkElement root) return;
        root.RequestedTheme = Current.Theme switch
        {
            ThemeChoice.Light => ElementTheme.Light,
            ThemeChoice.Dark => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
    }

    private static void Apply(AppearanceSettings next, bool notify)
    {
        var readingChanged = next.Font != Current.Font || next.TextSize != Current.TextSize || ReadingFont is null;
        Current = next;

        var accent = Rgb(next.AccentColor);
        foreach (var key in AccentKeys) Recolor(key, accent);
        Recolor("TextOnAccentFillColorPrimaryBrush", AppearanceSettings.InkOnAccent(next.Accent) ? Rgb(0x444054) : Rgb(0xFFFFFF));

        var res = Application.Current.Resources;
        (ReadingFont, TitleFont) = next.Font switch
        {
            FontChoice.Sans => (new FontFamily("Segoe UI"), new FontFamily("Segoe UI")),
            FontChoice.Serif => (new FontFamily("Cambria"), new FontFamily("Cambria")),
            FontChoice.Mono => ((FontFamily)res["Mono"], (FontFamily)res["Mono"]),
            _ => ((FontFamily)res["Serif"], (FontFamily)res["SerifBold"]),
        };

        ApplyTheme();
        // Lists rebuild their rows, which read the face as they're drawn.
        if (notify && readingChanged) AppServices.NotifyChanged();
    }

    // --- Brushes ------------------------------------------------------------------

    /// Theme.xaml, where the role brushes live.
    private static ResourceDictionary Palette =>
        Application.Current.Resources.MergedDictionaries.FirstOrDefault(d =>
            d.Source?.OriginalString.EndsWith("Theme.xaml", StringComparison.OrdinalIgnoreCase) == true)
        ?? Application.Current.Resources;

    /// Sets a brush's colour wherever Theme.xaml defines it (both themes), so
    /// everything already drawn with it changes too.
    private static void Recolor(string key, Color color)
    {
        var palette = Palette;
        if (palette.TryGetValue(key, out var top) && top is SolidColorBrush brush) brush.Color = color;
        foreach (var theme in palette.ThemeDictionaries.Values)
            if (theme is ResourceDictionary d && d.TryGetValue(key, out var v) && v is SolidColorBrush b) b.Color = color;
    }

    /// A role brush for the theme `scope` is actually showing. Code that
    /// builds elements uses this rather than Application.Resources, which
    /// only knows the app's launch theme, not the one chosen in Settings.
    public static Brush Brush(FrameworkElement scope, string key)
    {
        var theme = scope.ActualTheme == ElementTheme.Dark ? "Dark" : "Light";
        if (Palette.ThemeDictionaries.TryGetValue(theme, out var t) && t is ResourceDictionary d
            && d.TryGetValue(key, out var themed) && themed is Brush brush) return brush;
        return (Brush)Application.Current.Resources[key];
    }

    public static Color Rgb(uint hex) => Color.FromArgb(0xFF, (byte)(hex >> 16), (byte)(hex >> 8), (byte)hex);
}
