using Librelog.App.Pages;
using Librelog.App.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using Windows.System;

namespace Librelog.App;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<string, Type> Pages = new()
    {
        ["journal"] = typeof(JournalPage),
        ["calendar"] = typeof(CalendarPage),
        ["search"] = typeof(SearchPage),
        ["sync"] = typeof(SyncPage),
    };

    public MainWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        AddShortcut(VirtualKey.N, () => _ = JournalPage.NewEntry(AppServices.Today));
        AddShortcut(VirtualKey.K, () => Go("search"));
        AddShortcut(VirtualKey.F, () => Go("search"));
        Go("journal");
    }

    /// The pane's order, top to bottom; a switch slides the way you moved.
    private static readonly string[] Order = ["journal", "calendar", "search", "sync"];

    public void Go(string tag)
    {
        if (!Pages.TryGetValue(tag, out var page)) return;
        if (ContentFrame.CurrentSourcePageType == page) return;
        var from = Array.IndexOf(Order, Pages.FirstOrDefault(kv => kv.Value == ContentFrame.CurrentSourcePageType).Key);
        var to = Array.IndexOf(Order, tag);
        ContentFrame.Navigate(page, null, Transition(new SlideNavigationTransitionInfo
        {
            Effect = from < 0 || to >= from ? SlideNavigationTransitionEffect.FromRight : SlideNavigationTransitionEffect.FromLeft,
        }));
    }

    /// Opening an entry drills into it; Back reverses whichever transition was used.
    public void OpenEntry(string id) => ContentFrame.Navigate(typeof(EntryPage), id, Transition(new DrillInNavigationTransitionInfo()));

    public void OpenHistory(string id) =>
        ContentFrame.Navigate(typeof(HistoryPage), id, Transition(new SlideNavigationTransitionInfo { Effect = SlideNavigationTransitionEffect.FromRight }));

    private static NavigationTransitionInfo Transition(NavigationTransitionInfo info) =>
        Motion.Enabled ? info : new SuppressNavigationTransitionInfo();

    public void ShowSync() => Go("sync");

    public void GoBack()
    {
        if (ContentFrame.CanGoBack) ContentFrame.GoBack();
    }

    private void OnItemInvoked(NavigationView sender, NavigationViewItemInvokedEventArgs args)
    {
        if (args.IsSettingsInvoked) Go("sync");
        else if (args.InvokedItemContainer?.Tag is string tag) Go(tag);
    }

    private void OnBackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args) => GoBack();

    /// Keeps the pane's selection in step with the page shown.
    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        var tag = Pages.FirstOrDefault(kv => kv.Value == e.SourcePageType).Key;
        // The editor and history keep the pane on whatever opened them.
        if (tag is null) return;
        Nav.SelectedItem = tag == "sync"
            ? Nav.SettingsItem
            : Nav.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == tag);
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
