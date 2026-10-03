using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace Librelog.App;

/// One Librelog window at a time. Opening the sign-in link starts a second
/// process for the `librelog:` protocol; it hands the activation to the
/// running app and exits, so the link lands in the window you're using.
public static class Program
{
    [STAThread]
    private static int Main()
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();
        var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        var main = AppInstance.FindOrRegisterForKey("main");
        if (!main.IsCurrent)
        {
            main.RedirectActivationToAsync(activation).AsTask().Wait();
            return 0;
        }
        main.Activated += (_, args) => App.Current?.HandleActivation(args);

        Application.Start(_ =>
        {
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = new App(activation);
        });
        return 0;
    }
}
