using Librelog.App.Services;
using Librelog.Core;
using Microsoft.UI.Xaml.Controls;

namespace Librelog.App.Pages;

public sealed partial class EntryRow : UserControl
{
    private EntrySummary? entry;
    private string? snippet;
    private string? day;

    public EntryRow() => InitializeComponent();

    public bool ShowDate { get; set; } = true;

    /// The day being shown (Calendar), so a multi-day entry reads "Day 2 of 4".
    public string? Day
    {
        get => day;
        set
        {
            day = value;
            Update();
        }
    }

    /// Set by x:Bind in list templates; recycled rows get the next entry.
    public EntrySummary? Entry
    {
        get => entry;
        set
        {
            entry = value;
            Update();
        }
    }

    /// A search snippet to show instead of the excerpt.
    public string? Snippet
    {
        get => snippet;
        set
        {
            snippet = value;
            Update();
        }
    }

    private void Update()
    {
        if (entry is not { } e) return;
        // The reading face from Settings → Appearance, picked up as rows are drawn.
        TitleText.FontFamily = AppearanceService.TitleFont;
        TitleText.FontSize = 16 * AppearanceService.Scale;
        TitleText.FontWeight = Microsoft.UI.Text.FontWeights.Bold;
        ExcerptText.FontFamily = AppearanceService.ReadingFont;
        ExcerptText.FontSize = 15 * AppearanceService.Scale;
        // A multi-day entry always says which days it covers, even in a list
        // already grouped by day.
        DateText.Text = Format.Caps(e.EndDate switch
        {
            { } end when day is not null => Format.DayOfSpan(day, e.Date, end),
            { } end => Format.Range(e.Date, end),
            null => ShowDate ? Format.ShortDate(e.Date) : "",
        });
        TimeText.Text = Format.Time(e.Time);
        TitleText.Text = e.Title ?? "";
        TitleText.Visibility = Format.Shown(!string.IsNullOrEmpty(e.Title));
        FavoriteText.Visibility = Format.Shown(e.IsFavorite);
        if (snippet is { Length: > 0 }) Format.Snippet(ExcerptText, snippet);
        else
        {
            ExcerptText.TextHighlighters.Clear();
            ExcerptText.Text = Format.Excerpt(e);
        }
        RatingText.Text = Format.Stars(e.Rating);
        TagList.ItemsSource = e.TagNames;
    }
}
