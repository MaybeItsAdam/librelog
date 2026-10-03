using System.Globalization;
using Librelog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;

namespace Librelog.App.Services;

/// Display helpers shared by the pages.
public static class Format
{
    private static readonly CultureInfo Culture = CultureInfo.CurrentCulture;

    public static DateOnly Date(string ymd) => DateOnly.ParseExact(ymd, "yyyy-MM-dd", CultureInfo.InvariantCulture);

    public static string Ymd(DateOnly d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    public static string LongDate(string ymd) => Date(ymd).ToString("dddd d MMMM yyyy", Culture);

    public static string ShortDate(string ymd) => Date(ymd).ToString("ddd d MMM yyyy", Culture);

    /// Micro-label casing, done on the text because TextBlock can't.
    public static string Caps(string s) => s.ToUpper(CultureInfo.CurrentCulture);

    public static string Weekday(string ymd) => Date(ymd).ToString("dddd", Culture);

    public static string Month(DateOnly firstOfMonth) => firstOfMonth.ToString("MMMM yyyy", Culture);

    /// "Today", "Yesterday", "Tomorrow", or the date.
    public static string DayTitle(string ymd)
    {
        var d = Date(ymd);
        var today = DateOnly.FromDateTime(DateTime.Now);
        return (d.DayNumber - today.DayNumber) switch
        {
            0 => "Today",
            -1 => "Yesterday",
            1 => "Tomorrow",
            _ => d.ToString("d MMMM", Culture),
        };
    }

    public static string Time(string? hhmm) =>
        hhmm is null ? "" : TimeOnly.ParseExact(hhmm, "HH:mm", CultureInfo.InvariantCulture).ToString("t", Culture);

    public static string Stars(double? rating)
    {
        if (rating is not { } r || r <= 0) return "";
        var whole = (int)Math.Floor(r);
        return new string('★', whole) + (r - whole >= 0.5 ? "½" : "");
    }

    public static string Words(uint n) => n == 1 ? "1 word" : $"{n:N0} words";

    public static Visibility Shown(bool show) => show ? Visibility.Visible : Visibility.Collapsed;

    /// Search snippets wrap matches in U+E000 … U+E001; turn them into a
    /// highlighted run without trusting anything else in the text.
    public static void Snippet(Microsoft.UI.Xaml.Controls.TextBlock target, string snippet)
    {
        var text = new System.Text.StringBuilder();
        var highlighter = new TextHighlighter
        {
            Background = (Brush)Application.Current.Resources["AmberTintBrush"],
        };
        var start = -1;
        foreach (var ch in snippet)
        {
            if (ch == '') start = text.Length;
            else if (ch == '' && start >= 0)
            {
                highlighter.Ranges.Add(new TextRange { StartIndex = start, Length = text.Length - start });
                start = -1;
            }
            else if (ch != '') text.Append(ch);
        }
        target.Text = text.ToString();
        target.TextHighlighters.Clear();
        target.TextHighlighters.Add(highlighter);
    }

    public static string Excerpt(EntrySummary e) => e.Excerpt.Length > 0 ? e.Excerpt : "Empty entry";
}
