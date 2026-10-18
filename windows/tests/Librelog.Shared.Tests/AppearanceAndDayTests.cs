using System.Globalization;
using Librelog.Core;
using Librelog.Days;
using Librelog.Editor;
using Librelog.Settings;

namespace Librelog.Tests;

/// Settings → Appearance, discarding empty entries, and the journal's and
/// calendar's day arithmetic.
public sealed class AppearanceAndDayTests : IDisposable
{
    private static readonly CultureInfo En = CultureInfo.GetCultureInfo("en-GB");
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-settings").FullName;
    private readonly CoreStore store;

    public AppearanceAndDayTests() => store = CoreStore.Open(Path.Combine(dir, "journal.sqlite"));

    public void Dispose()
    {
        store.Dispose();
        Directory.Delete(dir, recursive: true);
    }

    [Fact]
    public void SettingsRoundTripThroughTheCore()
    {
        Assert.Null(store.Setting(AppearanceSettings.ThemeKey));
        Assert.Equal(AppearanceSettings.Default, AppearanceSettings.Read(store.Setting));

        store.SetSetting(AppearanceSettings.ThemeKey, AppearanceSettings.Wire(ThemeChoice.Dark));
        store.SetSetting(AppearanceSettings.AccentKey, "amber");
        store.SetSetting(AppearanceSettings.FontKey, "mono");
        store.SetSetting(AppearanceSettings.TextSizeKey, "larger");
        Assert.Equal("dark", store.Setting(AppearanceSettings.ThemeKey));
        var a = AppearanceSettings.Read(store.Setting);
        Assert.Equal(new AppearanceSettings(ThemeChoice.Dark, AccentChoice.Amber, FontChoice.Mono, TextSizeChoice.Larger), a);
        Assert.Equal(1.3, a.Scale);
        Assert.Equal(0xFFBF00u, a.AccentColor);
        Assert.True(AppearanceSettings.InkOnAccent(a.Accent));
        Assert.Equal("Geist Mono", a.ReadingFamily);

        store.SetSetting(AppearanceSettings.ThemeKey, null);
        Assert.Null(store.Setting(AppearanceSettings.ThemeKey));
        Assert.Equal(ThemeChoice.System, AppearanceSettings.Read(store.Setting).Theme);
    }

    [Fact]
    public void UnknownValuesFallBackToTheDefault()
    {
        Assert.Equal(AccentChoice.Azure, AppearanceSettings.Parse("teal", AccentChoice.Azure));
        Assert.Equal(ThemeChoice.System, AppearanceSettings.Parse("Dark", ThemeChoice.System)); // wire values are lower case
        Assert.Equal(TextSizeChoice.Small, AppearanceSettings.Parse("small", TextSizeChoice.Default));
        Assert.Equal("text_size", AppearanceSettings.TextSizeKey.Split('.')[1]);
        Assert.Equal(0.9, (AppearanceSettings.Default with { TextSize = TextSizeChoice.Small }).Scale);
        Assert.Equal(1.15, (AppearanceSettings.Default with { TextSize = TextSizeChoice.Large }).Scale);
        Assert.Equal("Arvo", AppearanceSettings.Default.ReadingFamily);
        Assert.Equal(0x007FFFu, AppearanceSettings.Default.AccentColor);
        Assert.False(AppearanceSettings.InkOnAccent(AccentChoice.Purple));
    }

    [Fact]
    public void EditorTakesTheReadingFaceButCodeStaysMono()
    {
        var body = Appearance.Text(new CharStyle("p"), "Cambria", 1.15);
        Assert.Equal("Cambria", body.Font);
        Assert.Equal(Appearance.BodySize * 1.15, body.Size, 3);
        Assert.Equal(24 * 1.15, Appearance.Text(new CharStyle("h1"), "Cambria", 1.15).Size, 3);
        var code = Appearance.Text(new CharStyle("p", Flags: Inline.Code), "Cambria", 1.15);
        Assert.Equal(Appearance.Mono, code.Font);
    }

    [Fact]
    public void EmptyEntriesAreDiscarded()
    {
        var blank = store.CreateEntry("2026-10-03", "08:00");
        Assert.True(store.DiscardIfEmpty(blank.Id));
        Assert.Empty(store.Day("2026-10-03"));
        Assert.False(store.DiscardIfEmpty(blank.Id)); // already gone

        var titled = store.CreateEntry("2026-10-03", "09:00");
        store.SetTitle(titled.Id, "Just a title");
        Assert.False(store.DiscardIfEmpty(titled.Id));
        var written = store.CreateEntry("2026-10-03", "10:00");
        store.SaveBody(written.Id, [new Block(BlockKind.Paragraph, "Words")]);
        Assert.False(store.DiscardIfEmpty(written.Id));
        Assert.Equal(2, store.Day("2026-10-03").Count);
    }

    [Fact]
    public void DayNames()
    {
        var today = new DateOnly(2026, 10, 18); // a Sunday
        Assert.Equal("Today", Days.DayNames.Name(today, today, En));
        Assert.Equal("Sunday 18 October 2026", Days.DayNames.Date(today, today, En));
        Assert.Equal("Yesterday", Days.DayNames.Name(today.AddDays(-1), today, En));
        Assert.Equal("Saturday 17 October 2026", Days.DayNames.Date(today.AddDays(-1), today, En));
        Assert.Equal("Wednesday", Days.DayNames.Name(new DateOnly(2026, 10, 14), today, En));
        Assert.Equal("14 October 2026", Days.DayNames.Date(new DateOnly(2026, 10, 14), today, En));
        Assert.Equal("Monday", Days.DayNames.Name(today.AddDays(1), today, En)); // a future-dated entry
        Assert.Equal("1 year ago", Days.DayNames.YearsAgo(new DateOnly(2025, 10, 18), today));
        Assert.Equal("4 years ago", Days.DayNames.YearsAgo(new DateOnly(2022, 10, 18), today));
    }

    [Fact]
    public void MonthNavigation()
    {
        var oct = new DateOnly(2026, 10, 1);
        Assert.Equal(oct, MonthMath.FirstOf(new DateOnly(2026, 10, 18)));
        Assert.Equal(15, MonthMath.MonthsBetween(new DateOnly(2025, 7, 1), oct));
        Assert.Equal(-1, MonthMath.Direction(oct, new DateOnly(2026, 9, 1)));
        Assert.Equal(1, MonthMath.Direction(oct, new DateOnly(2027, 1, 1)));
        Assert.Equal(0, MonthMath.Direction(oct, oct));

        // October 2026 starts on a Thursday.
        Assert.Equal(3, MonthMath.Lead(oct, DayOfWeek.Monday));
        Assert.Equal(4, MonthMath.Lead(oct, DayOfWeek.Sunday));
        Assert.Equal(5, MonthMath.Rows(oct, DayOfWeek.Monday));
        Assert.Equal(4, MonthMath.Rows(new DateOnly(2027, 2, 1), DayOfWeek.Monday)); // February 2027 starts on a Monday
        Assert.Equal(6, MonthMath.Rows(new DateOnly(2026, 8, 1), DayOfWeek.Monday));

        Assert.Equal(new DateOnly(2027, 2, 28), MonthMath.SameDayIn(new DateOnly(2027, 1, 31), new DateOnly(2027, 2, 1)));
        Assert.Equal(new DateOnly(2028, 2, 29), MonthMath.SameDayIn(new DateOnly(2027, 1, 31), new DateOnly(2028, 2, 1)));
    }

    [Fact]
    public void SwipesTurnOnePagePerGesture()
    {
        var s = new SwipeSteps(threshold: 100, quietMs: 200);
        Assert.Equal(0, s.Add(60, 0));
        Assert.Equal(1, s.Add(60, 16));
        Assert.Equal(0, s.Add(300, 32));   // still the same gesture
        Assert.Equal(0, s.Add(-60, 300));  // a pause: a new gesture
        Assert.Equal(-1, s.Add(-60, 316));
        Assert.Equal(0, s.Add(-60, 1000));
        Assert.Equal(0, s.Add(80, 1016));  // turned round: counting again from 80
        Assert.Equal(1, s.Add(40, 1032));

        Assert.Equal(1, SwipeSteps.FromDrag(-90));
        Assert.Equal(-1, SwipeSteps.FromDrag(90));
        Assert.Equal(0, SwipeSteps.FromDrag(30));
    }

    [Fact]
    public void CtrlTabCyclesSections()
    {
        Assert.Equal("calendar", Sections.Step("journal", 1));
        Assert.Equal("settings", Sections.Step("journal", -1));
        Assert.Equal("journal", Sections.Step("settings", 1));
        Assert.Equal("calendar", Sections.Step(null, 1));
        Assert.Equal("calendar", Sections.Step("entry", 1));
    }
}
