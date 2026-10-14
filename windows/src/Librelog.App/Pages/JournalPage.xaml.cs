using System.Collections.ObjectModel;
using Librelog.App.Services;
using Librelog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Librelog.App.Pages;

/// A day heading in the journal list.
public sealed record JournalDay(string Label);

/// An entry from an earlier year, shown with its date under "On this day".
public sealed record JournalMemory(EntrySummary Entry);

/// A quiet action row: "New entry" on an empty today, or the "On this day"
/// toggle.
public sealed record JournalAction(JournalActionKind Kind, string Label, string Glyph);

public enum JournalActionKind { NewEntry, OnThisDay }

public sealed partial class JournalTemplateSelector : DataTemplateSelector
{
    public DataTemplate? DayTemplate { get; set; }
    public DataTemplate? EntryTemplate { get; set; }
    public DataTemplate? MemoryTemplate { get; set; }
    public DataTemplate? ActionTemplate { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item) => item switch
    {
        JournalDay => DayTemplate,
        JournalMemory => MemoryTemplate,
        JournalAction => ActionTemplate,
        _ => EntryTemplate,
    };

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container) => SelectTemplateCore(item);
}

public sealed partial class JournalPage : Page
{
    private const uint PageSize = 50;
    private const string GlyphAdd = "";
    private const string GlyphDown = "";

    private readonly ObservableCollection<object> items = [];
    private List<EntrySummary> memories = [];
    private JournalAction? toggle;
    private bool memoriesOpen;
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
        List.ItemsSource = items;
        Motion.Press(NewButton, Motion.StrongPressScale);
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
        var (todays, memo) = await AppServices.Run(s => (s.Day(today), s.OnThisDay(today)));
        if (mine != generation) return; // reloaded meanwhile

        // Rebuilt in place after an edit or a sync: no second fly-in.
        var settle = entering ? null : Motion.Quiet(List);
        items.Clear();
        after = null;
        memories = memo;
        items.Add(new JournalDay(DayLabel(today)));
        if (todays.Count == 0)
            items.Add(new JournalAction(JournalActionKind.NewEntry, "New entry", GlyphAdd));
        foreach (var e in todays) items.Add(e);
        if (memories.Count > 0)
        {
            items.Add(MemoriesToggle());
            if (memoriesOpen) foreach (var m in memories) items.Add(new JournalMemory(m));
        }
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
        var page = await AppServices.Run(s => s.Timeline(new TimelineFilter(), cursor, PageSize));
        if (mine != generation) return;

        var today = AppServices.Today;
        // The first page joins the entrance; later ones just appear as you scroll.
        var settle = entering ? null : Motion.Quiet(List);
        entering = false;
        foreach (var e in page)
        {
            if (e.Date == today) continue; // already shown at the top
            if (e.Date != lastDay)
            {
                items.Add(new JournalDay(DayLabel(e.Date)));
                lastDay = e.Date;
            }
            items.Add(e);
        }
        if (page.Count > 0) after = new TimelineCursor(page[^1].Date, page[^1].Time, page[^1].Id);
        settle?.Invoke();
        done = page.Count < PageSize;
        loading = false;
    }

    /// One toggle row that stays put while entries open beneath it; its
    /// chevron turns to point up when open.
    private JournalAction MemoriesToggle() =>
        toggle = new(JournalActionKind.OnThisDay, $"On this day · {memories.Count}", GlyphDown);

    /// Opens or closes the earlier-years entries in place, under the toggle.
    private void ToggleMemories()
    {
        var at = -1;
        for (var i = 0; i < items.Count && at < 0; i++)
            if (items[i] is JournalAction { Kind: JournalActionKind.OnThisDay }) at = i;
        if (at < 0) return;
        memoriesOpen = !memoriesOpen;
        if (Chevron(List.ContainerFromIndex(at) as ListViewItem) is { } chevron)
            Motion.Rotate(chevron, memoriesOpen ? 180 : 0);
        if (memoriesOpen)
        {
            for (var i = 0; i < memories.Count; i++) items.Insert(at + 1 + i, new JournalMemory(memories[i]));
        }
        else
        {
            while (at + 1 < items.Count && items[at + 1] is JournalMemory) items.RemoveAt(at + 1);
        }
    }

    /// TODAY, YESTERDAY, or the full date with weekday and year.
    private static string DayLabel(string ymd)
    {
        var diff = Format.Date(ymd).DayNumber - DateOnly.FromDateTime(DateTime.Now).DayNumber;
        return Format.Caps(diff switch
        {
            0 => "Today",
            -1 => "Yesterday",
            _ => Format.LongDate(ymd),
        });
    }

    private void OnScrolled(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (sender is ScrollViewer sv && sv.VerticalOffset > sv.ScrollableHeight - 800) _ = LoadMore();
    }

    /// Rows press in a touch; a reused action row's chevron matches its state.
    private void OnContainerChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.InRecycleQueue) return;
        if (args.Item is not JournalDay) Motion.Press(args.ItemContainer);
        if (Chevron(args.ItemContainer) is { } chevron)
            Motion.Rotate(chevron, ReferenceEquals(args.Item, toggle) && memoriesOpen ? 180 : 0, animate: false);
    }

    private static UIElement? Chevron(ListViewItem? container) =>
        container?.ContentTemplateRoot is FrameworkElement row ? row.FindName("Chevron") as UIElement : null;

    private void OnNew(object sender, RoutedEventArgs e) => _ = NewEntry(AppServices.Today);

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        switch (e.ClickedItem)
        {
            case EntrySummary entry:
                Shell.Window.OpenEntry(entry.Id);
                break;
            case JournalMemory memory:
                Shell.Window.OpenEntry(memory.Entry.Id);
                break;
            case JournalAction { Kind: JournalActionKind.NewEntry }:
                _ = NewEntry(AppServices.Today);
                break;
            case JournalAction { Kind: JournalActionKind.OnThisDay }:
                ToggleMemories();
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
