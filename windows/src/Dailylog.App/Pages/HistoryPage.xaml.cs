using System.Globalization;
using Dailylog.App.Services;
using Dailylog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;

namespace Dailylog.App.Pages;

public sealed record VersionItem(string Label, string Markdown, Visibility ConflictVisibility);

public sealed partial class HistoryPage : Page
{
    private string? entryId;

    public HistoryPage() => InitializeComponent();

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        if (e.Parameter is not string id) return;
        entryId = id;
        var (entry, revisions) = await AppServices.Run(s => (s.Entry(id), s.Revisions(id)));
        HeadingText.Text = entry.Title ?? Format.DayTitle(entry.Date);
        var items = revisions.Select(r => new VersionItem(
            Label(r),
            CoreFunctions.BlocksToMarkdown(r.Body),
            Format.Shown(r.IsConflict))).ToList();
        Versions.ItemsSource = items;
        EmptyText.Visibility = Format.Shown(items.Count == 0);
        ReviewedButton.Visibility = Format.Shown(revisions.Any(r => r.IsConflict));
    }

    private static string Label(Revision r) =>
        DateTimeOffset.TryParse(r.CreatedAt, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)
            ? at.ToLocalTime().ToString("f", CultureInfo.CurrentCulture)
            : r.CreatedAt;

    private void OnCopy(object sender, RoutedEventArgs e)
    {
        var package = new DataPackage();
        package.SetText((string)((FrameworkElement)sender).Tag);
        Clipboard.SetContent(package);
    }

    private async void OnReviewed(object sender, RoutedEventArgs e)
    {
        if (entryId is not { } id) return;
        await AppServices.Write(s => s.ResolveConflicts(id));
        Shell.Window.GoBack();
    }
}
