using Dailylog.App.Services;
using Dailylog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Dailylog.App.Pages;

public sealed partial class TodayPage : Page
{
    private static string date = AppServices.Today;

    public TodayPage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        Loaded += (_, _) => AppServices.DataChanged += Reload;
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

    private void Reload() => _ = Load();

    private async Task Load()
    {
        var day = date;
        var (entries, memories, stats) = await AppServices.Run(s => (s.Day(day), s.OnThisDay(day), s.Stats(AppServices.Today)));
        if (day != date) return; // the user moved on while loading

        WeekdayText.Text = Format.Weekday(day);
        DayText.Text = Format.DayTitle(day);
        TodayButton.IsEnabled = day != AppServices.Today;
        PromptText.Text = Prompts.For(day);
        PromptPanel.Visibility = Format.Shown(entries.Count == 0);
        EntryList.ItemsSource = entries;
        EntryList.Visibility = Format.Shown(entries.Count > 0);
        MemoryList.ItemsSource = memories;
        MemoriesPanel.Visibility = Format.Shown(memories.Count > 0);
        CurrentText.Text = stats.CurrentStreak.ToString("N0");
        LongestText.Text = stats.LongestStreak.ToString("N0");
        WordsText.Text = stats.Words.ToString("N0");
    }

    private void Shift(int days)
    {
        date = Format.Ymd(Format.Date(date).AddDays(days));
        Reload();
    }

    private void OnPrevious(object sender, RoutedEventArgs e) => Shift(-1);
    private void OnNext(object sender, RoutedEventArgs e) => Shift(1);

    private void OnToday(object sender, RoutedEventArgs e)
    {
        date = AppServices.Today;
        Reload();
    }

    private void OnNew(object sender, RoutedEventArgs e) => _ = NewEntry(date);

    private void OnEntryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is EntrySummary entry) Shell.Window.OpenEntry(entry.Id);
    }
}

/// The rotating prompt on an empty day: the same one all day, a different
/// one tomorrow.
internal static class Prompts
{
    private static readonly string[] All =
    [
        "What's one small win from today?",
        "What surprised you today?",
        "Who did you talk to, and what about?",
        "What are you looking forward to?",
        "What took more energy than it should have?",
        "What would you like to remember about today?",
        "What are you grateful for right now?",
        "What did you learn today?",
        "Where did your time actually go?",
        "What's on your mind as the day ends?",
    ];

    public static string For(string ymd) => All[Format.Date(ymd).DayNumber % All.Length];
}
