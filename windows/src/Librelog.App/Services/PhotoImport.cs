using Librelog.Core;
using Librelog.Photos;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.FileProperties;
using Windows.Storage.Search;

namespace Librelog.App.Services;

/// The Windows half of keeping a photo on a day (docs/photos.md): finding
/// the day's photos in the Pictures library, and turning a picked file into
/// what Store.AddDayPhoto takes. The rules live in Librelog.Photos.PhotoDay.
public static class PhotoImport
{
    /// How many library photos the fallback scan reads when the search index
    /// can't be asked (it's off, or the filter isn't understood).
    private const uint FallbackScan = 3000;

    /// That day's photos in the Pictures library, earliest first: taken that
    /// local day, or, with no date taken, last modified that day. Throws
    /// UnauthorizedAccessException when Windows denies access to Pictures.
    public static async Task<List<StorageFile>> LibraryPhotos(DateOnly day)
    {
        var library = KnownFolders.PicturesLibrary;
        IReadOnlyList<StorageFile>? candidates = null;
        try
        {
            var options = Options();
            options.ApplicationSearchFilter = PhotoDay.IndexFilter(day);
            candidates = await library.CreateFileQueryWithOptions(options).GetFilesAsync();
        }
        catch (Exception ex) when (ex is not UnauthorizedAccessException)
        {
            // Not indexed, or the filter wasn't understood: scan instead.
        }
        // An unindexed library can answer the filter with nothing at all, so
        // nothing found also means look the slow way, newest first.
        if (candidates is null || candidates.Count == 0)
            candidates = await library.CreateFileQueryWithOptions(Options()).GetFilesAsync(0, FallbackScan);

        var zone = TimeZoneInfo.Local;
        var found = new List<(StorageFile File, DateTimeOffset When)>();
        foreach (var file in candidates)
        {
            var props = await file.Properties.GetImagePropertiesAsync();
            var when = PhotoDay.TakenAt(props.DateTaken) is not null
                ? props.DateTaken
                : (await file.GetBasicPropertiesAsync()).DateModified;
            if (PhotoDay.Contains(day, when, zone)) found.Add((file, when));
        }
        return found.OrderBy(f => f.When).Select(f => f.File).ToList();
    }

    private static QueryOptions Options()
    {
        var options = new QueryOptions(CommonFileQuery.OrderByDate, PhotoDay.Extensions)
        {
            FolderDepth = FolderDepth.Deep,
            IndexerOption = IndexerOption.UseIndexerWhenAvailable,
        };
        options.SetPropertyPrefetch(PropertyPrefetchOptions.ImageProperties, ["System.Photo.DateTaken"]);
        return options;
    }

    /// Copies the original to a temporary file, makes the ≤1024 px preview
    /// JPEG and the ≤100 px RGBA for the ThumbHash, and reads when and where
    /// it was taken. The core moves both files in when it's added.
    public static async Task<NewPhoto> Prepare(StorageFile file)
    {
        var temp = ApplicationData.Current.TemporaryFolder;
        var stem = Guid.NewGuid().ToString("N");
        var original = await file.CopyAsync(temp, stem + file.FileType.ToLowerInvariant(), NameCollisionOption.ReplaceExisting);
        var props = await file.Properties.GetImagePropertiesAsync();

        // The preview: decoded upright (EXIF orientation applied) and in sRGB,
        // then scaled by the encoder, so no transform has to reason about
        // which way round the stored pixels are.
        uint width, height;
        var preview = await temp.CreateFileAsync(stem + "-preview.jpg", CreationCollisionOption.ReplaceExisting);
        using (var input = await original.OpenReadAsync())
        {
            var decoder = await BitmapDecoder.CreateAsync(input);
            width = decoder.OrientedPixelWidth;
            height = decoder.OrientedPixelHeight;
            var (pw, ph) = PhotoDay.Fit(width, height, PhotoDay.PreviewSide);
            using var upright = await decoder.GetSoftwareBitmapAsync(
                BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, new BitmapTransform(),
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb);
            using var output = await preview.OpenAsync(FileAccessMode.ReadWrite);
            var quality = new BitmapPropertySet { ["ImageQuality"] = new BitmapTypedValue(0.85f, PropertyType.Single) };
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output, quality);
            encoder.SetSoftwareBitmap(upright);
            encoder.BitmapTransform.ScaledWidth = pw;
            encoder.BitmapTransform.ScaledHeight = ph;
            encoder.BitmapTransform.InterpolationMode = BitmapInterpolationMode.Fant;
            await encoder.FlushAsync();
        }

        // The ThumbHash source, from the preview: already upright and sRGB.
        byte[] rgba;
        uint hw, hh;
        using (var input = await preview.OpenReadAsync())
        {
            var decoder = await BitmapDecoder.CreateAsync(input);
            (hw, hh) = PhotoDay.Fit(decoder.PixelWidth, decoder.PixelHeight, PhotoDay.HashSide);
            var transform = new BitmapTransform { ScaledWidth = hw, ScaledHeight = hh, InterpolationMode = BitmapInterpolationMode.Fant };
            var pixels = await decoder.GetPixelDataAsync(
                BitmapPixelFormat.Rgba8, BitmapAlphaMode.Straight, transform,
                ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
            rgba = pixels.DetachPixelData();
        }

        var (lat, lng) = PhotoDay.Location(props.Latitude, props.Longitude);
        return new NewPhoto(
            original.Path, PhotoDay.Mime(file.FileType), width, height, PhotoDay.TakenAt(props.DateTaken), lat, lng,
            preview.Path, rgba, hw, hh);
    }
}
