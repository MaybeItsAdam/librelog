using Dailylog.App.Services;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
using Windows.ApplicationModel.Activation;

namespace Dailylog.App;

public partial class App : Application
{
    private readonly AppActivationArguments launch;
    private MainWindow? window;

    public App(AppActivationArguments launch)
    {
        Current = this;
        this.launch = launch;
        InitializeComponent();
    }

    public static new App? Current { get; private set; }

    public MainWindow? Window => window;

    protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        AppServices.Start();
        window = new MainWindow();
        window.Activate();
        HandleActivation(launch);
        _ = AppServices.Sync.Start();
    }

    /// Launches and redirected second launches. A `dailylog://auth#…` link
    /// finishes email sign-in.
    public void HandleActivation(AppActivationArguments args)
    {
        if (args.Kind != ExtendedActivationKind.Protocol || args.Data is not IProtocolActivatedEventArgs protocol) return;
        window?.DispatcherQueue.TryEnqueue(async () =>
        {
            window.Activate();
            await AppServices.Sync.HandleRedirect(protocol.Uri);
            window.ShowSync();
        });
    }
}
