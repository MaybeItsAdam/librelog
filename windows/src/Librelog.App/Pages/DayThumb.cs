using Librelog.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Librelog.App.Pages;

/// The small square on the left of a day's first entry in the journal: the
/// photo standing for that day (docs/photos.md). Decoded once at thumbnail
/// size and kept in memory, never written anywhere, so scrolling back up
/// doesn't decode again. A photo kept on another device shows its blurred
/// ThumbHash, like the day's tiles.
public sealed partial class DayThumb : UserControl
{
    public const double Side = 52;

    private static readonly Dictionary<string, ImageSource> Cache = [];
    private static readonly Queue<string> Order = new();
    private const int CacheLimit = 200;

    private readonly Image image = new() { Stretch = Stretch.UniformToFill };
    private DayPhoto? photo;

    public DayThumb()
    {
        Width = Side;
        Height = Side;
        VerticalAlignment = VerticalAlignment.Top;
        // Decorative: the entry beside it carries the row's meaning.
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAccessibilityView(this, Microsoft.UI.Xaml.Automation.Peers.AccessibilityView.Raw);
        Content = new Border
        {
            CornerRadius = new CornerRadius(6),
            BorderThickness = new Thickness(1),
            BorderBrush = (Brush)Application.Current.Resources["HairlineBrush"],
            Background = (Brush)Application.Current.Resources["WellBrush"],
            Child = image,
        };
    }

    /// Set by x:Bind in the journal's template; recycled rows get the next.
    public DayPhoto? Photo
    {
        get => photo;
        set
        {
            if (photo?.Id == value?.Id && photo?.PreviewPath == value?.PreviewPath) return;
            photo = value;
            image.Source = null;
            Visibility = value is null ? Visibility.Collapsed : Visibility.Visible;
            if (value is not null) _ = Load(value);
        }
    }

    private async Task Load(DayPhoto p)
    {
        var key = $"{p.Id}|{p.PreviewPath}";
        if (!Cache.TryGetValue(key, out var picture))
        {
            // Twice the side, for high-DPI screens.
            picture = await DayPhotosView.Picture(p, decodeWidth: (int)(Side * 2));
            if (picture is null) return;
            Cache[key] = picture;
            Order.Enqueue(key);
            while (Order.Count > CacheLimit) Cache.Remove(Order.Dequeue());
        }
        if (photo?.Id == p.Id) image.Source = picture;
    }
}
