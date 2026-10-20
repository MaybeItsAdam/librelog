using Librelog.App.Services;
using Librelog.Core;
using Librelog.Days;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using static Librelog.App.Services.Dialogs;

namespace Librelog.App.Pages;

/// The calendar events on a day (docs/calendar.md): those kept from a
/// calendar on another device, read-only with their calendar's name, and
/// the user's own, added with "Add event" on any day and edited by clicking
/// them. Windows has no calendar source of its own yet, so it offers no
/// suggestions. Shown with the day's photos, under the editor and under the
/// calendar's selected day.
public sealed partial class DayCalendarView : UserControl
{
    private const string GlyphAdd = "";

    private readonly StackPanel rows;
    private readonly TextBlock statusText;
    private string? date;
    private int generation;

    public DayCalendarView()
    {
        var label = new TextBlock { Text = "CALENDAR", Style = Res<Style>("MicroLabel"), VerticalAlignment = VerticalAlignment.Center };
        var addContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        addContent.Children.Add(new FontIcon { Glyph = GlyphAdd, FontSize = 12 });
        addContent.Children.Add(new TextBlock { Text = "Add event" });
        var add = new Button { Style = Res<Style>("QuietButton"), Content = addContent, HorizontalAlignment = HorizontalAlignment.Right };
        ToolTipService.SetToolTip(add, "Add an event to this day");
        add.Click += (_, _) => _ = Edit(null);
        var header = new Grid();
        header.Children.Add(label);
        header.Children.Add(add);

        rows = new StackPanel();
        statusText = new TextBlock { Style = Res<Style>("MutedText"), FontSize = 13, Visibility = Visibility.Collapsed };

        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(header);
        panel.Children.Add(statusText);
        panel.Children.Add(rows);
        Content = panel;

        // As DayPhotosView: not DataChanged, which the editor raises on every
        // save. Events change here (reloaded after each write) or by sync.
        Loaded += (_, _) =>
        {
            AppServices.Sync.Merged += Reload;
            Reload();
        };
        Unloaded += (_, _) => AppServices.Sync.Merged -= Reload;
        ActualThemeChanged += (_, _) => Reload();
    }

    /// The day shown, yyyy-MM-dd.
    public string? Date
    {
        get => date;
        set
        {
            if (date == value) return;
            date = value;
            Reload();
        }
    }

    public void Reload() => _ = Load();

    private async Task Load()
    {
        var mine = ++generation;
        if (date is not { } day)
        {
            rows.Children.Clear();
            return;
        }
        List<KeptEvent> events;
        try
        {
            events = await AppServices.Run(s => s.KeptCalendarEvents(day));
        }
        catch (CoreException ex)
        {
            if (mine == generation) ShowStatus($"Couldn't read this day's events: {ex.Message}");
            return;
        }
        if (mine != generation) return;
        ShowStatus("");
        rows.Children.Clear();
        foreach (var ev in events) rows.Children.Add(Row(ev));
    }

    private void ShowStatus(string text)
    {
        statusText.Text = text;
        statusText.Visibility = Format.Shown(text.Length > 0);
    }

    private Brush Themed(string key) => AppearanceService.Brush(this, key);

    // --- Rows ---------------------------------------------------------------------

    /// Time in mono on the left; title, then place (and the calendar's name
    /// for a kept one) beneath; Moved and Cancelled as squarish tags.
    private Border Row(KeptEvent ev)
    {
        var time = new TextBlock
        {
            Text = ev.TimeLabel.Length > 0 ? ev.TimeLabel : "All day",
            Style = Res<Style>("MonoText"),
            Foreground = Themed("MutedBrush"),
            Margin = new Thickness(0, 3, 0, 0),
        };

        var title = new TextBlock
        {
            Text = ev.Title,
            FontFamily = AppearanceService.ReadingFont,
            FontSize = 15 * AppearanceService.Scale,
            Foreground = Themed(ev.IsCancelled ? "MutedBrush" : "InkBrush"),
            TextWrapping = TextWrapping.Wrap,
            TextDecorations = ev.IsCancelled ? Windows.UI.Text.TextDecorations.Strikethrough : Windows.UI.Text.TextDecorations.None,
        };
        var titleLine = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        titleLine.Children.Add(title);
        if (ev.IsMoved) titleLine.Children.Add(StatusTag("MOVED", "AmberTintBrush", "AmberBrush"));
        if (ev.IsCancelled) titleLine.Children.Add(StatusTag("CANCELLED", "RaspberryTintBrush", "RaspberryBrush"));

        var text = new StackPanel { Spacing = 2 };
        text.Children.Add(titleLine);
        if (Caption(ev) is { Length: > 0 } caption)
            text.Children.Add(new TextBlock { Text = caption, Style = Res<Style>("MutedText"), FontSize = 13, Foreground = Themed("MutedBrush") });

        var grid = new Grid { ColumnSpacing = 16 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(time);
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);

        var button = new Button { Style = Res<Style>("DayRowButton"), Content = grid };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"{ev.Title}, {time.Text}");
        ToolTipService.SetToolTip(button, ev.IsOwn ? "Edit this event" : "Event details");
        button.Click += (_, _) => _ = ev.IsOwn ? Edit(ev) : Details(ev);
        Motion.Press(button);
        return new Border
        {
            BorderBrush = Themed("HairlineBrush"),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Child = button,
        };
    }

    /// "Canal path", "Work · Canal path", "Work · Now 21 Oct".
    private static string Caption(KeptEvent ev)
    {
        var parts = new List<string>();
        if (!ev.IsOwn && ev.Calendar is { Length: > 0 } cal) parts.Add(cal);
        if (ev.Location is { Length: > 0 } place) parts.Add(place);
        if (ev.MovedToAnotherDay && ev.Start.Length >= 10) parts.Add($"Now {Format.Date(ev.Start[..10]):d MMM}");
        return string.Join(" · ", parts);
    }

    /// Status as a tinted fill, border and text of one hue; a 6px rectangle.
    private static Border StatusTag(string text, string fill, string ink) => new()
    {
        Background = Res<Brush>(fill),
        BorderBrush = Res<Brush>(ink),
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(6),
        Padding = new Thickness(6, 1, 6, 1),
        VerticalAlignment = VerticalAlignment.Center,
        Child = new TextBlock { Text = text, Style = Res<Style>("MicroLabel"), Foreground = Res<Brush>(ink) },
    };

    // --- Adding and editing your own --------------------------------------------------

    /// One dialog for both: a new event (`existing` null) or one of the
    /// user's own, which can also be removed from here.
    private async Task Edit(KeptEvent? existing)
    {
        if (date is not { } day) return;
        (bool AllDay, TimeSpan? Start, TimeSpan? End) opened = existing is null ? (true, null, null) : OwnEventForm.TimesOf(existing);
        var (allDay, start, end) = opened;

        var titleBox = new TextBox
        {
            Header = "Title",
            Text = existing?.Title ?? "",
            CornerRadius = new CornerRadius(6),
            MinWidth = 320,
            FontFamily = AppearanceService.ReadingFont,
        };
        var allDaySwitch = new ToggleSwitch { Header = "All day", IsOn = allDay };
        var startPicker = new TimePicker { Header = "Starts", MinuteIncrement = 5, CornerRadius = new CornerRadius(6), SelectedTime = start ?? new TimeSpan(9, 0, 0) };
        var endPicker = new TimePicker { Header = "Ends", MinuteIncrement = 5, CornerRadius = new CornerRadius(6), SelectedTime = end ?? (start ?? new TimeSpan(9, 0, 0)) + TimeSpan.FromHours(1) };
        if (existing is not null && !allDay && end is null) endPicker.SelectedTime = null; // it had no end
        var times = new StackPanel { Spacing = 10, Visibility = Format.Shown(!allDay) };
        times.Children.Add(startPicker);
        times.Children.Add(endPicker);
        allDaySwitch.Toggled += (_, _) => times.Visibility = Format.Shown(!allDaySwitch.IsOn);
        // Moving the start past the end takes the end along, an hour on.
        startPicker.SelectedTimeChanged += (_, _) =>
        {
            if (startPicker.SelectedTime is { } s && endPicker.SelectedTime is { } e && e <= s && s < new TimeSpan(23, 0, 0))
                endPicker.SelectedTime = s + TimeSpan.FromHours(1);
        };
        var placeBox = new TextBox
        {
            Header = "Place",
            PlaceholderText = "Optional",
            Text = existing?.Location ?? "",
            CornerRadius = new CornerRadius(6),
        };
        var error = ErrorText();

        var body = new StackPanel { Spacing = 14 };
        body.Children.Add(titleBox);
        body.Children.Add(allDaySwitch);
        body.Children.Add(times);
        body.Children.Add(placeBox);
        body.Children.Add(error);

        var dialog = Make(this, existing is null ? $"Add an event to {Format.ShortDate(day)}" : "Edit event", body, existing is null ? "Add" : "Save");
        if (existing is not null)
        {
            dialog.SecondaryButtonText = "Remove from this day";
            dialog.SecondaryButtonStyle = Res<Style>("DangerDialogButton");
        }
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var (ev, problem) = OwnEventForm.Validate(titleBox.Text, allDaySwitch.IsOn, startPicker.SelectedTime, endPicker.SelectedTime, placeBox.Text);
            if (ev is null)
            {
                args.Cancel = true;
                ShowError(error, problem);
                return;
            }
            var deferral = args.GetDeferral();
            try
            {
                if (existing is null) await AppServices.Write(s => s.AddOwnEvent(day, ev));
                else await AppServices.Write(s => s.UpdateOwnEvent(existing.Id, ev));
            }
            catch (CoreException ex)
            {
                // The core checks the same rules; say what it said.
                args.Cancel = true;
                ShowError(error, ex.Message.Length > 0 ? char.ToUpper(ex.Message[0]) + ex.Message[1..] + "." : "Couldn't save the event.");
            }
            finally
            {
                deferral.Complete();
            }
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Secondary && existing is not null) await Remove(existing);
        Reload();
    }

    /// An event kept from a calendar follows that calendar, so it's shown,
    /// not edited; it can still come off the day.
    private async Task Details(KeptEvent ev)
    {
        var body = new StackPanel { Spacing = 8, MinWidth = 320 };
        body.Children.Add(new TextBlock { Text = ev.TimeLabel.Length > 0 ? ev.TimeLabel : "All day", Style = Res<Style>("MonoText") });
        if (ev.Location is { Length: > 0 } place) body.Children.Add(new TextBlock { Text = place, Style = Res<Style>("BodyText") });
        var from = ev.Calendar is { Length: > 0 } cal ? $"From the “{cal}” calendar" : "From a calendar";
        body.Children.Add(new TextBlock
        {
            Text = $"{from}, kept on another device. It follows that calendar, so it's changed there.",
            Style = Res<Style>("MutedText"),
            FontSize = 13,
        });
        if (ev.IsMoved || ev.IsCancelled)
        {
            var tags = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            if (ev.IsMoved) tags.Children.Add(StatusTag("MOVED", "AmberTintBrush", "AmberBrush"));
            if (ev.IsCancelled) tags.Children.Add(StatusTag("CANCELLED", "RaspberryTintBrush", "RaspberryBrush"));
            body.Children.Add(tags);
        }
        if (ev.Note is { Length: > 0 } note)
        {
            body.Children.Add(new TextBlock { Text = "NOTE", Style = Res<Style>("MicroLabel"), Margin = new Thickness(0, 6, 0, 0) });
            body.Children.Add(new TextBlock { Text = note, Style = Res<Style>("BodyText"), FontFamily = AppearanceService.ReadingFont });
        }

        var dialog = Make(this, ev.Title, body, null);
        dialog.SecondaryButtonText = "Remove from this day";
        dialog.SecondaryButtonStyle = Res<Style>("DangerDialogButton");
        if (await dialog.ShowAsync() == ContentDialogResult.Secondary) await Remove(ev);
    }

    private async Task Remove(KeptEvent ev)
    {
        try
        {
            await AppServices.Write(s => s.RemoveCalendarEvent(ev.Id));
        }
        catch (CoreException ex)
        {
            ShowStatus($"Couldn't remove it: {ex.Message}");
        }
        Reload();
    }
}
