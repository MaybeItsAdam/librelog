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

    /// The error from the last thing tried on this page (a wrong password or
    /// code, an email that didn't send). Shown ahead of the engine's own
    /// problem, which would otherwise hide it on the next update.
    private string? localProblem;
    private SyncPhase? shownPhase;

    /// A web checkout was opened in the browser: the status is checked again
    /// each time the window comes back to the front, until the payment shows.
    private bool awaitingCheckout;

    public SyncPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Motion.PressAll(this);
        Loaded += (_, _) => Sync.PropertyChanged += OnSyncChanged;
        Unloaded += (_, _) =>
        {
            Sync.PropertyChanged -= OnSyncChanged;
            StopWatchingCheckout();
            pairing?.Cancel();
        };
    }

    private static SyncEngine Sync => AppServices.Sync;

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Update();
        if (Sync.Phase == SyncPhase.On) _ = Sync.RefreshSubscription();
    }

    private void OnSyncChanged(object? sender, PropertyChangedEventArgs e) => Update();

    private void Update()
    {
        var phase = Sync.Phase;
        // A new screen: the last attempt's error belonged to the old one.
        if (shownPhase is { } was && was != phase)
        {
            localProblem = null;
            // Just signed in (or unlocked): find out what the account has.
            if (phase == SyncPhase.On) _ = Sync.RefreshSubscription();
        }
        shownPhase = phase;
        SignInPanel.Visibility = Format.Shown(phase == SyncPhase.Off);
        KeyPanel.Visibility = Format.Shown(phase == SyncPhase.NeedsKey);
        OnPanel.Visibility = Format.Shown(phase == SyncPhase.On);
        PairingPanel.Visibility = Format.Shown(pairing is not null);
        PairButton.Visibility = Format.Shown(pairing is null);
        CodePanel.Visibility = Format.Shown(Sync.CodeSentTo is not null && phase == SyncPhase.Off);
        SentText.Text = Sync.CodeSentTo is { } sentTo
            ? $"We sent a sign-in email to {sentTo}. Click its link on this PC, or type the code from it."
            : "";

        NewPhrasePanel.Visibility = Format.Shown(Sync.NewRecoveryPhrase is not null);
        NewPhraseText.Text = Spaced(Sync.NewRecoveryPhrase);

        EmailText.Text = Sync.Email ?? "";
        StatusText.Text = Sync.IsSyncing ? "Syncing…"
            : Sync.LastSynced is { } at ? $"Last synced {at:t} · {Sync.Pending} waiting to upload"
            : "Not synced yet";
        // Sync is bought here on the web, or in the phone, tablet or Mac app
        // (docs/billing.md). The web plans stay on offer to add time, except
        // over a store subscription that renews by itself.
        var notActive = Sync.Problem?.Problem == SyncProblem.NotActive;
        var (headline, detail, needsSubscription) = Sync.Subscription?.Describe(DateTimeOffset.Now)
            ?? (notActive ? ("Not active", "", true) : ("—", "", false));
        SubscriptionText.Text = headline;
        SubscriptionDetail.Text = detail;
        SubscriptionDetail.Visibility = Format.Shown(detail.Length > 0);
        SubscribeHintText.Text = SubscriptionStatus.SubscribeHint;
        SubscribeHintText.Visibility = Format.Shown(needsSubscription || notActive);
        WebPlansText.Text = SubscriptionStatus.WebPlans;
        WebPlansPanel.Visibility = Format.Shown(Sync.Subscription?.OffersWebPlans(DateTimeOffset.Now) ?? true);
        CheckoutText.Visibility = Format.Shown(CheckoutText.Text.Length > 0);

        var problem = localProblem ?? Sync.Problem?.Describe();
        ProblemText.Text = problem ?? "";
        ProblemText.Visibility = Format.Shown(problem is not null);
    }

    /// Four words to a line, numbered, so it's easy to copy onto paper.
    private static string Spaced(string? phrase)
    {
        if (phrase is null) return "";
        var words = phrase.Split(' ');
        return string.Join("\n", words.Chunk(4).Select((row, r) =>
            string.Join("   ", row.Select((w, i) => $"{r * 4 + i + 1,2}. {w}"))));
    }

    /// Runs a button's action, showing what went wrong instead of letting
    /// it out of an async void handler (which would end the app). `busy` is
    /// disabled meanwhile, so a second click can't start it again.
    private async Task Try(Func<Task> action, TextBlock? errorTarget = null, object? busy = null)
    {
        var control = busy as Control;
        if (control is not null) control.IsEnabled = false;
        if (errorTarget is null) localProblem = null;
        try
        {
            await action();
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            var message = ex is SyncException s ? s.Describe() : ex.Message;
            if (errorTarget is null)
            {
                localProblem = message;
            }
            else
            {
                errorTarget.Text = message;
                errorTarget.Visibility = Visibility.Visible;
            }
        }
        finally
        {
            if (control is not null) control.IsEnabled = true;
        }
        Update();
    }

    // --- Sign-in ------------------------------------------------------------------

    private async void OnSendEmail(object sender, RoutedEventArgs e)
    {
        var email = EmailBox.Text.Trim();
        if (!email.Contains('@')) return;
        await Try(() => Sync.SendSignInEmail(email), busy: SendButton);
    }

    /// Google in the default browser, through Supabase's web flow; it comes
    /// back as a `librelog://auth/callback?code=…` activation (App.HandleActivation).
    private async void OnGoogle(object sender, RoutedEventArgs e) => await Try(async () =>
    {
        if (!await global::Windows.System.Launcher.LaunchUriAsync(Sync.BeginGoogleSignIn()))
            throw new InvalidOperationException("Couldn't open the browser.");
    });

    private async void OnVerify(object sender, RoutedEventArgs e) => await Try(() => Sync.Verify(CodeBox.Text), busy: sender);

    /// Swaps the emailed link for a password, for accounts that have one.
    private void OnTogglePassword(object sender, RoutedEventArgs e)
    {
        var show = PasswordPanel.Visibility == Visibility.Collapsed;
        PasswordPanel.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        SendButton.Visibility = show ? Visibility.Collapsed : Visibility.Visible;
        PasswordToggle.Content = show ? "Email me a sign-in link instead" : "Use a password instead";
        if (!show) PasswordBox.Password = "";
    }

    private async void OnPasswordSignIn(object sender, RoutedEventArgs e)
    {
        var email = EmailBox.Text.Trim();
        if (!email.Contains('@') || PasswordBox.Password.Length == 0) return;
        var password = PasswordBox.Password;
        await Try(() => Sync.SignIn(email, password), busy: sender);
        PasswordBox.Password = "";
    }

    private async void OnSignOut(object sender, RoutedEventArgs e)
    {
        pairing?.Cancel();
        await Try(Sync.SignOut, busy: sender);
    }

    private async void OnDeleteAccount(object sender, RoutedEventArgs e)
    {
        var dialog = Dialog("Delete your account?", new TextBlock
        {
            Text = "Your account and everything synced to it are removed from the server for good, and your other devices stop syncing. The journal on each device stays where it is.",
            TextWrapping = TextWrapping.Wrap,
        }, "Delete account");
        // A destructive choice: Enter cancels rather than deletes.
        dialog.DefaultButton = ContentDialogButton.Close;
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        pairing?.Cancel();
        await Try(Sync.DeleteAccount, busy: DeleteAccountButton);
    }

    // --- Journal key --------------------------------------------------------------

    private async void OnUsePhrase(object sender, RoutedEventArgs e) => await Try(() => Sync.UseRecoveryPhrase(PhraseBox.Text), busy: sender);

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
        }, ApproveText, busy: sender);
    }

    private void OnPhraseSaved(object sender, RoutedEventArgs e)
    {
        Sync.NewRecoveryPhrase = null;
        Update();
    }

    private async void OnShowPhrase(object sender, RoutedEventArgs e) =>
        await Try(async () => PhraseText.Text = Spaced(await AppServices.Run(s => s.RecoveryPhrase())));

    private async void OnSyncNow(object sender, RoutedEventArgs e) => await Try(async () =>
    {
        await Sync.SyncNow();
        await Sync.RefreshSubscription();
    });

    // --- Buying sync on the web -----------------------------------------------------

    private async void OnBuyOneYear(object sender, RoutedEventArgs e) => await BuyOnWeb(1, sender);

    private async void OnBuyThreeYears(object sender, RoutedEventArgs e) => await BuyOnWeb(3, sender);

    /// Stripe Checkout in the default browser, made for this account by the
    /// `stripe-checkout` Edge Function. The payment reaches the account
    /// through `stripe-webhook`, not through this app, so the status is
    /// simply asked for again when the window comes back to the front.
    private async Task BuyOnWeb(int years, object button) => await Try(async () =>
    {
        var url = await Sync.StartWebCheckout(years);
        if (!await global::Windows.System.Launcher.LaunchUriAsync(url))
            throw new InvalidOperationException("Couldn't open the browser.");
        CheckoutText.Text = "Finish paying in your browser. This page updates when you come back to it.";
        WatchForCheckout();
    }, busy: button);

    private void WatchForCheckout()
    {
        if (awaitingCheckout) return;
        awaitingCheckout = true;
        Shell.Window.Activated += OnWindowActivated;
    }

    private void StopWatchingCheckout()
    {
        if (!awaitingCheckout) return;
        awaitingCheckout = false;
        Shell.Window.Activated -= OnWindowActivated;
    }

    private async void OnWindowActivated(object sender, WindowActivatedEventArgs args)
    {
        if (args.WindowActivationState == WindowActivationState.Deactivated) return;
        var before = Sync.Subscription?.SyncUntil;
        // Stripe's webhook can land a few seconds after the browser's thank-you
        // page, so ask again once more shortly after.
        foreach (var wait in new[] { TimeSpan.Zero, TimeSpan.FromSeconds(5) })
        {
            await Task.Delay(wait);
            if (!awaitingCheckout) return;
            await Sync.RefreshSubscription();
            if (Sync.Subscription?.SyncUntil != before)
            {
                StopWatchingCheckout();
                CheckoutText.Text = "Thank you. Your payment has come through.";
                break;
            }
        }
        Update();
    }

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
        if (error) Motion.Tint(TransferBox, Raspberry, hold: true);
        else Motion.Untint(TransferBox);
    }

    private static readonly Windows.UI.Color Emerald = Windows.UI.Color.FromArgb(0xFF, 0x4C, 0xC3, 0x8E);
    private static readonly Windows.UI.Color Raspberry = Windows.UI.Color.FromArgb(0xFF, 0xD6, 0x22, 0x46);

    /// A finished import or export: the count rises to its total under a
    /// brief emerald tint.
    private void ShowDone(uint total, Func<uint, string> message)
    {
        ShowTransfer(message(0));
        Motion.Tint(TransferBox, Emerald);
        Motion.CountUp((int)total, n => TransferText.Text = message((uint)n));
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
            ShowDone(summary.Entries, n => $"Exported {Entries(n)} to {file.Name}.");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
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
        catch (Exception ex) when (ex is not OperationCanceledException)
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
            ShowDone(result.Imported, n => result.Skipped == 0
                ? $"Imported {Entries(n)}."
                : $"Imported {Entries(n)}; skipped {result.Skipped} already in your journal.");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
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
