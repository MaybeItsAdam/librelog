using System.Collections.ObjectModel;
using System.Globalization;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Days;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Librelog.App.Pages;

public sealed partial class OnThisDayTemplateSelector : DataTemplateSelector
{
    public DataTemplate? YearTemplate { get; set; }
    public DataTemplate? EntryTemplate { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item) => item is JournalDay ? YearTemplate : EntryTemplate;

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container) => SelectTemplateCore(item);
}

public sealed partial class OnThisDayPage : Page
{
    private readonly ObservableCollection<object> items = [];

    public OnThisDayPage()
    {
        InitializeComponent();
        List.ItemsSource = items;
        Loaded += (_, _) => AppServices.DataChanged += Reload;
        Unloaded += (_, _) => AppServices.DataChanged -= Reload;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e) => Reload();

    private void Reload() => _ = Load();

    private async Task Load()
    {
        var today = AppServices.Today;
        var entries = await AppServices.Run(s => s.OnThisDay(today));
        var now = Format.Date(today);
        DateText.Text = now.ToString("d MMMM", CultureInfo.CurrentCulture);
        EmptyText.Visibility = Format.Shown(entries.Count == 0);

        items.Clear();
        string? year = null;
        foreach (var e in entries.OrderByDescending(e => e.Date).ThenBy(e => e.Time))
        {
            if (e.Date[..4] != year)
            {
                year = e.Date[..4];
                var day = Format.Date(e.Date);
                items.Add(new JournalDay(DayNames.YearsAgo(day, now), Format.Caps(Format.LongDate(e.Date))));
            }
            items.Add(e);
        }
    }

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is EntrySummary entry) Shell.Window.OpenEntry(entry.Id);
    }
}
