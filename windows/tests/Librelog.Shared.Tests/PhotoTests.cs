using Librelog.Core;
using Librelog.Photos;

namespace Librelog.Tests;

/// Photos of the day: the C ABI against the real core, and the rules the
/// Windows chooser follows (docs/photos.md).
public sealed class PhotoTests : IDisposable
{
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-photos").FullName;
    private readonly CoreStore store;

    public PhotoTests() => store = CoreStore.Open(Path.Combine(dir, "journal.sqlite"));

    public void Dispose()
    {
        store.Dispose();
        Directory.Delete(dir, recursive: true);
    }

    private NewPhoto Photo(string name, byte[] bytes, string? takenAt = "2026-10-03T09:15:00+01:00")
    {
        var original = Path.Combine(dir, name + ".jpg");
        var preview = Path.Combine(dir, name + "-preview.jpg");
        File.WriteAllBytes(original, bytes);
        File.WriteAllBytes(preview, [1, 2, 3]);
        var rgba = Enumerable.Repeat(new byte[] { 200, 120, 40, 255 }, 4 * 3).SelectMany(p => p).ToArray();
        return new NewPhoto(original, "image/jpeg", 4000, 3000, takenAt, 51.5, -0.12, preview, rgba, 4, 3);
    }

    [Fact]
    public void AddsListsCaptionsAndRemoves()
    {
        var added = store.AddDayPhoto("2026-10-03", Photo("a", [1, 1, 1]));
        Assert.Equal("2026-10-03", added.Date);
        Assert.Equal((4000u, 3000u), (added.Width!.Value, added.Height!.Value));
        Assert.True(added.FromThisDevice);
        Assert.NotNull(added.Thumbhash);
        Assert.True(File.Exists(added.PreviewPath));
        Assert.True(File.Exists(added.OriginalPath));
        Assert.False(File.Exists(Path.Combine(dir, "a.jpg"))); // moved, not copied

        store.SetPhotoCaption(added.Id, "  Canal at dawn ");
        var listed = Assert.Single(store.DayPhotos("2026-10-03"));
        Assert.Equal("Canal at dawn", listed.Caption);
        store.SetPhotoCaption(listed.Id, null);
        Assert.Null(Assert.Single(store.DayPhotos("2026-10-03")).Caption);
        Assert.Empty(store.DayPhotos("2026-10-04"));

        store.RemoveDayPhoto(added.Id);
        Assert.Empty(store.DayPhotos("2026-10-03"));
        Assert.False(File.Exists(added.PreviewPath));
    }

    [Fact]
    public void SamePhotoTwiceIsOneRow()
    {
        var first = store.AddDayPhoto("2026-10-03", Photo("a", [7, 7]));
        var again = store.AddDayPhoto("2026-10-03", Photo("b", [7, 7]));
        Assert.Equal(first.Id, again.Id);
        Assert.Single(store.DayPhotos("2026-10-03"));
    }

    [Fact]
    public void ThumbhashDecodesToRgba()
    {
        var added = store.AddDayPhoto("2026-10-03", Photo("a", [5]));
        var image = store.ThumbhashImage(added.Thumbhash!);
        Assert.True(image.Width > 0 && image.Height > 0);
        Assert.Equal((int)(image.Width * image.Height * 4), image.Rgba.Length);
        Assert.Equal("invalid", Assert.Throws<CoreException>(() => store.ThumbhashImage("not base64!")).Kind);
    }

    [Fact]
    public void CoreRejectsBadPhotos()
    {
        var bad = Photo("a", [1]) with { HashWidth = 101 };
        Assert.Equal("invalid", Assert.Throws<CoreException>(() => store.AddDayPhoto("2026-10-03", bad)).Kind);
        var badDate = Photo("b", [2], takenAt: "yesterday");
        Assert.Equal("invalid", Assert.Throws<CoreException>(() => store.AddDayPhoto("2026-10-03", badDate)).Kind);
    }

    [Fact]
    public void LocalDayRange()
    {
        var london = TimeZoneInfo.FindSystemTimeZoneById("Europe/London");
        var (start, end) = PhotoDay.Range(new DateOnly(2026, 10, 3), london);
        Assert.Equal(new DateTimeOffset(2026, 10, 3, 0, 0, 0, TimeSpan.FromHours(1)), start);
        Assert.Equal(new DateTimeOffset(2026, 10, 4, 0, 0, 0, TimeSpan.FromHours(1)), end);

        // The clocks go back on 25 October 2026: a 25-hour day.
        var (s, e) = PhotoDay.Range(new DateOnly(2026, 10, 25), london);
        Assert.Equal(TimeSpan.FromHours(25), e - s);

        var day = new DateOnly(2026, 10, 3);
        Assert.True(PhotoDay.Contains(day, new DateTimeOffset(2026, 10, 3, 23, 30, 0, TimeSpan.FromHours(1)), london));
        // 23:30 UTC on the 3rd is already the 4th in London.
        Assert.False(PhotoDay.Contains(day, new DateTimeOffset(2026, 10, 3, 23, 30, 0, TimeSpan.Zero), london));
        Assert.True(PhotoDay.Contains(day, new DateTimeOffset(2026, 10, 2, 23, 30, 0, TimeSpan.Zero), london));
    }

    [Fact]
    public void MidnightSkippedByTheClocks()
    {
        // Santiago springs forward at midnight: 6 September 2026 starts at 01:00.
        var chile = TimeZoneInfo.FindSystemTimeZoneById("America/Santiago");
        var (start, _) = PhotoDay.Range(new DateOnly(2026, 9, 6), chile);
        Assert.Equal(1, start.Hour);
    }

    [Fact]
    public void DatesAndPlaces()
    {
        var taken = new DateTimeOffset(2026, 10, 3, 9, 15, 7, TimeSpan.FromHours(1));
        Assert.Equal("2026-10-03T09:15:07+01:00", PhotoDay.TakenAt(taken));
        Assert.Equal("2026-10-03T08:15:07+00:00", PhotoDay.TakenAt(taken.ToUniversalTime()));
        Assert.Null(PhotoDay.TakenAt(null));
        Assert.Null(PhotoDay.TakenAt(new DateTimeOffset(1601, 1, 1, 0, 0, 0, TimeSpan.Zero)));

        var file = new DateTimeOffset(2026, 10, 5, 12, 0, 0, TimeSpan.Zero);
        Assert.Equal(taken, PhotoDay.When(taken, file));
        Assert.Equal(file, PhotoDay.When(null, file));
        Assert.Equal(file, PhotoDay.When(default(DateTimeOffset), file));

        Assert.Equal((51.5, -0.12), PhotoDay.Location(51.5, -0.12));
        Assert.Equal((null, null), PhotoDay.Location(0, 0));
        Assert.Equal((null, null), PhotoDay.Location(51.5, null));
        Assert.Equal((null, null), PhotoDay.Location(95, 10));

        Assert.Equal("System.ItemDate:2026-10-02..2026-10-04", PhotoDay.IndexFilter(new DateOnly(2026, 10, 3)));
        Assert.Equal("image/jpeg", PhotoDay.Mime(".JPG"));
        Assert.Equal("image/heic", PhotoDay.Mime("heic"));
    }

    [Fact]
    public void FitsWithinASquare()
    {
        Assert.Equal((1024u, 768u), PhotoDay.Fit(4000, 3000, 1024));
        Assert.Equal((768u, 1024u), PhotoDay.Fit(3000, 4000, 1024));
        Assert.Equal((800u, 600u), PhotoDay.Fit(800, 600, 1024)); // never up
        Assert.Equal((100u, 1u), PhotoDay.Fit(10000, 20, 100));   // never below a pixel
        Assert.Equal((0u, 0u), PhotoDay.Fit(0, 10, 100));
    }

    [Fact]
    public void PremultipliesForDrawing()
    {
        byte[] rgba = [255, 128, 0, 255, 200, 100, 50, 0, 255, 255, 255, 128];
        Assert.Equal(new byte[] { 0, 128, 255, 255, 0, 0, 0, 0, 128, 128, 128, 128 }, Pixels.RgbaToPremultipliedBgra(rgba));
    }
}
