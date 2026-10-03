using Librelog.Core;

namespace Librelog.Tests;

/// Import and export through the C ABI, against the real core library.
public sealed class TransferTests : IDisposable
{
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-transfer").FullName;

    public void Dispose() => Directory.Delete(dir, recursive: true);

    private CoreStore Open(string name) => CoreStore.Open(Path.Combine(dir, name + ".sqlite"));

    [Fact]
    public void JsonExportPreviewsAndImportsIntoAnotherStore()
    {
        using var a = Open("a");
        var e = a.CreateEntry("2026-10-02", "21:40");
        a.SaveBody(e.Id, [new Block(BlockKind.Paragraph, "Walked the canal")]);
        a.SetTitle(e.Id, "Long walk");
        a.TagEntry(e.Id, a.TagNamed("Walk").Id);

        var path = Path.Combine(dir, "backup." + ExportFormat.Json.Extension());
        var summary = a.Export(ExportFormat.Json, path);
        Assert.Equal(1u, summary.Entries);
        Assert.True(File.Exists(path));

        using var b = Open("b");
        var preview = b.PreviewImport(path);
        Assert.Equal("Librelog", preview.Source);
        Assert.Equal((1u, 0u, 1u, 1u), (preview.Entries, preview.AlreadyImported, preview.ToImport, preview.Tags));
        Assert.Equal("2026-10-02", preview.FirstDate);

        var result = b.ImportFile(path);
        Assert.Equal((1u, 0u), (result.Imported, result.Skipped));
        Assert.Equal("Long walk", b.Entry(e.Id).Title);
        Assert.Equal(0u, b.PreviewImport(path).ToImport);
        Assert.Equal(1u, b.ImportFile(path).Skipped);
    }

    [Fact]
    public void EveryExportFormatWritesAFile()
    {
        using var a = Open("formats");
        var e = a.CreateEntry("2026-10-03", null);
        a.SaveBody(e.Id, [new Block(BlockKind.Paragraph, "Hello")]);
        foreach (var f in Enum.GetValues<ExportFormat>())
        {
            var path = Path.Combine(dir, "out." + f.Extension());
            Assert.Equal(1u, a.Export(f, path).Entries);
            Assert.True(new FileInfo(path).Length > 0, f.ToString());
        }
    }

    [Fact]
    public void DaylioCsvPreview()
    {
        var path = Path.Combine(dir, "daylio_export.csv");
        File.WriteAllText(path,
            "full_date,date,weekday,time,mood,activities,note_title,note\n" +
            "2026-10-02,October 2,Friday,9:15 PM,rad,friends | walk,Great day,Pub<br>then home\n" +
            "2026-10-01,October 1,Thursday,08:00,sleepy,work,,Tired\n");
        using var s = Open("daylio");
        var p = s.PreviewImport(path);
        Assert.Equal(("Daylio", 2u), (p.Source, p.Entries));
        Assert.Equal(("2026-10-01", "2026-10-02"), (p.FirstDate, p.LastDate));
        Assert.Contains(p.Warnings, w => w.Contains("sleepy"));
    }

    [Fact]
    public void UnknownFilesAreRefused()
    {
        var path = Path.Combine(dir, "photo.png");
        File.WriteAllBytes(path, [0x89, 0x50, 0x4E, 0x47]);
        using var s = Open("bad");
        Assert.Throws<CoreException>(() => s.PreviewImport(path));
    }
}
