using System.Globalization;
using Librelog.App.Services;
using Librelog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

namespace Librelog.App.Pages;

public sealed partial class CalendarPage : Page
{
    private DateOnly month = FirstOf(DateOnly.FromDateTime(DateTime.Now));
    private string selected = AppServices.Today;

    public CalendarPage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        BuildHeader();
        Loaded += (_, _) => AppServices.DataChanged += Reload;
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => Reload();

    private static DateOnly FirstOf(DateOnly d) => new(d.Year, d.Month, 1);

    private static int FirstDayOfWeek => (int)CultureInfo.CurrentCulture.DateTimeFormat.FirstDayOfWeek;

    private void BuildHeader()
    {
        var names = CultureInfo.CurrentCulture.DateTimeFormat.AbbreviatedDayNames;
        for (var i = 0; i < 7; i++)
        {
            WeekdayHeader.ColumnDefinitions.Add(new ColumnDefinition());
            var label = new TextBlock
            {
                Text = Format.Caps(names[(i + FirstDayOfWeek) % 7]),
                Style = (Style)Application.Current.Resources["MicroLabel"],
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 4, 0, 8),
            };
            Grid.SetColumn(label, i);
            WeekdayHeader.Children.Add(label);
        }
        for (var i = 0; i < 7; i++) DayGrid.ColumnDefinitions.Add(new ColumnDefinition());
    }

    private void Reload() => _ = Load();

    private async Task Load()
    {
        var shown = month;
        var ym = shown.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        var day = selected;
        var (marks, entries) = await AppServices.Run(s => (s.Month(ym), s.Day(day)));
        if (shown != month) return;
        MonthText.Text = Format.Month(shown);
        DrawGrid(shown, marks.ToDictionary(m => m.Date));
        SelectedText.Text = Format.Caps(Format.LongDate(day));
        DayList.ItemsSource = entries.Select(e => new DayEntry(e, day)).ToList();
        NothingText.Visibility = Format.Shown(entries.Count == 0);
    }

    private void DrawGrid(DateOnly first, Dictionary<string, DayMark> marks)
    {
        DayGrid.Children.Clear();
        DayGrid.RowDefinitions.Clear();
        var lead = ((int)first.DayOfWeek - FirstDayOfWeek + 7) % 7;
        var days = DateTime.DaysInMonth(first.Year, first.Month);
        var rows = (lead + days + 6) / 7;
        for (var r = 0; r < rows; r++) DayGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(52) });

        var res = Application.Current.Resources;
        for (var d = 1; d <= days; d++)
        {
            var ymd = Format.Ymd(first.AddDays(d - 1));
            var cell = lead + d - 1;
            marks.TryGetValue(ymd, out var mark);
            var isSelected = ymd == selected;
            var isToday = ymd == AppServices.Today;
            var row = cell / 7;
            var column = cell % 7;

            // Days a multi-day entry covers sit on one azure band: square and
            // bridging the gap where it carries on into the next cell, rounded
            // where it starts, ends, or meets the edge of the week or month.
            var banded = mark is { SpanBefore: true } or { SpanAfter: true };
            if (banded)
            {
                var joinLeft = mark!.SpanBefore && column > 0 && d > 1;
                var joinRight = mark.SpanAfter && column < 6 && d < days;
                const double gap = 2; // half the grid's column spacing
                var band = new Border
                {
                    Background = (Brush)res["AzureTintBrush"],
                    Margin = new Thickness(joinLeft ? -gap : 0, 0, joinRight ? -gap : 0, 0),
                    CornerRadius = new CornerRadius(joinLeft ? 0 : 6, joinRight ? 0 : 6, joinRight ? 0 : 6, joinLeft ? 0 : 6),
                    IsHitTestVisible = false,
                };
                Grid.SetRow(band, row);
                Grid.SetColumn(band, column);
                DayGrid.Children.Add(band);
            }

            var number = new TextBlock
            {
                Text = d.ToString(CultureInfo.CurrentCulture),
                FontFamily = (FontFamily)res["Mono"],
                FontSize = 13,
                HorizontalAlignment = HorizontalAlignment.Center,
                Foreground = (Brush)res[isToday ? "AzureBrush" : "InkBrush"],
            };
            var dot = new Microsoft.UI.Xaml.Shapes.Ellipse
            {
                Width = 6,
                Height = 6,
                Fill = RatingBrush(mark),
                HorizontalAlignment = HorizontalAlignment.Center,
                Visibility = Format.Shown(mark is not null),
            };
            var button = new Button
            {
                Content = new StackPanel { Spacing = 4, Children = { number, dot } },
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch,
                // On a band the cell lets the band show through, so the days read as one.
                Background = isSelected ? (Brush)res["WellBrush"]
                    : banded ? new SolidColorBrush(Microsoft.UI.Colors.Transparent) : (Brush)res["RaisedBrush"],
                BorderBrush = isSelected ? (Brush)res["AzureBrush"]
                    : banded ? new SolidColorBrush(Microsoft.UI.Colors.Transparent) : (Brush)res["HairlineBrush"],
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(6),
                Tag = ymd,
            };
            ToolTipService.SetToolTip(button, mark is null ? Format.LongDate(ymd)
                : $"{Format.LongDate(ymd)} · {mark.EntryCount} {(mark.EntryCount == 1 ? "entry" : "entries")}");
            button.Click += OnDayClick;
            Grid.SetRow(button, row);
            Grid.SetColumn(button, column);
            DayGrid.Children.Add(button);
        }
    }

    /// Tinted by rating: raspberry below 2, amber below 3.5, emerald above.
    private static Brush RatingBrush(DayMark? mark)
    {
        var res = Application.Current.Resources;
        return mark?.Rating switch
        {
            null => (Brush)res["MutedBrush"],
            < 2 => (Brush)res["RaspberryBrush"],
            < 3.5 => (Brush)res["AmberBrush"],
            _ => (Brush)res["EmeraldBrush"],
        };
    }

    private void OnDayClick(object sender, RoutedEventArgs e)
    {
        selected = (string)((Button)sender).Tag;
        Reload();
    }

    private void Shift(int months)
    {
        month = month.AddMonths(months);
        Reload();
    }

    private void OnPrevious(object sender, RoutedEventArgs e) => Shift(-1);
    private void OnNext(object sender, RoutedEventArgs e) => Shift(1);

    private void OnNew(object sender, RoutedEventArgs e) => _ = JournalPage.NewEntry(selected);

    private void OnEntryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is DayEntry item) Shell.Window.OpenEntry(item.Entry.Id);
    }
}

/// An entry in the selected day's list, with the day so a multi-day entry
/// can say which of its days this is.
public sealed record DayEntry(EntrySummary Entry, string Day);
