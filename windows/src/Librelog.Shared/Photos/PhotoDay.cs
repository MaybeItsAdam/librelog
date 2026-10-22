using System.Globalization;

namespace Librelog.Photos;

/// The rules for choosing and preparing a day's photos (docs/photos.md),
/// kept apart from Windows' imaging and storage APIs so they can be tested.
public static class PhotoDay
{
    /// The long side of the preview JPEG the core files under thumbs/.
    public const uint PreviewSide = 1024;

    /// The ThumbHash is made from the photo scaled to fit this square.
    public const uint HashSide = 100;

    /// What "Add photo" and "Choose another photo" offer.
    public static readonly IReadOnlyList<string> Extensions =
        [".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp", ".gif", ".tif", ".tiff", ".avif", ".bmp"];

    public static string Mime(string extension) => extension.ToLowerInvariant().TrimStart('.') switch
    {
        "jpg" or "jpeg" => "image/jpeg",
        "png" => "image/png",
        "heic" => "image/heic",
        "heif" => "image/heif",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "tif" or "tiff" => "image/tiff",
        "avif" => "image/avif",
        "bmp" => "image/bmp",
        _ => "image/jpeg",
    };

    /// The local day as an instant range [start, end), so a photo at 23:30
    /// belongs to the day it was taken on here. Days that change clocks are
    /// 23 or 25 hours long.
    public static (DateTimeOffset Start, DateTimeOffset End) Range(DateOnly day, TimeZoneInfo zone)
    {
        static DateTimeOffset At(DateOnly d, TimeZoneInfo z)
        {
            var local = d.ToDateTime(TimeOnly.MinValue);
            // A midnight skipped by the clocks going forward: the day starts an hour later.
            while (z.IsInvalidTime(local)) local = local.AddMinutes(30);
            return new DateTimeOffset(local, z.GetUtcOffset(local));
        }
        return (At(day, zone), At(day.AddDays(1), zone));
    }

    public static bool Contains(DateOnly day, DateTimeOffset when, TimeZoneInfo zone)
    {
        var (start, end) = Range(day, zone);
        return when >= start && when < end;
    }

    /// An Advanced Query Syntax filter that narrows the Pictures library to
    /// around the day through the search index. System.ItemDate is a photo's
    /// date taken, or its file date when it has none. The index keeps UTC,
    /// so the filter takes a day either side; Contains makes it exact.
    public static string IndexFilter(DateOnly day) =>
        $"System.ItemDate:{Ymd(day.AddDays(-1))}..{Ymd(day.AddDays(1))}";

    /// When the photo was taken, the date its metadata gives, or failing
    /// that the file's own date.
    public static DateTimeOffset When(DateTimeOffset? taken, DateTimeOffset fileDate) =>
        Known(taken) is { } t ? t : fileDate;

    /// RFC 3339 for the core, or null when the photo doesn't say. Windows
    /// reports a missing date as the year 1601.
    public static string? TakenAt(DateTimeOffset? taken) =>
        Known(taken)?.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);

    private static DateTimeOffset? Known(DateTimeOffset? taken) => taken is { Year: >= 1900 } t ? t : null;

    /// A latitude or longitude worth keeping: in range, and not the 0, 0
    /// some cameras write when they had no fix.
    public static (double? Lat, double? Lng) Location(double? lat, double? lng) =>
        lat is { } a && lng is { } b && double.IsFinite(a) && double.IsFinite(b)
        && Math.Abs(a) <= 90 && Math.Abs(b) <= 180 && (a, b) != (0, 0)
            ? (a, b)
            : (null, null);

    /// Scales a size to fit within `max` on its long side, keeping the shape.
    /// Never scales up; never goes below one pixel.
    public static (uint Width, uint Height) Fit(uint width, uint height, uint max)
    {
        if (width == 0 || height == 0) return (0, 0);
        if (width <= max && height <= max) return (width, height);
        var scale = (double)max / Math.Max(width, height);
        return ((uint)Math.Clamp(Math.Round(width * scale), 1, max), (uint)Math.Clamp(Math.Round(height * scale), 1, max));
    }

    /// What a photo's menu offers for the day's preview in the journal:
    /// "Use as preview" unless it already is, and "No preview" while the
    /// day shows one. `previewId` is the photo shown now, or null for none.
    public static (bool UseAsPreview, bool NoPreview) PreviewActions(string photoId, string? previewId) =>
        (previewId != photoId, previewId != null);

    private static string Ymd(DateOnly d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
}

/// Pixel conversions for drawing.
public static class Pixels
{
    /// Straight RGBA (the core's ThumbHash image) to the premultiplied BGRA a
    /// WinUI WriteableBitmap holds.
    public static byte[] RgbaToPremultipliedBgra(ReadOnlySpan<byte> rgba)
    {
        var bgra = new byte[rgba.Length - rgba.Length % 4];
        for (var i = 0; i < bgra.Length; i += 4)
        {
            int r = rgba[i], g = rgba[i + 1], b = rgba[i + 2], a = rgba[i + 3];
            bgra[i] = (byte)((b * a + 127) / 255);
            bgra[i + 1] = (byte)((g * a + 127) / 255);
            bgra[i + 2] = (byte)((r * a + 127) / 255);
            bgra[i + 3] = (byte)a;
        }
        return bgra;
    }
}
