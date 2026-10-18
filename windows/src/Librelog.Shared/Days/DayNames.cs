using System.Globalization;

namespace Librelog.Days;

/// How a day is named at the head of its group in the journal: the name
/// large ("Today", "Yesterday", "Tuesday") with the full date beneath.
public static class DayNames
{
    public static string Name(DateOnly day, DateOnly today, CultureInfo culture) =>
        (day.DayNumber - today.DayNumber) switch
        {
            0 => "Today",
            -1 => "Yesterday",
            _ => culture.DateTimeFormat.GetDayName(day.DayOfWeek),
        };

    /// The line under the name. When the name is already the weekday it
    /// isn't repeated.
    public static string Date(DateOnly day, DateOnly today, CultureInfo culture) =>
        day.DayNumber - today.DayNumber is 0 or -1
            ? day.ToString("dddd d MMMM yyyy", culture)
            : day.ToString("d MMMM yyyy", culture);

    /// "1 year ago", "5 years ago", for On This Day.
    public static string YearsAgo(DateOnly day, DateOnly today)
    {
        var years = today.Year - day.Year;
        return years == 1 ? "1 year ago" : $"{years} years ago";
    }
}
