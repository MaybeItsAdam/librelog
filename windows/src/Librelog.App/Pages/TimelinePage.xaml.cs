using System.Collections.ObjectModel;
using Librelog.App.Services;
using Librelog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Librelog.App.Pages;

public sealed partial class TimelinePage : Page
{
    private const uint PageSize = 50;
    private readonly ObservableCollection<EntrySummary> entries = [];
    private bool done;
    private bool loading;
    private int generation;

    public TimelinePage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        List.ItemsSource = entries;
        Loaded += (_, _) =>
        {
            AppServices.DataChanged += Reload;
            if (List.FindScrollViewer() is { } sv) sv.ViewChanged += OnScrolled;
        };
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => Reload();

    private TimelineFilter Filter => new(FavoritesOnly: FavoritesToggle.IsChecked == true);

    private void Reload()
    {
        generation++;
        entries.Clear();
        done = false;
        loading = false;
        _ = LoadMore();
    }

    private async Task LoadMore()
    {
        if (done || loading) return;
        loading = true;
        var mine = generation;
        var filter = Filter;
        var after = entries.Count > 0 ? new TimelineCursor(entries[^1].Date, entries[^1].Time, entries[^1].Id) : null;
        var page = await AppServices.Run(s => s.Timeline(filter, after, PageSize));
        if (mine != generation) return; // reloaded meanwhile
        foreach (var e in page) entries.Add(e);
        done = page.Count < PageSize;
        loading = false;
        EmptyText.Visibility = Format.Shown(entries.Count == 0);
    }

    private void OnScrolled(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (sender is ScrollViewer sv && sv.VerticalOffset > sv.ScrollableHeight - 800) _ = LoadMore();
    }

    private void OnFilterChanged(object sender, RoutedEventArgs e) => Reload();

    private void OnEntryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is EntrySummary entry) Shell.Window.OpenEntry(entry.Id);
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
