using System.Collections.ObjectModel;
using System.Globalization;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Days;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Librelog.App.Pages;

/// A day heading: its name ("Today", "Tuesday") and the date beneath.
/// Today's carries the New entry button.
public sealed record JournalDay(string Name, string Date, bool IsToday = false)
{
    public Visibility NewVisibility => Format.Shown(IsToday);
}

/// An entry in the journal, with its day's preview photo when it's the
/// day's first row (Thumb) and the gutter when the day has one at all.
public sealed record JournalEntry(EntrySummary Entry, DayPhoto? Thumb, bool Gutter)
{
    public double GutterWidth => Gutter ? DayThumb.Side : 0;
    public double GutterSpacing => Gutter ? 12 : 0;
}

/// Today's rule under its heading, carrying the add-photo icon.
public sealed record JournalRule;

/// A quiet action row: "New entry" on an empty today.
public sealed record JournalAction(JournalActionKind Kind, string Label, string Glyph);

public enum JournalActionKind { NewEntry }

public sealed partial class JournalTemplateSelector : DataTemplateSelector
{
    public DataTemplate? DayTemplate { get; set; }
    public DataTemplate? EntryTemplate { get; set; }
    public DataTemplate? ActionTemplate { get; set; }
    public DataTemplate? RuleTemplate { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item) => item switch
    {
        JournalDay => DayTemplate,
        JournalAction => ActionTemplate,
        JournalRule => RuleTemplate,
        _ => EntryTemplate,
    };

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container) => SelectTemplateCore(item);
}

public sealed partial class JournalPage : Page
{
    private const uint PageSize = 50;
    private const string GlyphAdd = "";

    private readonly ObservableCollection<object> items = [];
    // Today's photos sit under its entries, as in the editor, but without a
    // worded Add photo: the icon in today's rule adds them. One instance,
    // its own list item, kept across reloads.
    private readonly DayPhotosView todayPhotos = new() { ShowAdd = false };
    private readonly ListViewItem todayPhotosItem;
    private bool entering = true;     // the staggered entrance plays on first load only
    private string? lastDay;          // the day of the last item added
    private TimelineCursor? after;    // where the next page starts
    private bool done;
    private bool loading;
    private int generation;

    public JournalPage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        todayPhotosItem = new ListViewItem
        {
            Content = todayPhotos,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(0, 8, 0, 8),
        };
        List.ItemsSource = items;
        Loaded += (_, _) =>
        {
            AppServices.DataChanged += Reload;
            if (List.FindScrollViewer() is { } sv) sv.ViewChanged += OnScrolled;
        };
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
    }

    /// Creates an entry for `day` and opens it: at the current time for
    /// today, with no time for any other day (EntryMeta.DefaultTime).
    public static Task NewEntry(string day) =>
        NewEntry(day, EntryMeta.DefaultTime(day, AppServices.Today, DateTime.Now));

    /// Creates an entry for `day` at `time`, or with no time when null, and opens it.
    public static async Task NewEntry(string day, string? time)
    {
        var entry = await AppServices.Write(s => s.CreateEntry(day, time));
        Shell.Window.OpenEntry(entry.Id);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => Reload();

    private void Reload() => _ = LoadFirst();

    /// Today's group first (always shown), then every other day a page at a time.
    private async Task LoadFirst()
    {
        var mine = ++generation;
        done = false;
        loading = true;
        var today = AppServices.Today;
        var (todays, thumbs) = await AppServices.Run(s => (s.Day(today), Thumbs(s, [today])));
        if (mine != generation) return; // reloaded meanwhile

        // Rebuilt in place after an edit or a sync: no second fly-in.
        var settle = entering ? null : Motion.Quiet(List);
        items.Clear();
        after = null;
        items.Add(Heading(today));
        items.Add(new JournalRule());
        if (todays.Count == 0)
            items.Add(new JournalAction(JournalActionKind.NewEntry, "New entry", GlyphAdd));
        for (var i = 0; i < todays.Count; i++) items.Add(Row(todays[i], thumbs, first: i == 0));
        todayPhotos.Date = today;
        items.Add(todayPhotosItem);
        lastDay = today;
        loading = false;
        settle?.Invoke();
        await LoadMore();
    }

    private async Task LoadMore()
    {
        if (done || loading) return;
        loading = true;
        var mine = generation;
        var cursor = after;
        var (page, thumbs) = await AppServices.Run(s =>
        {
            var rows = s.Timeline(new TimelineFilter(), cursor, PageSize);
            return (rows, Thumbs(s, rows.Select(e => e.Date)));
        });
        if (mine != generation) return;

        var today = AppServices.Today;
        // The first page joins the entrance; later ones just appear as you scroll.
        var settle = entering ? null : Motion.Quiet(List);
        entering = false;
        foreach (var e in page)
        {
            if (e.Date == today) continue; // already shown at the top
            var first = e.Date != lastDay;
            if (first)
            {
                items.Add(Heading(e.Date));
                lastDay = e.Date;
            }
            items.Add(Row(e, thumbs, first));
        }
        if (page.Count > 0) after = new TimelineCursor(page[^1].Date, page[^1].Time, page[^1].Id);
        settle?.Invoke();
        done = page.Count < PageSize;
        loading = false;
    }

    /// The photo standing for each of `dates` that has one, in one call.
    private static Dictionary<string, DayPhoto> Thumbs(CoreStore s, IEnumerable<string> dates) =>
        s.DayPreviews(dates).ToDictionary(p => p.Date);

    private static JournalEntry Row(EntrySummary e, Dictionary<string, DayPhoto> thumbs, bool first)
    {
        var thumb = thumbs.GetValueOrDefault(e.Date);
        return new JournalEntry(e, first ? thumb : null, thumb is not null);
    }

    /// "Today" over SUNDAY 18 OCTOBER 2026; "Tuesday" over 14 OCTOBER 2026.
    private static JournalDay Heading(string ymd)
    {
        var day = Format.Date(ymd);
        var today = DateOnly.FromDateTime(DateTime.Now);
        var culture = CultureInfo.CurrentCulture;
        return new JournalDay(DayNames.Name(day, today, culture), Format.Caps(DayNames.Date(day, today, culture)), day == today);
    }

    private void OnScrolled(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (sender is ScrollViewer sv && sv.VerticalOffset > sv.ScrollableHeight - 800) _ = LoadMore();
    }

    /// Rows press in a touch.
    private void OnContainerChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.InRecycleQueue) return;
        if (args.Item is not (JournalDay or JournalRule or ListViewItem)) Motion.Press(args.ItemContainer);
    }

    private void OnNew(object sender, RoutedEventArgs e) => _ = NewEntry(AppServices.Today);

    private void OnAddPhoto(object sender, RoutedEventArgs e) => _ = todayPhotos.Add();

    /// New entry for another day: a missed one written up later, say. Past
    /// days start with no time; the box adds one.
    private void OnNewForDay(object sender, RoutedEventArgs e)
    {
        var picker = new CalendarDatePicker
        {
            Date = DateTimeOffset.Now.Date,
            MaxDate = DateTimeOffset.Now.Date,
            CornerRadius = new CornerRadius(6),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        var timed = new CheckBox { Content = "Time", MinWidth = 0 };
        var time = new TimePicker
        {
            MinuteIncrement = 1,
            CornerRadius = new CornerRadius(6),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            SelectedTime = new TimeSpan(DateTime.Now.Hour, DateTime.Now.Minute, 0),
            Visibility = Visibility.Collapsed,
        };
        timed.Click += (_, _) => time.Visibility = Format.Shown(timed.IsChecked == true);
        var create = new Button { Style = (Style)Application.Current.Resources["PrimaryButton"], Content = "New entry", HorizontalAlignment = HorizontalAlignment.Stretch };

        var panel = new StackPanel { Spacing = 10, Width = 280 };
        panel.Children.Add(new TextBlock { Text = "NEW ENTRY FOR", Style = (Style)Application.Current.Resources["MicroLabel"] });
        panel.Children.Add(picker);
        panel.Children.Add(timed);
        panel.Children.Add(time);
        panel.Children.Add(create);
        var flyout = new Flyout { Content = panel, Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedRight };
        create.Click += (_, _) =>
        {
            if (picker.Date is not { } d) return;
            flyout.Hide();
            var at = timed.IsChecked == true && time.SelectedTime is { } t
                ? TimeOnly.FromTimeSpan(t).ToString("HH:mm", CultureInfo.InvariantCulture)
                : null;
            _ = NewEntry(Format.Ymd(DateOnly.FromDateTime(d.Date)), at);
        };
        flyout.ShowAt((FrameworkElement)sender);
    }

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        switch (e.ClickedItem)
        {
            case JournalEntry row:
                Shell.Window.OpenEntry(row.Entry.Id);
                break;
            case JournalAction { Kind: JournalActionKind.NewEntry }:
                _ = NewEntry(AppServices.Today);
                break;
        }
    }
}

internal static class VisualTreeExtensions
{
    public static ScrollViewer? FindScrollViewer(this DependencyObject root)
    {
        if (root is ScrollViewer sv) return sv;
        for (var i = 0; i < Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(root); i++)
            if (Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(root, i).FindScrollViewer() is { } found) return found;
        return null;
    }
}
