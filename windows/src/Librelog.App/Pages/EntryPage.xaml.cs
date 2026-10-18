using System.Globalization;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Editor;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using Windows.System;

namespace Librelog.App.Pages;

public sealed partial class EntryPage : Page
{
    private static readonly TimeSpan SaveDelay = TimeSpan.FromMilliseconds(700);

    private Entry? entry;
    private bool loading;
    private bool dirty;
    private CancellationTokenSource? saveTimer;

    /// Every write still on its way to the core, so leaving can wait for them
    /// before asking whether the entry is empty.
    private Task writes = Task.CompletedTask;

    public EntryPage()
    {
        InitializeComponent();
        Editor.Controller.Changed += OnBodyChanged;
        Editor.StateChanged += UpdateFormatBar;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        if (e.Parameter is not string id) return;
        loading = true;
        try
        {
            entry = await AppServices.Run(s => s.Entry(id));
        }
        catch (CoreException ex) when (ex.Kind == "not_found")
        {
            Shell.Window.GoBack();
            return;
        }
        var en = entry;
        DatePicker.Date = Format.Date(en.Date).ToDateTime(TimeOnly.MinValue);
        TimePicker.SelectedTime = en.Time is { } t ? TimeOnly.ParseExact(t, "HH:mm", CultureInfo.InvariantCulture).ToTimeSpan() : null;
        ShowSpan(en.Date, en.EndDate);
        TitleBox.Text = en.Title ?? "";
        TitleBox.FontFamily = AppearanceService.TitleFont;
        TitleBox.FontSize = 26 * AppearanceService.Scale;
        Photos.Date = en.Date;
        Rating.Value = en.Rating ?? -1;
        FavoriteButton.IsChecked = en.IsFavorite;
        TagList.ItemsSource = en.Tags;
        ConflictBanner.Visibility = Format.Shown(en.HasConflict);
        Editor.Load(en.Body);
        WordCount.Text = Format.Words(en.WordCount);
        SavedText.Text = "";
        loading = false;
        Editor.Focus(FocusState.Programmatic);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        saveTimer?.Cancel();
        if (dirty) Track(Save());
        // An entry left with nothing in it goes, rather than staying as an
        // empty row. Not when it's only opening its own history.
        if (entry is { } en && e.SourcePageType != typeof(HistoryPage)) _ = Discard(en.Id);
    }

    private async Task Discard(string id)
    {
        try
        {
            await writes;
        }
        catch (CoreException)
        {
            // A failed write is no reason to keep or lose the entry.
        }
        await AppServices.DiscardIfEmpty(id);
    }

    private void Track(Task write) => writes = Task.WhenAll(writes, write);

    // --- Saving -----------------------------------------------------------------

    private void OnBodyChanged()
    {
        if (loading || entry is null) return;
        dirty = true;
        SavedText.Text = "Editing";
        saveTimer?.Cancel();
        var cts = saveTimer = new CancellationTokenSource();
        _ = SaveAfter(cts.Token);
    }

    private async Task SaveAfter(CancellationToken ct)
    {
        try
        {
            await Task.Delay(SaveDelay, ct);
        }
        catch (TaskCanceledException)
        {
            return;
        }
        await Save();
    }

    private async Task Save()
    {
        if (entry is not { } en) return;
        dirty = false;
        var blocks = Editor.Blocks();
        var saving = AppServices.Write(s => s.SaveBody(en.Id, blocks));
        Track(saving);
        var words = await saving;
        WordCount.Text = Format.Words(words);
        SavedText.Text = "Saved";
    }

    private Task Update(Action<CoreStore, string> write)
    {
        if (loading || entry is not { } en) return Task.CompletedTask;
        var task = AppServices.Write(s => write(s, en.Id));
        Track(task);
        return task;
    }

    // --- Header fields ------------------------------------------------------------

    private void OnTitleChanged(object sender, TextChangedEventArgs e)
    {
        var title = TitleBox.Text;
        _ = Update((s, id) => s.SetTitle(id, title));
    }

    private void OnRatingChanged(RatingControl sender, object args)
    {
        double? rating = Rating.Value < 0 ? null : Rating.Value;
        _ = Update((s, id) => s.SetRating(id, rating));
    }

    private void OnFavorite(object sender, RoutedEventArgs e)
    {
        var fav = FavoriteButton.IsChecked == true;
        _ = Update((s, id) => s.SetFavorite(id, fav));
    }

    private void OnDateChanged(CalendarDatePicker sender, CalendarDatePickerDateChangedEventArgs args) => SaveDateTime();
    private void OnTimeChanged(TimePicker sender, TimePickerSelectedValueChangedEventArgs args) => SaveDateTime();

    private void SaveDateTime()
    {
        if (DatePicker.Date is not { } d) return;
        var date = Format.Ymd(DateOnly.FromDateTime(d.Date));
        if (!loading) Photos.Date = date;
        var time = TimePicker.SelectedTime is { } t ? TimeOnly.FromTimeSpan(t).ToString("HH:mm", CultureInfo.InvariantCulture) : null;
        _ = MoveTo(date, time);
    }

    /// The core keeps a multi-day entry's length when its first day moves, so
    /// read the new end back rather than working it out here.
    private async Task MoveTo(string date, string? time)
    {
        if (loading || entry is not { } en) return;
        await AppServices.Write(s => s.SetDateTime(en.Id, date, time));
        var moved = await AppServices.Run(s => s.Entry(en.Id));
        loading = true;
        ShowSpan(moved.Date, moved.EndDate);
        loading = false;
    }

    // --- Several days -------------------------------------------------------------

    private const int MaxSpanDays = 365;

    private static DateTimeOffset At(DateOnly d) => new(d.ToDateTime(TimeOnly.MinValue));

    /// Sets the range label, the checkbox and the last-day picker (limited to
    /// the day after the start up to a year on) without saving anything.
    private void ShowSpan(string start, string? end)
    {
        var first = Format.Date(start);
        var (min, max) = (At(first.AddDays(1)), At(first.AddDays(MaxSpanDays)));
        // Never let Min pass Max on the way: move whichever bound is in the way first.
        if (min > EndPicker.MaxDate)
        {
            EndPicker.MaxDate = max;
            EndPicker.MinDate = min;
        }
        else
        {
            EndPicker.MinDate = min;
            EndPicker.MaxDate = max;
        }
        EndPicker.Date = end is null ? null : At(Format.Date(end));
        SeveralDaysBox.IsChecked = end is not null;
        EndPicker.Visibility = Format.Shown(end is not null);
        RangeText.Text = Format.Caps(end is null
            ? Format.LongDate(start)
            : $"{Format.Range(start, end)} · {Format.Date(end).DayNumber - first.DayNumber + 1} days");
    }

    private void OnSeveralDays(object sender, RoutedEventArgs e)
    {
        if (loading || entry is null || DatePicker.Date is not { } d) return;
        var start = DateOnly.FromDateTime(d.Date);
        // Turning it on starts as two days; the picker refines it.
        string? end = SeveralDaysBox.IsChecked == true ? Format.Ymd(start.AddDays(1)) : null;
        _ = SaveEnd(Format.Ymd(start), end);
    }

    private void OnEndChanged(CalendarDatePicker sender, CalendarDatePickerDateChangedEventArgs args)
    {
        if (loading || entry is null || DatePicker.Date is not { } d || EndPicker.Date is not { } end) return;
        _ = SaveEnd(Format.Ymd(DateOnly.FromDateTime(d.Date)), Format.Ymd(DateOnly.FromDateTime(end.Date)));
    }

    private async Task SaveEnd(string start, string? end)
    {
        if (entry is not { } en) return;
        await AppServices.Write(s => s.SetEndDate(en.Id, end));
        loading = true;
        ShowSpan(start, end);
        loading = false;
    }

    private async void OnTagKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter || entry is not { } en) return;
        e.Handled = true;
        var name = TagBox.Text.Trim();
        if (name.Length == 0) return;
        TagBox.Text = "";
        var tagging = AppServices.Write(s => s.TagEntry(en.Id, s.TagNamed(name).Id));
        Track(tagging);
        await tagging;
        await ReloadTags();
    }

    private async void OnRemoveTag(object sender, RoutedEventArgs e)
    {
        if (entry is not { } en || ((FrameworkElement)sender).Tag is not string tagId) return;
        await AppServices.Write(s => s.UntagEntry(en.Id, tagId));
        await ReloadTags();
    }

    private async Task ReloadTags()
    {
        if (entry is not { } en) return;
        TagList.ItemsSource = (await AppServices.Run(s => s.Entry(en.Id))).Tags;
    }

    // --- Format bar ---------------------------------------------------------------

    private void OnBold(object sender, RoutedEventArgs e) => Editor.Toggle(Inline.Bold);
    private void OnItalic(object sender, RoutedEventArgs e) => Editor.Toggle(Inline.Italic);
    private void OnUnderline(object sender, RoutedEventArgs e) => Editor.Toggle(Inline.Underline);
    private void OnHighlight(object sender, RoutedEventArgs e) => Editor.Toggle(Inline.Highlight);
    private void OnHeading(object sender, RoutedEventArgs e) => Editor.SetKind(BlockKind.Heading(2));
    private void OnBullet(object sender, RoutedEventArgs e) => Editor.SetKind(BlockKind.Bullet);
    private void OnNumbered(object sender, RoutedEventArgs e) => Editor.SetKind(BlockKind.Numbered);
    private void OnCheck(object sender, RoutedEventArgs e) => Editor.SetKind(BlockKind.Check(false));
    private void OnQuote(object sender, RoutedEventArgs e) => Editor.SetKind(BlockKind.Quote);

    /// Reflects the caret's paragraph and the active marks.
    private void UpdateFormatBar()
    {
        var c = Editor.Controller;
        var kind = c.CurrentKind;
        BoldButton.IsChecked = c.IsActive(Inline.Bold);
        ItalicButton.IsChecked = c.IsActive(Inline.Italic);
        UnderlineButton.IsChecked = c.IsActive(Inline.Underline);
        HighlightButton.IsChecked = c.IsActive(Inline.Highlight);
        HeadingButton.IsChecked = kind.T == "heading";
        BulletButton.IsChecked = kind.T == "bullet";
        NumberedButton.IsChecked = kind.T == "numbered";
        CheckButton.IsChecked = kind.T == "check";
        QuoteButton.IsChecked = kind.T == "quote";
    }

    // --- Menu ---------------------------------------------------------------------

    private void OnCopyMarkdown(object sender, RoutedEventArgs e)
    {
        var markdown = CoreFunctions.BlocksToMarkdown(Editor.Blocks());
        if (TitleBox.Text.Trim() is { Length: > 0 } title) markdown = $"# {title}\n\n{markdown}";
        var package = new DataPackage();
        package.SetText(markdown);
        Clipboard.SetContent(package);
        SavedText.Text = "Copied as Markdown";
    }

    private async void OnHistory(object sender, RoutedEventArgs e)
    {
        if (entry is not { } en) return;
        if (dirty) await Save();
        Shell.Window.OpenHistory(en.Id);
    }

    private async void OnDelete(object sender, RoutedEventArgs e)
    {
        if (entry is not { } en) return;
        var confirm = new ContentDialog
        {
            XamlRoot = XamlRoot,
            RequestedTheme = ActualTheme,
            Title = "Delete this entry?",
            Content = "It moves to Trash for 30 days, then it's gone from every device.",
            PrimaryButtonText = "Delete",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
        dirty = false;
        saveTimer?.Cancel();
        await AppServices.Write(s => s.DeleteEntry(en.Id));
        entry = null; // gone already: nothing to discard on the way out
        Shell.Window.GoBack();
    }
}
