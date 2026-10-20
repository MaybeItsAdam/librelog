using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Librelog.App.Services;

/// ContentDialogs built in code for the day's sections and Settings: the
/// house dialog style, the owner's theme, an inline error line.
public static class Dialogs
{
    public static T Res<T>(string key) => (T)Application.Current.Resources[key];

    /// With a primary action, Close reads "Cancel"; without one, "Close".
    public static ContentDialog Make(FrameworkElement owner, string title, object content, string? primary)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = owner.XamlRoot,
            Style = Res<Style>("DefaultContentDialogStyle"),
            RequestedTheme = owner.ActualTheme,
            Title = title,
            Content = content,
            CloseButtonText = primary is null ? "Close" : "Cancel",
            DefaultButton = primary is null ? ContentDialogButton.Close : ContentDialogButton.Primary,
        };
        if (primary is not null) dialog.PrimaryButtonText = primary;
        return dialog;
    }

    /// The line under a dialog's fields that says what's wrong. Raspberry
    /// keeps its hex in both themes.
    public static TextBlock ErrorText() => new()
    {
        Foreground = Res<Brush>("RaspberryBrush"),
        FontSize = 13,
        TextWrapping = TextWrapping.Wrap,
        Visibility = Visibility.Collapsed,
    };

    public static void ShowError(TextBlock target, string? message)
    {
        target.Text = message ?? "";
        target.Visibility = Format.Shown(message is { Length: > 0 });
    }

    public static PasswordBox Password(string header) => new()
    {
        Header = header,
        CornerRadius = new CornerRadius(6),
        MinWidth = 320,
        PasswordRevealMode = PasswordRevealMode.Peek,
    };
}
