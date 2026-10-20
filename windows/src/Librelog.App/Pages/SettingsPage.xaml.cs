using Librelog.App.Services;
using Librelog.Core;
using Librelog.Privacy;
using Librelog.Settings;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using static Librelog.App.Services.Dialogs;

namespace Librelog.App.Pages;

public sealed partial class SettingsPage : Page
{
    private bool loading;

    public SettingsPage()
    {
        InitializeComponent();
        foreach (var accent in Enum.GetValues<AccentChoice>()) AccentChoices.Children.Add(Swatch(accent));
        ActualThemeChanged += (_, _) => UpdateSwatches();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Show();
        _ = ShowLock();
    }

    /// Puts the stored choices on the controls without writing them back.
    private void Show()
    {
        var a = AppearanceService.Current;
        loading = true;
        ThemeChoices.SelectedIndex = (int)a.Theme;
        FontChoices.SelectedIndex = (int)a.Font;
        SizeChoices.SelectedIndex = (int)a.TextSize;
        loading = false;
        UpdateSwatches();
        UpdatePreview();
    }

    /// Stores a choice; the default is stored as unset.
    private async Task Choose<T>(string key, T value, T fallback) where T : struct, Enum
    {
        if (loading) return;
        await AppearanceService.Set(key, EqualityComparer<T>.Default.Equals(value, fallback) ? null : AppearanceSettings.Wire(value));
        Show();
    }

    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ThemeChoices.SelectedIndex >= 0)
            _ = Choose(AppearanceSettings.ThemeKey, (ThemeChoice)ThemeChoices.SelectedIndex, AppearanceSettings.Default.Theme);
    }

    private void OnFontChanged(object sender, SelectionChangedEventArgs e)
    {
        if (FontChoices.SelectedIndex >= 0)
            _ = Choose(AppearanceSettings.FontKey, (FontChoice)FontChoices.SelectedIndex, AppearanceSettings.Default.Font);
    }

    private void OnSizeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SizeChoices.SelectedIndex >= 0)
            _ = Choose(AppearanceSettings.TextSizeKey, (TextSizeChoice)SizeChoices.SelectedIndex, AppearanceSettings.Default.TextSize);
    }

    // --- Accent -------------------------------------------------------------------

    /// A 6px square of the accent's colour; the chosen one is ringed in ink.
    private Button Swatch(AccentChoice accent)
    {
        var name = accent.ToString();
        var button = new Button
        {
            Width = 36,
            Height = 36,
            Padding = new Thickness(3),
            CornerRadius = new CornerRadius(6),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(2),
            Tag = accent,
            Content = new Border
            {
                Width = 26,
                Height = 26,
                CornerRadius = new CornerRadius(4),
                Background = new SolidColorBrush(AppearanceService.Rgb(AppearanceSettings.AccentHex(accent))),
            },
        };
        ToolTipService.SetToolTip(button, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"{name} accent");
        button.Click += (_, _) => _ = Choose(AppearanceSettings.AccentKey, accent, AppearanceSettings.Default.Accent);
        return button;
    }

    private void UpdateSwatches()
    {
        foreach (var button in AccentChoices.Children.OfType<Button>())
        {
            var chosen = (AccentChoice)button.Tag == AppearanceService.Current.Accent;
            button.BorderBrush = chosen
                ? AppearanceService.Brush(this, "InkBrush")
                : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
    }

    private void UpdatePreview()
    {
        SampleTitle.FontFamily = AppearanceService.TitleFont;
        SampleTitle.FontWeight = Microsoft.UI.Text.FontWeights.Bold;
        SampleTitle.FontSize = 19 * AppearanceService.Scale;
        SampleBody.FontFamily = AppearanceService.ReadingFont;
        SampleBody.FontSize = 15 * AppearanceService.Scale;
    }

    private void OnSync(object sender, RoutedEventArgs e) => Shell.Window.ShowSync();

    // --- Privacy: locked notes (docs/locked-notes.md) ---------------------------------

    private LockStatus lockStatus = new(false, false);

    /// The switch and the password actions as the core has them.
    private async Task ShowLock()
    {
        lockStatus = await AppServices.Run(s => s.LockStatus());
        loading = true;
        LockSwitch.IsOn = lockStatus.Enabled;
        loading = false;
        LockActions.Visibility = Format.Shown(lockStatus.HasPassword);
    }

    private void Say(string text)
    {
        LockMessage.Text = text;
        LockMessage.Visibility = Format.Shown(text.Length > 0);
    }

    /// On for the first time sets a password; after that the switch only
    /// shows or hides the notes.
    private async void OnLockToggled(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        var on = LockSwitch.IsOn;
        Say("");
        try
        {
            if (on && !lockStatus.HasPassword)
            {
                await SetPassword(current: false); // cancelled: ShowLock turns the switch back off
            }
            else
            {
                await AppServices.Write(s => s.SetLockEnabled(on));
            }
        }
        catch (CoreException ex)
        {
            Say(ex.Message);
        }
        await ShowLock();
    }

    private async void OnChangeLockPassword(object sender, RoutedEventArgs e)
    {
        Say("");
        if (await SetPassword(current: true)) Say("Password changed. Every locked note now opens with the new one.");
        await ShowLock();
    }

    /// The new password twice (and, to change it, the current one first).
    /// True once the core has it.
    private async Task<bool> SetPassword(bool current)
    {
        var currentBox = Password("Current password");
        var newBox = Password(current ? "New password" : "Lock password");
        var againBox = Password("Again");
        var error = ErrorText();
        var body = new StackPanel { Spacing = 12 };
        if (current) body.Children.Add(currentBox);
        body.Children.Add(newBox);
        body.Children.Add(againBox);
        body.Children.Add(new TextBlock
        {
            Text = $"At least {LockPassword.MinLength} characters. There's no way to recover it: forget it and the locked notes can only be deleted.",
            Style = Res<Style>("MutedText"),
            FontSize = 13,
        });
        body.Children.Add(error);

        var dialog = Make(this, current ? "Change the lock password" : "Set a lock password", body, current ? "Change" : "Turn on");
        var done = false;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            if (LockPassword.Problem(newBox.Password, againBox.Password) is { } problem)
            {
                args.Cancel = true;
                ShowError(error, problem);
                return;
            }
            var (old, next) = (current ? currentBox.Password : null, newBox.Password);
            var deferral = args.GetDeferral();
            try
            {
                await AppServices.Write(s => s.SetLockPassword(old, next));
                done = true;
            }
            catch (CoreException ex)
            {
                args.Cancel = true;
                if (LockPassword.IsWrongPassword(ex))
                {
                    ShowError(error, LockPassword.WrongPassword);
                    currentBox.Password = "";
                    currentBox.Focus(FocusState.Programmatic);
                }
                else
                {
                    ShowError(error, ex.Message);
                }
            }
            finally
            {
                deferral.Complete();
            }
        };
        dialog.Closed += (_, _) => currentBox.Password = newBox.Password = againBox.Password = "";
        await dialog.ShowAsync();
        return done;
    }

    /// Forgot the password: every locked note goes, and the password.
    private async void OnResetLock(object sender, RoutedEventArgs e)
    {
        Say("");
        var text = new TextBlock
        {
            Text = "This deletes every locked note on every device, and the lock password. There's no way to get them back. The rest of your journal is untouched.",
            Style = Res<Style>("BodyText"),
            MaxWidth = 420,
        };
        var dialog = Make(this, "Reset locked notes?", text, "Delete locked notes");
        dialog.PrimaryButtonStyle = Res<Style>("DangerDialogButton");
        dialog.DefaultButton = ContentDialogButton.Close;
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await AppServices.Write(s => s.ResetLock());
            Say("Locked notes reset. Turn them on again to set a new password.");
        }
        catch (CoreException ex)
        {
            Say(ex.Message);
        }
        await ShowLock();
    }
}
