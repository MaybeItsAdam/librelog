namespace Librelog.Days;

/// The calendar's month arithmetic (SPEC.md §6.3, docs/calendar.md).
public static class MonthMath
{
    public static DateOnly FirstOf(DateOnly d) => new(d.Year, d.Month, 1);

    /// Whole months from `from` to `to`; negative when `to` is earlier.
    public static int MonthsBetween(DateOnly from, DateOnly to) => (to.Year - from.Year) * 12 + to.Month - from.Month;

    /// Which way the grid slides when going from one month to another:
    /// +1 for later (the new month comes in from the right), -1 for earlier,
    /// 0 for the same month.
    public static int Direction(DateOnly from, DateOnly to) => Math.Sign(MonthsBetween(from, to));

    /// Empty cells before the 1st in a week starting on `firstDayOfWeek`.
    public static int Lead(DateOnly firstOfMonth, DayOfWeek firstDayOfWeek) =>
        ((int)firstOfMonth.DayOfWeek - (int)firstDayOfWeek + 7) % 7;

    /// Weeks the month's grid needs.
    public static int Rows(DateOnly firstOfMonth, DayOfWeek firstDayOfWeek) =>
        (Lead(firstOfMonth, firstDayOfWeek) + DateTime.DaysInMonth(firstOfMonth.Year, firstOfMonth.Month) + 6) / 7;

    /// The day to select after jumping to a month: the same day of the month,
    /// clamped to its length (31 January → 28 or 29 February).
    public static DateOnly SameDayIn(DateOnly day, DateOnly firstOfMonth) =>
        new(firstOfMonth.Year, firstOfMonth.Month, Math.Min(day.Day, DateTime.DaysInMonth(firstOfMonth.Year, firstOfMonth.Month)));
}

/// Turns horizontal scrolling (a trackpad's two-finger swipe, a tilting
/// wheel) into whole steps: one step per gesture, once the travel passes
/// Threshold. A pause longer than Quiet ends the gesture.
public sealed class SwipeSteps(double threshold = 120, double quietMs = 250)
{
    private double travel;
    private double lastMs = double.NegativeInfinity;
    private bool stepped;

    public double Threshold { get; } = threshold;

    /// Feeds one wheel delta (positive is scrolling right, towards later)
    /// at time `ms`. Returns +1 or -1 when the gesture should turn a page, else 0.
    public int Add(double delta, double ms)
    {
        if (ms - lastMs > quietMs)
        {
            travel = 0;
            stepped = false;
        }
        lastMs = ms;
        if (stepped) return 0;
        // A change of direction mid-gesture starts the count again.
        travel = Math.Sign(delta) == Math.Sign(travel) ? travel + delta : delta;
        if (Math.Abs(travel) < Threshold) return 0;
        stepped = true;
        return Math.Sign(travel);
    }

    /// A touch drag that ended `dx` pixels from where it began: past
    /// `distance`, dragging left goes to the later month.
    public static int FromDrag(double dx, double distance = 60) =>
        Math.Abs(dx) < distance ? 0 : -Math.Sign(dx);
}

/// The main sections in navigation order, for Ctrl+Tab.
public static class Sections
{
    public static readonly IReadOnlyList<string> Order = ["journal", "calendar", "search", "onthisday", "settings"];

    /// The section `delta` steps from `current`, wrapping round. A page that
    /// isn't a section (the editor) counts as the journal.
    public static string Step(string? current, int delta)
    {
        var at = current is null ? -1 : IndexOf(current);
        if (at < 0) at = 0;
        var n = Order.Count;
        return Order[((at + delta) % n + n) % n];
    }

    private static int IndexOf(string tag)
    {
        for (var i = 0; i < Order.Count; i++)
            if (Order[i] == tag) return i;
        return -1;
    }
}
