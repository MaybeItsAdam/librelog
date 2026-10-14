using System.Diagnostics;
using System.Numerics;
using System.Runtime.CompilerServices;
using Microsoft.UI.Composition;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.UI;
using Windows.UI.ViewManagement;

namespace Librelog.App.Services;

/// Every animation in the app, and the numbers they share. Motion is small
/// and quick: things press in, pop back, slide the way you moved. Nothing
/// casts a shadow. With Windows' "Animation effects" off, all of it is
/// skipped and the end state is shown at once.
public static class Motion
{
    // --- The numbers ----------------------------------------------------------------

    /// A row or button under the pointer.
    public const float PressScale = 0.97f;
    /// The primary action ("New entry") gives a little more.
    public const float StrongPressScale = 0.94f;
    /// Where a pop (a rating, a favourite) starts before springing back to 1.
    public const float PopScale = 1.18f;
    /// A new star rating: a smaller pop, since the whole row moves.
    public const float RatingPopScale = 1.08f;
    /// A newly selected calendar day grows into place from here.
    public const float SelectScale = 0.88f;
    /// How far a new month slides in.
    public const float SlideDistance = 36f;

    public static readonly TimeSpan Short = TimeSpan.FromMilliseconds(180);
    public static readonly TimeSpan Medium = TimeSpan.FromMilliseconds(260);
    public static readonly TimeSpan CountUpLength = TimeSpan.FromMilliseconds(700);
    public static readonly TimeSpan TintHold = TimeSpan.FromMilliseconds(1400);
    public static readonly TimeSpan TintFade = TimeSpan.FromMilliseconds(700);

    private static readonly TimeSpan SpringPeriod = TimeSpan.FromMilliseconds(45);
    private const float PressDamping = 0.9f;   // goes in without wobble
    private const float ReleaseDamping = 0.55f; // comes back with a little give
    private const float PopDamping = 0.45f;

    // --- Whether to move at all -----------------------------------------------------

    private static readonly UISettings Settings = new();

    /// Windows' Settings → Accessibility → Visual effects → Animation effects.
    public static bool Enabled
    {
        get
        {
            try { return Settings.AnimationsEnabled; }
            catch { return true; }
        }
    }

    // --- Press ----------------------------------------------------------------------

    private static readonly ConditionalWeakTable<UIElement, object> Pressable = new();

    /// Scales `element` down slightly while it's pressed and springs it back
    /// on release. Safe to call more than once on the same element.
    public static void Press(UIElement element, float scale = PressScale)
    {
        if (!Pressable.TryAdd(element, element)) return;
        // handledEventsToo: buttons and list items mark pointer events handled.
        element.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((s, _) => ScaleTo((UIElement)s, scale, PressDamping)), true);
        var up = new PointerEventHandler((s, _) => ScaleTo((UIElement)s, 1f, ReleaseDamping));
        element.AddHandler(UIElement.PointerReleasedEvent, up, true);
        element.AddHandler(UIElement.PointerCanceledEvent, up, true);
        element.AddHandler(UIElement.PointerCaptureLostEvent, up, true);
        element.AddHandler(UIElement.PointerExitedEvent, up, true);
    }

    /// Press feedback for every button already in `root`'s visual tree; the
    /// primary (azure) buttons press a little further.
    public static void PressAll(DependencyObject root)
    {
        if (root is ButtonBase button)
        {
            var primary = Application.Current.Resources.TryGetValue("PrimaryButton", out var style) && ReferenceEquals(button.Style, style);
            Press(button, primary ? StrongPressScale : PressScale);
        }
        var n = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < n; i++) PressAll(VisualTreeHelper.GetChild(root, i));
    }

    private static void ScaleTo(UIElement element, float scale, float damping)
    {
        if (!Enabled) return;
        var visual = Centered(element);
        var spring = visual.Compositor.CreateSpringVector3Animation();
        spring.FinalValue = new Vector3(scale, scale, 1);
        spring.DampingRatio = damping;
        spring.Period = SpringPeriod;
        visual.StartAnimation("Scale", spring);
    }

    // --- Pop ------------------------------------------------------------------------

    /// Starts `element` a size bigger (or smaller) and springs it to rest.
    public static void Pop(UIElement element, float from = PopScale)
    {
        if (!Enabled) return;
        var visual = Centered(element);
        var spring = visual.Compositor.CreateSpringVector3Animation();
        spring.InitialValue = new Vector3(from, from, 1);
        spring.FinalValue = Vector3.One;
        spring.DampingRatio = PopDamping;
        spring.Period = SpringPeriod;
        visual.StartAnimation("Scale", spring);
    }

    /// Moves `brush` to `to` over a short ease, or sets it outright.
    public static void TweenColor(SolidColorBrush brush, Color to, bool animate = true)
    {
        if (!animate || !Enabled)
        {
            brush.Color = to;
            return;
        }
        var tween = new ColorAnimation
        {
            To = to,
            Duration = new Duration(Medium),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
            EnableDependentAnimation = true,
        };
        Storyboard.SetTarget(tween, brush);
        Storyboard.SetTargetProperty(tween, "Color");
        var board = new Storyboard();
        board.Children.Add(tween);
        board.Begin();
    }

    // --- Rotate, slide, fade --------------------------------------------------------

    /// Turns `element` to `degrees`; `animate: false` sets it outright.
    public static void Rotate(UIElement element, float degrees, bool animate = true)
    {
        var visual = Centered(element);
        if (!animate || !Enabled)
        {
            visual.StopAnimation("RotationAngleInDegrees");
            visual.RotationAngleInDegrees = degrees;
            return;
        }
        var c = visual.Compositor;
        var turn = c.CreateScalarKeyFrameAnimation();
        turn.InsertKeyFrame(1f, degrees, Ease(c));
        turn.Duration = Short;
        visual.StartAnimation("RotationAngleInDegrees", turn);
    }

    /// Brings `element` in from `dx` pixels to the side, fading up.
    public static void SlideIn(UIElement element, float dx)
    {
        if (!Enabled) return;
        ElementCompositionPreview.SetIsTranslationEnabled(element, true);
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var c = visual.Compositor;
        var slide = c.CreateVector3KeyFrameAnimation();
        slide.InsertKeyFrame(0f, new Vector3(dx, 0, 0));
        slide.InsertKeyFrame(1f, Vector3.Zero, Ease(c));
        slide.Duration = Medium;
        visual.StartAnimation("Translation", slide);
        FadeIn(element, Medium);
    }

    public static void FadeIn(UIElement element) => FadeIn(element, Short);

    private static void FadeIn(UIElement element, TimeSpan length)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var c = visual.Compositor;
        var fade = c.CreateScalarKeyFrameAnimation();
        fade.InsertKeyFrame(0f, 0f);
        fade.InsertKeyFrame(1f, 1f, Ease(c));
        fade.Duration = length;
        visual.StartAnimation("Opacity", fade);
    }

    // --- Lists ----------------------------------------------------------------------

    /// For `ContainerContentChanging`: every row presses in a touch.
    public static void PressRows(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (!args.InRecycleQueue) Press(args.ItemContainer);
    }

    /// The house list motion: rows rise in (one after another, if `stagger`),
    /// make room for new ones and close up after removed ones.
    public static TransitionCollection ListTransitions(bool stagger = true) => new()
    {
        new EntranceThemeTransition { IsStaggeringEnabled = stagger, FromVerticalOffset = 24 },
        new AddDeleteThemeTransition(),
        new RepositionThemeTransition(),
    };

    /// Turns off `list`'s item transitions while it's rebuilt in place (after
    /// an edit or a sync), so rows don't all fly in again. Call the returned
    /// action when the rebuild is done; transitions come back after layout.
    public static Action Quiet(ItemsControl list)
    {
        var saved = list.ItemContainerTransitions;
        var panel = list.ItemsPanelRoot;
        var savedPanel = panel?.ChildrenTransitions;
        list.ItemContainerTransitions = new TransitionCollection();
        if (panel is not null) panel.ChildrenTransitions = null;
        return () => list.DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            list.ItemContainerTransitions = saved;
            if (panel is not null) panel.ChildrenTransitions = savedPanel;
        });
    }

    // --- Numbers and status ---------------------------------------------------------

    private static DispatcherQueueTimer? counter;

    /// Calls `render` with numbers rising from 0 to `to`, easing out.
    public static void CountUp(int to, Action<int> render)
    {
        counter?.Stop();
        if (!Enabled || to <= 1)
        {
            render(to);
            return;
        }
        var timer = counter = DispatcherQueue.GetForCurrentThread().CreateTimer();
        var start = Stopwatch.GetTimestamp();
        timer.Interval = TimeSpan.FromMilliseconds(16);
        timer.Tick += (t, _) =>
        {
            var p = Math.Min(1, Stopwatch.GetElapsedTime(start) / CountUpLength);
            render((int)Math.Round(to * (1 - Math.Pow(1 - p, 3))));
            if (p >= 1) t.Stop();
        };
        render(0);
        timer.Start();
    }

    /// Tints `box` with `color` the house way, a low-alpha fill and border of
    /// the same hue, then fades the tint away (unless `hold` is set).
    public static void Tint(Border box, Color color, bool hold = false)
    {
        var fill = new SolidColorBrush(color) { Opacity = 0.10 };
        var line = new SolidColorBrush(color) { Opacity = 0.40 };
        box.Background = fill;
        box.BorderBrush = line;
        if (hold || !Enabled) return;
        var board = new Storyboard();
        foreach (var brush in new[] { fill, line })
        {
            var fade = new DoubleAnimation
            {
                To = 0,
                BeginTime = TintHold,
                Duration = new Duration(TintFade),
                EnableDependentAnimation = true,
            };
            Storyboard.SetTarget(fade, brush);
            Storyboard.SetTargetProperty(fade, "Opacity");
            board.Children.Add(fade);
        }
        board.Begin();
    }

    /// Takes the tint off again.
    public static void Untint(Border box)
    {
        box.Background = null;
        box.BorderBrush = null;
    }

    // --- Shared --------------------------------------------------------------------

    private static Visual Centered(UIElement element)
    {
        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.CenterPoint = new Vector3(element.ActualSize.X / 2, element.ActualSize.Y / 2, 0);
        return visual;
    }

    /// Fast out, slow settle.
    private static CompositionEasingFunction Ease(Compositor c) =>
        c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0f), new Vector2(0f, 1f));
}
