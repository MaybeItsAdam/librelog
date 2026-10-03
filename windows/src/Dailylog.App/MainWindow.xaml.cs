using Dailylog.App.Pages;
using Dailylog.App.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Windows.System;

namespace Dailylog.App;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<string, Type> Pages = new()
    {
        ["today"] = typeof(TodayPage),
        ["calendar"] = typeof(CalendarPage),
        ["timeline"] = typeof(TimelinePage),
        ["search"] = typeof(SearchPage),
        ["sync"] = typeof(SyncPage),
    };

    public MainWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        AddShortcut(VirtualKey.N, () => _ = TodayPage.NewEntry(AppServices.Today));
        AddShortcut(VirtualKey.K, () => Go("search"));
        AddShortcut(VirtualKey.F, () => Go("search"));
        Go("today");
    }

    public void Go(string tag)
    {
        if (!Pages.TryGetValue(tag, out var page)) return;
        if (ContentFrame.CurrentSourcePageType != page) ContentFrame.Navigate(page);
    }

    public void OpenEntry(string id) => ContentFrame.Navigate(typeof(EntryPage), id);

    public void OpenHistory(string id) => ContentFrame.Navigate(typeof(HistoryPage), id);

    public void ShowSync() => Go("sync");

    public void GoBack()
    {
        if (ContentFrame.CanGoBack) ContentFrame.GoBack();
    }

    private void OnItemInvoked(NavigationView sender, NavigationViewItemInvokedEventArgs args)
    {
        if (args.InvokedItemContainer?.Tag is string tag) Go(tag);
    }

    private void OnBackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args) => GoBack();

    /// Keeps the pane's selection in step with the page shown.
    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        var tag = Pages.FirstOrDefault(kv => kv.Value == e.SourcePageType).Key;
        Nav.SelectedItem = Nav.MenuItems.Concat(Nav.FooterMenuItems)
            .OfType<NavigationViewItem>()
            .FirstOrDefault(i => (string)i.Tag == tag);
    }

    private void AddShortcut(VirtualKey key, Action action)
    {
        var accelerator = new KeyboardAccelerator { Key = key, Modifiers = VirtualKeyModifiers.Control };
        accelerator.Invoked += (_, args) =>
        {
            action();
            args.Handled = true;
        };
        Nav.KeyboardAccelerators.Add(accelerator);
    }
}

/// Shorthand for the one window.
public static class Shell
{
    public static MainWindow Window => App.Current!.Window!;
}
