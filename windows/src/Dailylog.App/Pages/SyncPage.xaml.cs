using System.ComponentModel;
using System.Runtime.InteropServices.WindowsRuntime;
using Dailylog.App.Services;
using Dailylog.Core;
using Dailylog.Sync;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage.Streams;

namespace Dailylog.App.Pages;

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
}
