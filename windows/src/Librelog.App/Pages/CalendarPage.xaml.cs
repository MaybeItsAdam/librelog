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
    private int slide;          // -1 or 1 after a month change, for the next draw
    private bool popSelected;   // a day was just picked

    public CalendarPage()
    {
        InitializeComponent();
        DayList.ItemContainerTransitions = Motion.ListTransitions();
        DayList.ContainerContentChanging += Motion.PressRows;
        Loaded += (_, _) => Motion.PressAll(this);
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
        var picked = popSelected;
        DrawGrid(shown, marks.ToDictionary(m => m.Date));
        if (slide != 0)
        {
            // The new month comes from the side you moved towards.
            Motion.SlideIn(DayGrid, slide * Motion.SlideDistance);
            Motion.SlideIn(MonthText, slide * Motion.SlideDistance / 2);
            slide = 0;
        }
        SelectedText.Text = Format.Caps(Format.LongDate(day));
        if (picked) Motion.FadeIn(DayList);
        DayList.ItemsSource = entries;
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
                Background = (Brush)res[isSelected ? "WellBrush" : "RaisedBrush"],
                BorderBrush = (Brush)res[isSelected ? "AzureBrush" : "HairlineBrush"],
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
            Grid.SetRow(button, cell / 7);
            Grid.SetColumn(button, cell % 7);
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
        var day = (string)((Button)sender).Tag;
        if (day == selected) return;
        selected = day;
        popSelected = true;
        Reload();
    }

    private void Shift(int months)
    {
        month = month.AddMonths(months);
        slide = Math.Sign(months);
        Reload();
    }

    private void OnPrevious(object sender, RoutedEventArgs e) => Shift(-1);
    private void OnNext(object sender, RoutedEventArgs e) => Shift(1);

    private void OnNew(object sender, RoutedEventArgs e) => _ = JournalPage.NewEntry(selected);

    private void OnEntryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is EntrySummary entry) Shell.Window.OpenEntry(entry.Id);
    }
}
