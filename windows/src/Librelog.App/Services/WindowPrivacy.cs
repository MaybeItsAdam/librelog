using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace Librelog.App.Services;

/// Keeps an open locked note (docs/locked-notes.md) to the person at the
/// screen: out of screenshots, recordings and screen sharing while it's open,
/// and closed when the window is minimised or the session locks. Android does
/// the same with FLAG_SECURE and by closing the note when the app stops.
public static partial class WindowPrivacy
{
    private const uint WmSize = 0x0005;
    private const uint WmWtsSessionChange = 0x02B1;
    private const nint SizeMinimized = 1;
    private const nint WtsSessionLock = 0x7;
    private const uint NotifyForThisSession = 0;

    private const uint WdaNone = 0x00;
    private const uint WdaMonitor = 0x01;
    private const uint WdaExcludeFromCapture = 0x11;

    private static nint hwnd;
    private static DispatcherQueue? queue;
    private static int excluding;

    /// Raised on the UI thread when the window is minimised or the session
    /// locks: anything private on screen should close.
    public static event Action? Concealed;

    /// Watches the one window. Call once, after it's made.
    public static unsafe void Install(Window window)
    {
        hwnd = WinRT.Interop.WindowNative.GetWindowHandle(window);
        queue = window.DispatcherQueue;
        delegate* unmanaged[Stdcall]<nint, uint, nint, nint, nuint, nuint, nint> proc = &SubclassProc;
        SetWindowSubclass(hwnd, (nint)proc, 1, 0);
        WTSRegisterSessionNotification(hwnd, NotifyForThisSession);
    }

    /// Leaves the window out of screen capture until the result is disposed.
    public static IDisposable ExcludeFromCapture()
    {
        if (hwnd == 0) return new Restore(counted: false);
        // Windows before 10 2004 has no exclusion; there the window is
        // captured as black instead.
        if (excluding++ == 0 && !SetWindowDisplayAffinity(hwnd, WdaExcludeFromCapture))
            SetWindowDisplayAffinity(hwnd, WdaMonitor);
        return new Restore(counted: true);
    }

    private sealed class Restore(bool counted) : IDisposable
    {
        private bool done;

        public void Dispose()
        {
            if (done || !counted) return;
            done = true;
            if (--excluding == 0) SetWindowDisplayAffinity(hwnd, WdaNone);
        }
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvStdcall) })]
    private static nint SubclassProc(nint hWnd, uint msg, nint wParam, nint lParam, nuint id, nuint data)
    {
        if ((msg == WmSize && wParam == SizeMinimized) || (msg == WmWtsSessionChange && wParam == WtsSessionLock))
        {
            try
            {
                // After this message is handled, not inside it.
                queue?.TryEnqueue(() => Concealed?.Invoke());
            }
            catch (Exception)
            {
                // Nothing may unwind into Windows from a window procedure.
            }
        }
        return DefSubclassProc(hWnd, msg, wParam, lParam);
    }

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowDisplayAffinity(nint hWnd, uint dwAffinity);

    [LibraryImport("wtsapi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool WTSRegisterSessionNotification(nint hWnd, uint dwFlags);

    [LibraryImport("comctl32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowSubclass(nint hWnd, nint pfnSubclass, nuint uIdSubclass, nuint dwRefData);

    [LibraryImport("comctl32.dll")]
    private static partial nint DefSubclassProc(nint hWnd, uint uMsg, nint wParam, nint lParam);
}
