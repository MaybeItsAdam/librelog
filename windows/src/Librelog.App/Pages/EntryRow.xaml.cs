using Librelog.App.Services;
using Librelog.Core;
using Microsoft.UI.Xaml.Controls;

namespace Librelog.App.Pages;

public sealed partial class EntryRow : UserControl
{
    private EntrySummary? entry;
    private string? snippet;

    public EntryRow() => InitializeComponent();

    public bool ShowDate { get; set; } = true;

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
        DateText.Text = ShowDate ? Format.Caps(Format.ShortDate(e.Date)) : "";
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
