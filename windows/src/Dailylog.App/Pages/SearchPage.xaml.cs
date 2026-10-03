using Dailylog.App.Services;
using Dailylog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Dailylog.App.Pages;

public sealed record SearchExample(string Query, string Meaning);

public sealed partial class SearchPage : Page
{
    private CancellationTokenSource? pending;

    public SearchPage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        Examples.ItemsSource = new SearchExample[]
        {
            new("canal walk", "entries with words starting “canal” and “walk”"),
            new("\"long way home\"", "that exact phrase"),
            new("tag:run -rain", "tagged run, without the word rain"),
            new("rating:>=4", "days rated four stars or more"),
            new("after:2026-01-01 is:favourite", "favourites written this year"),
            new("on:2025-10", "everything from October 2025"),
        };
        Update([], "");
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => QueryBox.Focus(FocusState.Programmatic);

    private async void OnQueryChanged(object sender, TextChangedEventArgs e)
    {
        pending?.Cancel();
        var cts = pending = new CancellationTokenSource();
        var query = QueryBox.Text;
        try
        {
            await Task.Delay(120, cts.Token); // settle between keystrokes
        }
        catch (TaskCanceledException)
        {
            return;
        }
        List<SearchHit> hits = string.IsNullOrWhiteSpace(query) ? [] : await AppServices.Run(s => s.Search(query, 100));
        if (!cts.IsCancellationRequested) Update(hits, query);
    }

    private void Update(List<SearchHit> hits, string query)
    {
        var searching = !string.IsNullOrWhiteSpace(query);
        ExamplesPanel.Visibility = Format.Shown(!searching);
        Results.Visibility = Format.Shown(searching);
        Results.ItemsSource = hits;
        NoResults.Visibility = Format.Shown(searching && hits.Count == 0);
    }

    private void OnExample(object sender, RoutedEventArgs e)
    {
        QueryBox.Text = (string)((FrameworkElement)sender).Tag;
        QueryBox.SelectionStart = QueryBox.Text.Length;
        QueryBox.Focus(FocusState.Programmatic);
    }

    private void OnHitClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is SearchHit hit) Shell.Window.OpenEntry(hit.Entry.Id);
    }
}
