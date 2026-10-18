using Librelog.App.Services;
using Librelog.Settings;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

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

    protected override void OnNavigatedTo(NavigationEventArgs e) => Show();

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
}
