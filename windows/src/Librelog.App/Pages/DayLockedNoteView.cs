using Librelog.App.Services;
using Librelog.Core;
using Librelog.Privacy;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using static Librelog.App.Services.Dialogs;

namespace Librelog.App.Pages;

/// A day's locked note (docs/locked-notes.md): one row, "Locked" or "Add a
/// locked note", shown only while locked notes are on in Settings. Opening
/// it always asks for the password; nothing is remembered. The text shows
/// only while its dialog is open and saves as it closes.
public sealed partial class DayLockedNoteView : UserControl
{
    private const string GlyphLock = "";

    private readonly TextBlock rowText;
    private readonly FontIcon rowIcon;
    private readonly TextBlock statusText;
    private string? date;
    private bool hasNote;
    private int generation;

    public DayLockedNoteView()
    {
        Visibility = Visibility.Collapsed;
        var label = new TextBlock { Text = "LOCKED NOTE", Style = Res<Style>("MicroLabel") };

        rowIcon = new FontIcon { Glyph = GlyphLock, FontSize = 14, VerticalAlignment = VerticalAlignment.Center };
        rowText = new TextBlock { VerticalAlignment = VerticalAlignment.Center, FontSize = 14 };
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        content.Children.Add(rowIcon);
        content.Children.Add(rowText);
        var row = new Button { Style = Res<Style>("DayRowButton"), Content = content };
        ToolTipService.SetToolTip(row, "Opens with the lock password");
        row.Click += (_, _) => _ = Open();
        Motion.Press(row);

        statusText = new TextBlock { Style = Res<Style>("MutedText"), FontSize = 13, Visibility = Visibility.Collapsed };

        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(label);
        panel.Children.Add(new Border { BorderBrush = Res<Brush>("HairlineBrush"), BorderThickness = new Thickness(0, 0, 0, 1), Child = row });
        panel.Children.Add(statusText);
        Content = panel;

        // As DayPhotosView: sync and our own writes reload it. Loaded catches
        // the switch being flipped in Settings meanwhile.
        Loaded += (_, _) =>
        {
            AppServices.Sync.Merged += Reload;
            Reload();
        };
        Unloaded += (_, _) => AppServices.Sync.Merged -= Reload;
        ActualThemeChanged += (_, _) => Paint();
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
            Visibility = Visibility.Collapsed;
            return;
        }
        LockStatus status;
        bool has;
        try
        {
            (status, has) = await AppServices.Run(s =>
            {
                var st = s.LockStatus();
                return (st, st.Enabled && s.HasLockedNote(day));
            });
        }
        catch (CoreException)
        {
            return; // leave it as it was
        }
        if (mine != generation) return;
        hasNote = has;
        Visibility = Format.Shown(status.Enabled);
        Paint();
    }

    private void Paint()
    {
        rowText.Text = hasNote ? "Locked" : "Add a locked note";
        rowText.FontFamily = AppearanceService.ReadingFont;
        rowText.Foreground = AppearanceService.Brush(this, hasNote ? "InkBrush" : "MutedBrush");
        rowIcon.Foreground = AppearanceService.Brush(this, "MutedBrush");
    }

    private void ShowStatus(string text)
    {
        statusText.Text = text;
        statusText.Visibility = Format.Shown(text.Length > 0);
    }

    // --- Opening ------------------------------------------------------------------

    private async Task Open()
    {
        if (date is not { } day) return;
        ShowStatus("");

        // The password, every time. A wrong one keeps the dialog open,
        // says so, and clears the field.
        var box = Password("Lock password");
        var error = ErrorText();
        var ask = new StackPanel { Spacing = 10 };
        ask.Children.Add(box);
        ask.Children.Add(error);
        var askDialog = Make(this, hasNote ? "Open the locked note" : "Add a locked note", ask, "Open");
        string? password = null;
        string? text = null;
        askDialog.PrimaryButtonClick += async (_, args) =>
        {
            var tried = box.Password;
            if (tried.Length == 0)
            {
                args.Cancel = true;
                ShowError(error, LockPassword.WrongPassword);
                return;
            }
            var deferral = args.GetDeferral();
            try
            {
                text = await AppServices.Run(s => s.LockedNote(day, tried)) ?? "";
                password = tried;
            }
            catch (CoreException ex)
            {
                args.Cancel = true;
                ShowError(error, LockPassword.IsWrongPassword(ex) ? LockPassword.WrongPassword : ex.Message);
                box.Password = "";
                box.Focus(FocusState.Programmatic);
            }
            finally
            {
                deferral.Complete();
            }
        };
        askDialog.Closed += (_, _) => box.Password = "";
        // Minimised or locked away mid-question: put the question away too.
        void ConcealAsk() => askDialog.Hide();
        WindowPrivacy.Concealed += ConcealAsk;
        ContentDialogResult asked;
        try
        {
            asked = await askDialog.ShowAsync();
        }
        finally
        {
            WindowPrivacy.Concealed -= ConcealAsk;
        }
        if (asked != ContentDialogResult.Primary || password is null || text is null) return;

        // A plain editor. Whatever it holds when it closes is saved under the
        // same password; emptied, the note goes. The TextBox hands back `\r`
        // for every line break, so both sides are compared, and saved, with
        // `\n`: opening a note and closing it untouched mustn't rewrite it.
        var original = Newlines(text);
        var editor = new TextBox
        {
            Text = text,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            PlaceholderText = "Only you can open this.",
            FontFamily = AppearanceService.ReadingFont,
            FontSize = 15 * AppearanceService.Scale,
            CornerRadius = new CornerRadius(6),
            MinWidth = 440,
            MinHeight = 240,
            MaxHeight = 480,
        };
        ScrollViewer.SetVerticalScrollBarVisibility(editor, ScrollBarVisibility.Auto);
        var noteDialog = Make(this, Format.LongDate(day), editor, null);
        noteDialog.CloseButtonText = "Done";
        // While it's open: out of screen capture, and closed (so saved) if
        // the window is minimised or the session locks, as on Android.
        void ConcealNote() => noteDialog.Hide();
        WindowPrivacy.Concealed += ConcealNote;
        var shielded = WindowPrivacy.ExcludeFromCapture();
        try
        {
            await noteDialog.ShowAsync();
        }
        finally
        {
            shielded.Dispose();
            WindowPrivacy.Concealed -= ConcealNote;
        }

        var edited = Newlines(editor.Text);
        editor.Text = "";
        text = null;
        try
        {
            if (edited != original)
            {
                var pw = password;
                await AppServices.Write(s => s.SetLockedNote(day, pw, edited));
            }
        }
        catch (CoreException ex)
        {
            ShowStatus($"Couldn't save the locked note: {ex.Message}");
        }
        finally
        {
            // Forget it: no field holds the note or its password, and the
            // locals end with this call.
            password = null;
        }
        Reload();
    }

    /// `\r\n` and lone `\r` (what a TextBox returns) as `\n`, the core's line break.
    private static string Newlines(string s) => s.Replace("\r\n", "\n").Replace('\r', '\n');
}
