using System.ComponentModel;
using System.Runtime.InteropServices.WindowsRuntime;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Sync;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace Librelog.App.Pages;

public sealed partial class SyncPage : Page
{
    private CancellationTokenSource? pairing;

    public SyncPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Sync.PropertyChanged += OnSyncChanged;
        Unloaded += (_, _) =>
        {
            Sync.PropertyChanged -= OnSyncChanged;
            pairing?.Cancel();
        };
    }

    private static SyncEngine Sync => AppServices.Sync;

    protected override void OnNavigatedTo(NavigationEventArgs e) => Update();

    private void OnSyncChanged(object? sender, PropertyChangedEventArgs e) => Update();

    private void Update()
    {
        var phase = Sync.Phase;
        SignInPanel.Visibility = Format.Shown(phase == SyncPhase.Off);
        KeyPanel.Visibility = Format.Shown(phase == SyncPhase.NeedsKey);
        OnPanel.Visibility = Format.Shown(phase == SyncPhase.On);
        PairingPanel.Visibility = Format.Shown(pairing is not null);
        PairButton.Visibility = Format.Shown(pairing is null);
        CodePanel.Visibility = Format.Shown(Sync.Email is not null && phase == SyncPhase.Off);
        SentText.Text = Sync.Email is { } sentTo
            ? $"We sent a sign-in email to {sentTo}. Click its link on this PC, or type the code from it."
            : "";

        NewPhrasePanel.Visibility = Format.Shown(Sync.NewRecoveryPhrase is not null);
        NewPhraseText.Text = Spaced(Sync.NewRecoveryPhrase);

        EmailText.Text = Sync.Email ?? "";
        StatusText.Text = Sync.IsSyncing ? "Syncing…"
            : Sync.LastSynced is { } at ? $"Last synced {at:t} · {Sync.Pending} waiting to upload"
            : "Not synced yet";
        ProblemText.Text = Sync.Problem?.Describe() ?? "";
        ProblemText.Visibility = Format.Shown(Sync.Problem is not null);
    }

    /// Four words to a line, numbered, so it's easy to copy onto paper.
    private static string Spaced(string? phrase)
    {
        if (phrase is null) return "";
        var words = phrase.Split(' ');
        return string.Join("\n", words.Chunk(4).Select((row, r) =>
            string.Join("   ", row.Select((w, i) => $"{r * 4 + i + 1,2}. {w}"))));
    }

    private async Task Try(Func<Task> action, TextBlock? errorTarget = null)
    {
        try
        {
            await action();
        }
        catch (Exception ex) when (ex is SyncException or CoreException)
        {
            var message = ex is SyncException s ? s.Describe() : ex.Message;
            (errorTarget ?? ProblemText).Text = message;
            (errorTarget ?? ProblemText).Visibility = Visibility.Visible;
        }
        Update();
    }

    // --- Sign-in ------------------------------------------------------------------

    private async void OnSendEmail(object sender, RoutedEventArgs e)
    {
        var email = EmailBox.Text.Trim();
        if (!email.Contains('@')) return;
        SendButton.IsEnabled = false;
        await Try(() => Sync.SendSignInEmail(email));
        SendButton.IsEnabled = true;
    }

    /// Google in the default browser, through Supabase's web flow; it comes
    /// back as a `librelog://auth/callback?code=…` activation (App.HandleActivation).
    private async void OnGoogle(object sender, RoutedEventArgs e)
    {
        if (await global::Windows.System.Launcher.LaunchUriAsync(Sync.BeginGoogleSignIn())) return;
        ProblemText.Text = "Couldn't open the browser.";
        ProblemText.Visibility = Visibility.Visible;
    }

    private async void OnVerify(object sender, RoutedEventArgs e) => await Try(() => Sync.Verify(CodeBox.Text));

    private async void OnSignOut(object sender, RoutedEventArgs e)
    {
        pairing?.Cancel();
        await Try(Sync.SignOut);
    }

    // --- Journal key --------------------------------------------------------------

    private async void OnUsePhrase(object sender, RoutedEventArgs e) => await Try(() => Sync.UseRecoveryPhrase(PhraseBox.Text));

    private async void OnPair(object sender, RoutedEventArgs e)
    {
        using var session = new PairingSession();
        var cts = pairing = new CancellationTokenSource();
        PairingCodeBox.Text = session.Code;
        QrImage.Source = await QrCode(session.Code);
        Update();
        await Try(async () =>
        {
            try
            {
                await Sync.ReceiveKeyByPairing(session, cts.Token);
            }
            catch (OperationCanceledException)
            {
                // Cancelled by the user.
            }
        });
        pairing = null;
        Update();
    }

    private void OnCancelPairing(object sender, RoutedEventArgs e) => pairing?.Cancel();

    private void OnCopyPairingCode(object sender, RoutedEventArgs e) => Copy(PairingCodeBox.Text);

    private async void OnApprove(object sender, RoutedEventArgs e)
    {
        ApproveText.Text = "";
        await Try(async () =>
        {
            await Sync.ApprovePairing(ApproveBox.Text.Trim());
            ApproveText.Text = "Sent. The other device will finish in a few seconds.";
            ApproveBox.Text = "";
        }, ApproveText);
    }

    private void OnPhraseSaved(object sender, RoutedEventArgs e)
    {
        Sync.NewRecoveryPhrase = null;
        Update();
    }

    private async void OnShowPhrase(object sender, RoutedEventArgs e) =>
        PhraseText.Text = Spaced(await AppServices.Run(s => s.RecoveryPhrase()));

    private async void OnSyncNow(object sender, RoutedEventArgs e) => await Sync.SyncNow();

    // --- Helpers ------------------------------------------------------------------

    private static async Task<BitmapImage> QrCode(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        // Grape on white: high contrast for cameras, still on palette.
        var png = new PngByteQRCode(data).GetGraphic(8, [0x44, 0x40, 0x54, 0xFF], [0xFF, 0xFF, 0xFF, 0xFF]);
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(png.AsBuffer());
        stream.Seek(0);
        var image = new BitmapImage();
        await image.SetSourceAsync(stream);
        return image;
    }

    private static void Copy(string text)
    {
        var package = new DataPackage();
        package.SetText(text);
        Clipboard.SetContent(package);
    }

    // --- Import and export (SPEC.md §16) -------------------------------------------

    private static readonly (ExportFormat Format, string Title, string Detail)[] ExportChoices =
    [
        (ExportFormat.Json, "Complete backup (JSON)", "Everything, exactly. Import it into Librelog on another device."),
        (ExportFormat.Markdown, "Markdown files (.zip)", "One file per entry. Opens in Obsidian or any text editor."),
        (ExportFormat.Html, "Web page (.html)", "Your journal as one page to read, share or print."),
        (ExportFormat.Text, "Plain text (.txt)", "Just the words."),
    ];

    private static readonly string[] ImportTypes = [".json", ".zip", ".csv", ".md", ".markdown", ".txt", ".diary", "*"];

    private static string Entries(uint n) => n == 1 ? "1 entry" : $"{n} entries";

    /// File pickers need the window handle in a desktop app.
    private static T WithWindow<T>(T picker) where T : class
    {
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(Shell.Window));
        return picker;
    }

    private ContentDialog Dialog(string title, object content, string primary) => new()
    {
        XamlRoot = XamlRoot,
        Style = (Style)Application.Current.Resources["DefaultContentDialogStyle"],
        RequestedTheme = ActualTheme, // dialogs don't inherit the theme chosen in Settings
        Title = title,
        Content = content,
        PrimaryButtonText = primary,
        CloseButtonText = "Cancel",
        DefaultButton = ContentDialogButton.Primary,
    };

    private void ShowTransfer(string message, bool error = false)
    {
        TransferText.Text = message;
        TransferText.Foreground = AppearanceService.Brush(this, error ? "RaspberryBrush" : "InkBrush");
    }

    private async void OnExport(object sender, RoutedEventArgs e)
    {
        // 1. Which format.
        var options = new RadioButtons();
        foreach (var (_, title, detail) in ExportChoices)
        {
            var label = new StackPanel { Spacing = 2 };
            label.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyText"] });
            label.Children.Add(new TextBlock
            {
                Text = detail,
                Style = (Style)Application.Current.Resources["BodyText"],
                Foreground = AppearanceService.Brush(this, "MutedBrush"),
                FontSize = 13,
                TextWrapping = TextWrapping.Wrap,
            });
            options.Items.Add(new RadioButton { Content = label, Margin = new Thickness(0, 0, 0, 6) });
        }
        options.SelectedIndex = 0;
        if (await Dialog("Export your journal", options, "Export…").ShowAsync() != ContentDialogResult.Primary) return;
        var (format, kind, _) = ExportChoices[Math.Max(0, options.SelectedIndex)];

        // 2. Where.
        var picker = WithWindow(new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
            SuggestedFileName = $"Librelog {AppServices.Today}",
        });
        picker.FileTypeChoices.Add(kind, new List<string> { "." + format.Extension() });
        var file = await picker.PickSaveFileAsync();
        if (file is null) return;

        // 3. Write it, off the UI thread.
        ShowTransfer("Exporting…");
        try
        {
            var summary = await AppServices.Run(s => s.Export(format, file.Path));
            ShowTransfer($"Exported {Entries(summary.Entries)} to {file.Name}.");
        }
        catch (CoreException ex)
        {
            ShowTransfer($"Couldn't export: {ex.Message}", error: true);
        }
    }

    private async void OnImport(object sender, RoutedEventArgs e)
    {
        var picker = WithWindow(new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary });
        foreach (var t in ImportTypes) picker.FileTypeFilter.Add(t);
        var file = await picker.PickSingleFileAsync();
        if (file is not null) await ImportFrom(file.Path);
    }

    /// A folder of Markdown notes (an Obsidian vault, say).
    private async void OnImportFolder(object sender, RoutedEventArgs e)
    {
        var picker = WithWindow(new FolderPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary });
        picker.FileTypeFilter.Add("*");
        var folder = await picker.PickSingleFolderAsync();
        if (folder is not null) await ImportFrom(folder.Path);
    }

    private async Task ImportFrom(string path)
    {
        ImportPreview preview;
        try
        {
            ShowTransfer("Reading…");
            preview = await AppServices.Run(s => s.PreviewImport(path));
            ShowTransfer("");
        }
        catch (CoreException ex)
        {
            ShowTransfer(ex.Message, error: true);
            return;
        }

        var dialog = Dialog(
            $"Import from {preview.SourceName}",
            PreviewContent(preview, this),
            preview.ToImport == 0 ? "Nothing new to import" : $"Import {Entries(preview.ToImport)}");
        dialog.IsPrimaryButtonEnabled = preview.ToImport > 0;
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        ShowTransfer("Importing…");
        try
        {
            // Write refreshes every screen and schedules a sync push.
            var result = await AppServices.Write(s => s.ImportFile(path));
            ShowTransfer(result.Skipped == 0
                ? $"Imported {Entries(result.Imported)}."
                : $"Imported {Entries(result.Imported)}; skipped {result.Skipped} already in your journal.");
        }
        catch (CoreException ex)
        {
            ShowTransfer($"Couldn't import: {ex.Message}", error: true);
        }
    }

    private static StackPanel PreviewContent(ImportPreview p, FrameworkElement scope)
    {
        var body = (Style)Application.Current.Resources["BodyText"];
        var muted = AppearanceService.Brush(scope, "MutedBrush");
        var ink = AppearanceService.Brush(scope, "InkBrush");
        var panel = new StackPanel { Spacing = 8, MaxWidth = 420 };
        void Line(string text, bool dim = false) => panel.Children.Add(new TextBlock
        {
            Text = text,
            Style = body,
            TextWrapping = TextWrapping.Wrap,
            Foreground = dim ? muted : ink,
        });

        var range = p.FirstDate is { } first && p.LastDate is { } last
            ? first == last ? $" · {Format.ShortDate(first)}" : $" · {Format.ShortDate(first)} – {Format.ShortDate(last)}"
            : "";
        Line(Entries(p.Entries) + range);
        if (p.AlreadyImported > 0) Line($"{p.AlreadyImported} already imported, will be skipped.", dim: true);
        if (p.Tags > 0) Line(p.Tags == 1 ? "1 tag" : $"{p.Tags} tags", dim: true);
        foreach (var w in p.Warnings) Line(w, dim: true);
        return panel;
    }
}
