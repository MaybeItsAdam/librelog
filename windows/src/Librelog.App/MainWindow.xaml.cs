using Librelog.App.Pages;
using Librelog.App.Services;
using Librelog.Days;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
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
        ["onthisday"] = typeof(OnThisDayPage),
        ["settings"] = typeof(SettingsPage),
        ["sync"] = typeof(SyncPage),
    };

    /// The section showing, for Ctrl+Tab; the editor keeps whichever opened it.
    private string section = "journal";

    public MainWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        AddShortcut(VirtualKey.N, () => _ = JournalPage.NewEntry(AppServices.Today));
        AddShortcut(VirtualKey.K, () => Go("search"));
        AddShortcut(VirtualKey.F, () => Go("search"));
        // Ctrl+Tab and Ctrl+Shift+Tab step through the sections, as in a browser.
        AddShortcut(VirtualKey.Tab, () => Go(Sections.Step(section, 1)));
        AddShortcut(VirtualKey.Tab, () => Go(Sections.Step(section, -1)), VirtualKeyModifiers.Shift);
        Go("journal");
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
        if (args.IsSettingsInvoked) Go("settings");
        else if (args.InvokedItemContainer?.Tag is string tag) Go(tag);
    }

    private void OnBackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args) => GoBack();

    /// Keeps the pane's selection in step with the page shown.
    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        var tag = Pages.FirstOrDefault(kv => kv.Value == e.SourcePageType).Key;
        // The editor and history keep the pane on whatever opened them.
        if (tag is null) return;
        // Sync is reached from Settings, so Settings stays lit.
        section = tag == "sync" ? "settings" : tag;
        Nav.SelectedItem = section == "settings"
            ? Nav.SettingsItem
            : Nav.MenuItems.Concat(Nav.FooterMenuItems).OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == section);
    }

    private void AddShortcut(VirtualKey key, Action action, VirtualKeyModifiers extra = VirtualKeyModifiers.None)
    {
        var accelerator = new KeyboardAccelerator { Key = key, Modifiers = VirtualKeyModifiers.Control | extra };
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
