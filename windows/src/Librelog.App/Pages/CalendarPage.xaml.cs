using System.Globalization;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Days;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using Windows.Foundation;

namespace Librelog.App.Pages;

public sealed partial class CalendarPage : Page
{
    /// How far a month slides in from, and how long it takes.
    private const double SlideFrom = 56;
    private static readonly TimeSpan SlideTime = TimeSpan.FromMilliseconds(240);

    private readonly SwipeSteps wheel = new();
    private DateOnly month = MonthMath.FirstOf(DateOnly.FromDateTime(DateTime.Now));
    private string selected = AppServices.Today;
    private int chooserYear;
    private int slideDirection; // set by a month change, used by the next draw
    private bool popSelected;   // a day was just picked

    public CalendarPage()
    {
        InitializeComponent();
        DayList.ItemContainerTransitions = Motion.ListTransitions();
        DayList.ContainerContentChanging += Motion.PressRows;
        Loaded += (_, _) => Motion.PressAll(this);
        NavigationCacheMode = NavigationCacheMode.Required;
        BuildHeader();
        BuildChooser();
        Loaded += (_, _) => AppServices.DataChanged += Reload;
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
        // Colours are picked in code, so a theme change redraws.
        ActualThemeChanged += (_, _) => Reload();
        MonthCard.SizeChanged += (_, e) =>
            MonthCard.Clip = new RectangleGeometry { Rect = new Rect(0, 0, e.NewSize.Width, e.NewSize.Height) };
        MonthCard.AddHandler(PointerWheelChangedEvent, new PointerEventHandler(OnWheel), handledEventsToo: true);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => Reload();

    private static DayOfWeek FirstDayOfWeek => CultureInfo.CurrentCulture.DateTimeFormat.FirstDayOfWeek;

    private Brush Themed(string key) => AppearanceService.Brush(this, key);

    private void BuildHeader()
    {
        var names = CultureInfo.CurrentCulture.DateTimeFormat.AbbreviatedDayNames;
        for (var i = 0; i < 7; i++)
        {
            WeekdayHeader.ColumnDefinitions.Add(new ColumnDefinition());
            var label = new TextBlock
            {
                Text = Format.Caps(names[(i + (int)FirstDayOfWeek) % 7]),
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
        var picked = popSelected;
        DrawGrid(shown, marks.ToDictionary(m => m.Date));
        SelectedText.Text = Format.Caps(Format.LongDate(day));
        if (picked) Motion.FadeIn(DayList);
        DayList.ItemsSource = entries.Select(e => new DayEntry(e, day)).ToList();
        NothingText.Visibility = Format.Shown(entries.Count == 0);
        Photos.Date = day;
        DayEvents.Date = day;
        LockedNote.Date = day;

        if (slideDirection != 0) SlideIn(slideDirection);
        slideDirection = 0;
    }

    private void DrawGrid(DateOnly first, Dictionary<string, DayMark> marks)
    {
        DayGrid.Children.Clear();
        DayGrid.RowDefinitions.Clear();
        var lead = MonthMath.Lead(first, FirstDayOfWeek);
        var days = DateTime.DaysInMonth(first.Year, first.Month);
        var rows = MonthMath.Rows(first, FirstDayOfWeek);
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

            // Days a multi-day entry covers sit on one accent-tinted band: square and
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
                    Background = Themed("AzureTintBrush"),
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
                Foreground = Themed(isToday ? "AccentBrush" : "InkBrush"),
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
                Background = isSelected ? Themed("WellBrush")
                    : banded ? new SolidColorBrush(Microsoft.UI.Colors.Transparent) : Themed("RaisedBrush"),
                BorderBrush = isSelected ? Themed("AccentBrush")
                    : banded ? new SolidColorBrush(Microsoft.UI.Colors.Transparent) : Themed("HairlineBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(6),
                Tag = ymd,
            };
            ToolTipService.SetToolTip(button, mark is null ? Format.LongDate(ymd)
                : $"{Format.LongDate(ymd)} · {mark.EntryCount} {(mark.EntryCount == 1 ? "entry" : "entries")}");
            button.Click += OnDayClick;
            Motion.Press(button);
            if (isSelected && popSelected)
            {
                // The ring grows into place once the button has a size to centre on.
                button.Loaded += (b, _) => Motion.Pop((UIElement)b, Motion.SelectScale);
                popSelected = false;
            }
            Grid.SetRow(button, row);
            Grid.SetColumn(button, column);
            DayGrid.Children.Add(button);
        }
    }

    /// Tinted by rating: raspberry below 2, amber below 3.5, emerald above.
    private Brush RatingBrush(DayMark? mark) => mark?.Rating switch
    {
        null => Themed("MutedBrush"),
        < 2 => Themed("RaspberryBrush"),
        < 3.5 => Themed("AmberBrush"),
        _ => Themed("EmeraldBrush"),
    };

    private void OnDayClick(object sender, RoutedEventArgs e)
    {
        var day = (string)((Button)sender).Tag;
        if (day == selected) return;
        selected = day;
        popSelected = true;
        Reload();
    }

    // --- Changing month ----------------------------------------------------------------

    /// Shows another month, sliding it in from the side it lies on, and keeps
    /// the same day of the month selected.
    private void GoTo(DateOnly first)
    {
        first = MonthMath.FirstOf(first);
        var direction = MonthMath.Direction(month, first);
        if (direction == 0) return;
        selected = Format.Ymd(MonthMath.SameDayIn(Format.Date(selected), first));
        month = first;
        slideDirection = direction;
        Reload();
    }

    private void Shift(int months) => GoTo(month.AddMonths(months));

    /// The new month comes in from the right when going forward, from the
    /// left going back. With animations off in Windows, it's just there.
    private void SlideIn(int direction)
    {
        var ms = new Windows.UI.ViewManagement.UISettings().AnimationsEnabled ? SlideTime : TimeSpan.Zero;
        var duration = new Duration(ms);
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var slide = new DoubleAnimation { From = direction * SlideFrom, To = 0, Duration = duration, EasingFunction = ease };
        var fade = new DoubleAnimation { From = 0, To = 1, Duration = duration, EasingFunction = ease };
        Storyboard.SetTarget(slide, Slide);
        Storyboard.SetTargetProperty(slide, "X");
        Storyboard.SetTarget(fade, DayGrid);
        Storyboard.SetTargetProperty(fade, "Opacity");
        var story = new Storyboard();
        story.Children.Add(slide);
        story.Children.Add(fade);
        story.Begin();
    }

    private void OnPrevious(object sender, RoutedEventArgs e) => Shift(-1);
    private void OnNext(object sender, RoutedEventArgs e) => Shift(1);

    /// A trackpad's two-finger swipe or a tilting wheel: one month per gesture.
    private void OnWheel(object sender, PointerRoutedEventArgs e)
    {
        var point = e.GetCurrentPoint(MonthCard);
        if (!point.Properties.IsHorizontalMouseWheel) return;
        var step = wheel.Add(point.Properties.MouseWheelDelta, point.Timestamp / 1000.0);
        if (step != 0) Shift(step);
        e.Handled = true;
    }

    /// A touch swipe across the month: left for the next, right for the last.
    private void OnSwiped(object sender, ManipulationCompletedRoutedEventArgs e)
    {
        var step = SwipeSteps.FromDrag(e.Cumulative.Translation.X);
        if (step != 0) Shift(step);
    }

    // --- Month and year chooser ------------------------------------------------------------

    private void BuildChooser()
    {
        var names = CultureInfo.CurrentCulture.DateTimeFormat.AbbreviatedMonthNames;
        for (var i = 0; i < 4; i++) MonthChoices.ColumnDefinitions.Add(new ColumnDefinition());
        for (var i = 0; i < 3; i++) MonthChoices.RowDefinitions.Add(new RowDefinition());
        for (var m = 1; m <= 12; m++)
        {
            var button = new Button
            {
                Content = names[m - 1],
                Tag = m,
                Style = (Style)Application.Current.Resources["PlainButton"],
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            button.Click += OnMonthChosen;
            Grid.SetRow(button, (m - 1) / 4);
            Grid.SetColumn(button, (m - 1) % 4);
            MonthChoices.Children.Add(button);
        }
    }

    private void OnChooserOpening(object? sender, object e)
    {
        chooserYear = month.Year;
        UpdateChooser();
    }

    /// The year in view, with the month showing marked by the accent.
    private void UpdateChooser()
    {
        ChooserYearText.Text = chooserYear.ToString(CultureInfo.CurrentCulture);
        foreach (var button in MonthChoices.Children.OfType<Button>())
        {
            var current = chooserYear == month.Year && (int)button.Tag == month.Month;
            button.BorderBrush = Themed(current ? "AccentBrush" : "HairlineBrush");
            button.Background = Themed(current ? "WellBrush" : "RaisedBrush");
        }
    }

    private void OnPreviousYear(object sender, RoutedEventArgs e)
    {
        chooserYear--;
        UpdateChooser();
    }

    private void OnNextYear(object sender, RoutedEventArgs e)
    {
        chooserYear++;
        UpdateChooser();
    }

    private void OnMonthChosen(object sender, RoutedEventArgs e)
    {
        MonthFlyout.Hide();
        GoTo(new DateOnly(chooserYear, (int)((Button)sender).Tag, 1));
    }

    private void OnThisMonth(object sender, RoutedEventArgs e)
    {
        MonthFlyout.Hide();
        GoTo(DateOnly.FromDateTime(DateTime.Now));
    }

    private void OnNew(object sender, RoutedEventArgs e) => _ = JournalPage.NewEntry(selected);

    private void OnEntryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is DayEntry item) Shell.Window.OpenEntry(item.Entry.Id);
    }
}

/// An entry in the selected day's list, with the day so a multi-day entry
/// can say which of its days this is.
public sealed record DayEntry(EntrySummary Entry, string Day);
