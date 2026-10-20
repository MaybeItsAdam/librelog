using System.Globalization;
using Librelog.Core;

namespace Librelog.Days;

/// The "Add event" dialog's rules (docs/calendar.md, "Events you add
/// yourself"): a title, all day or a start and an end after it, and an
/// optional place. Checked here so the dialog can say what's wrong inline;
/// the core checks the same again.
public static class OwnEventForm
{
    /// The event to save, or the line to show under the fields.
    public static (OwnEvent? Event, string? Error) Validate(
        string? title, bool allDay, TimeSpan? start, TimeSpan? end, string? place)
    {
        var name = title?.Trim() ?? "";
        if (name.Length == 0) return (null, "Give the event a title.");
        var where = place?.Trim() is { Length: > 0 } p ? p : null;
        if (allDay) return (new OwnEvent(name, null, null, where), null);
        if (start is not { } s) return (null, "Choose a start time, or make it all day.");
        if (end is { } e && e <= s) return (null, "The end has to be after the start.");
        return (new OwnEvent(name, Hhmm(s), end is { } e2 ? Hhmm(e2) : null, where), null);
    }

    /// What the dialog opens with for an event already on the day.
    public static (bool AllDay, TimeSpan? Start, TimeSpan? End) TimesOf(KeptEvent ev) =>
        ev.AllDay ? (true, null, null) : (false, TimeOf(ev.Start), ev.End is { } end ? TimeOf(end) : null);

    /// "2026-10-14T09:30" → 09:30; null for a day without a time.
    public static TimeSpan? TimeOf(string moment)
    {
        var t = moment.IndexOf('T');
        return t >= 0 && TimeOnly.TryParseExact(moment[(t + 1)..], "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var time)
            ? time.ToTimeSpan()
            : null;
    }

    public static string Hhmm(TimeSpan t) => TimeOnly.FromTimeSpan(t).ToString("HH:mm", CultureInfo.InvariantCulture);
}
