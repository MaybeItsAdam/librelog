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

/// A quiet action row: "New entry" on an empty today.
public sealed record JournalAction(JournalActionKind Kind, string Label, string Glyph);

public enum JournalActionKind { NewEntry }

public sealed partial class JournalTemplateSelector : DataTemplateSelector
{
    public DataTemplate? DayTemplate { get; set; }
    public DataTemplate? EntryTemplate { get; set; }
    public DataTemplate? ActionTemplate { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item) => item switch
    {
        JournalDay => DayTemplate,
        JournalAction => ActionTemplate,
        _ => EntryTemplate,
    };

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container) => SelectTemplateCore(item);
}

public sealed partial class JournalPage : Page
{
    private const uint PageSize = 50;
    private const string GlyphAdd = "";

    private readonly ObservableCollection<object> items = [];
    private string? lastDay;          // the day of the last item added
    private TimelineCursor? after;    // where the next page starts
    private bool done;
    private bool loading;
    private int generation;

    public JournalPage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        List.ItemsSource = items;
        Loaded += (_, _) =>
        {
            AppServices.DataChanged += Reload;
            if (List.FindScrollViewer() is { } sv) sv.ViewChanged += OnScrolled;
        };
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
    }

    /// Creates an entry for `day` at the current time and opens it.
    public static async Task NewEntry(string day)
    {
        var time = DateTime.Now.ToString("HH:mm");
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
        var todays = await AppServices.Run(s => s.Day(today));
        if (mine != generation) return; // reloaded meanwhile

        items.Clear();
        after = null;
        items.Add(Heading(today));
        if (todays.Count == 0)
            items.Add(new JournalAction(JournalActionKind.NewEntry, "New entry", GlyphAdd));
        foreach (var e in todays) items.Add(e);
        lastDay = today;
        loading = false;
        await LoadMore();
    }

    private async Task LoadMore()
    {
        if (done || loading) return;
        loading = true;
        var mine = generation;
        var cursor = after;
        var page = await AppServices.Run(s => s.Timeline(new TimelineFilter(), cursor, PageSize));
        if (mine != generation) return;

        var today = AppServices.Today;
        foreach (var e in page)
        {
            if (e.Date == today) continue; // already shown at the top
            if (e.Date != lastDay)
            {
                items.Add(Heading(e.Date));
                lastDay = e.Date;
            }
            items.Add(e);
        }
        if (page.Count > 0) after = new TimelineCursor(page[^1].Date, page[^1].Time, page[^1].Id);
        done = page.Count < PageSize;
        loading = false;
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

    private void OnNew(object sender, RoutedEventArgs e) => _ = NewEntry(AppServices.Today);

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        switch (e.ClickedItem)
        {
            case EntrySummary entry:
                Shell.Window.OpenEntry(entry.Id);
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
