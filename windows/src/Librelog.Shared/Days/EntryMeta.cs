using System.Globalization;

namespace Librelog.Days;

/// The editor's quiet metadata line under the title, as a micro-label:
/// "WED 14 OCT · 09:30 · 3 DAYS". The year shows only when it isn't this
/// year's; the journal only when there's more than one to be in.
public static class EntryMeta
{
    public static string Line(string date, string? time, string? endDate, DateOnly today, CultureInfo culture, string? journal = null)
    {
        var start = Day(date);
        var parts = new List<string>
        {
            start.ToString(start.Year == today.Year ? "ddd d MMM" : "ddd d MMM yyyy", culture),
        };
        if (time is { Length: > 0 }) parts.Add(time);
        if (endDate is { } end && Day(end) > start) parts.Add($"{Day(end).DayNumber - start.DayNumber + 1} days");
        if (journal is { Length: > 0 }) parts.Add(journal);
        return string.Join(" · ", parts).ToUpper(culture);
    }

    /// The time a new entry on `day` starts with: now for today. A missed
    /// day written up later has none, since the moment has gone.
    public static string? DefaultTime(string day, string today, DateTime now) =>
        day == today ? now.ToString("HH:mm", CultureInfo.InvariantCulture) : null;

    private static DateOnly Day(string ymd) => DateOnly.ParseExact(ymd, "yyyy-MM-dd", CultureInfo.InvariantCulture);
}
