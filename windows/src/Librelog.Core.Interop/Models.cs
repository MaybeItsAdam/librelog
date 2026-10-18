using System.Text.Json.Serialization;

namespace Librelog.Core;

// Records mirroring core/src/model.rs and core/src/doc.rs. Field names follow
// the Rust ones (snake_case on the wire, via CoreJson's naming policy).

/// A block's kind. On the wire: {"t":"heading","level":2}, {"t":"check","checked":true}, …
public sealed record BlockKind(
    [property: JsonPropertyName("t")] string T,
    byte? Level = null,
    bool? Checked = null,
    string? AttachmentId = null)
{
    public static readonly BlockKind Paragraph = new("paragraph");
    public static readonly BlockKind Bullet = new("bullet");
    public static readonly BlockKind Numbered = new("numbered");
    public static readonly BlockKind Quote = new("quote");
    public static readonly BlockKind Code = new("code");
    public static readonly BlockKind Rule = new("rule");
    public static BlockKind Heading(int level) => new("heading", Level: (byte)Math.Clamp(level, 1, 6));
    public static BlockKind Check(bool isChecked) => new("check", Checked: isChecked);
    public static BlockKind Image(string attachmentId) => new("image", AttachmentId: attachmentId);

    [JsonIgnore] public bool IsList => T is "bullet" or "numbered" or "check";
}

/// An inline mark's kind: {"t":"bold"}, {"t":"link","url":…}, {"t":"entry_link","entry_id":…}.
public sealed record MarkKind(
    [property: JsonPropertyName("t")] string T,
    string? Url = null,
    string? EntryId = null)
{
    public static readonly MarkKind Bold = new("bold");
    public static readonly MarkKind Italic = new("italic");
    public static readonly MarkKind Underline = new("underline");
    public static readonly MarkKind Strike = new("strike");
    public static readonly MarkKind Highlight = new("highlight");
    public static readonly MarkKind Code = new("code");
    public static MarkKind Link(string url) => new("link", Url: url);
    public static MarkKind EntryLink(string entryId) => new("entry_link", EntryId: entryId);
}

/// A mark over UTF-16 offsets [Start, End) of its block's text — the same
/// unit .NET strings index by.
public sealed record Mark(uint Start, uint End, MarkKind Kind);

public sealed record Block(BlockKind Kind, string Text, IReadOnlyList<Mark> Marks, byte Indent = 0)
{
    /// Also the JSON constructor: the core omits empty `marks` and zero
    /// `indent`, which then keep these defaults.
    [JsonConstructor]
    public Block(BlockKind kind, string text) : this(kind, text, []) { }

    // Records compare lists by reference; a document compares by content.
    public bool Equals(Block? other) =>
        other is not null && Kind == other.Kind && Text == other.Text && Indent == other.Indent
        && Marks.SequenceEqual(other.Marks);

    public override int GetHashCode() => HashCode.Combine(Kind, Text, Indent, Marks.Count);
}

public sealed record Journal(string Id, string Name, string? Color, bool IsDefault);

public sealed record Tag(string Id, string Name, string? Color, bool IsTracker, string? Value);

public sealed record Entry(
    string Id, string JournalId, string Date, string? Time, string? Title,
    IReadOnlyList<Block> Body, uint WordCount, double? Rating, bool IsFavorite,
    IReadOnlyList<Tag> Tags, bool HasConflict, string UpdatedAt, string? EndDate = null);

public sealed record EntrySummary(
    string Id, string JournalId, string Date, string? Time, string? Title, string Excerpt,
    uint WordCount, double? Rating, bool IsFavorite, IReadOnlyList<string> TagNames, string? EndDate = null);

/// SpanBefore / SpanAfter: a multi-day entry continues into the previous /
/// next day, so the calendar can join the days into one band.
public sealed record DayMark(string Date, uint EntryCount, double? Rating, bool SpanBefore = false, bool SpanAfter = false);

/// Search snippet with matches wrapped in U+E000 … U+E001.
public sealed record SearchHit(EntrySummary Entry, string Snippet);

public sealed record Stats(uint Entries, ulong Words, uint DaysWritten, uint CurrentStreak, uint LongestStreak, double? AverageRating);

public sealed record TimelineFilter(string? JournalId = null, IReadOnlyList<string>? TagIds = null, double? MinRating = null, bool FavoritesOnly = false);

public sealed record TimelineCursor(string Date, string? Time, string Id);

public sealed record OutgoingBatch(string BatchId, string Payload, uint OpCount);

public sealed record OutgoingSnapshot(long Seq, string Payload);

public sealed record IncomingOps(long Seq, string DeviceId, string Payload);

public sealed record PullResult(uint Changed, IReadOnlyList<string> Conflicts, long Cursor);

public sealed record Revision(string Id, IReadOnlyList<Block> Body, string DeviceId, bool IsConflict, string CreatedAt);

/// A Markdown shortcut typed at the start of a block; remove PrefixLen UTF-16 units.
public sealed record Shortcut(BlockKind Kind, uint PrefixLen);

/// A scanned or pasted pairing code. PublicKey is base64.
public sealed record PairingCode(string Id, string PublicKey);

/// Export formats (core/src/transfer.rs). Sent to the core by name.
public enum ExportFormat { Json, Markdown, Html, Text }

public static class ExportFormats
{
    /// File extension, without the dot.
    public static string Extension(this ExportFormat f) => f switch
    {
        ExportFormat.Json => "json",
        ExportFormat.Markdown => "zip",
        ExportFormat.Html => "html",
        _ => "txt",
    };
}

public sealed record ExportSummary(uint Entries, string Path);

/// What importing a file would do. Source is "Librelog", "DayOne", "Daylio", "Diarium"
/// or "Markdown" (kept a string so the JSON stays reflection-free).
public sealed record ImportPreview(
    string Source, uint Entries, uint AlreadyImported, uint Tags,
    string? FirstDate, string? LastDate, uint SkippedImages, IReadOnlyList<string> Warnings)
{
    public uint ToImport => Entries - Math.Min(AlreadyImported, Entries);

    public string SourceName => Source switch
    {
        "DayOne" => "Day One",
        "Librelog" => "Librelog backup",
        "Diarium" => "Diarium backup",
        _ => Source,
    };
}

public sealed record ImportResult(string Source, uint Imported, uint Skipped);

/// A photo to keep on a day (core/src/media.rs, docs/photos.md). The core
/// moves Path and PreviewPath into its media folders, so the app must not use
/// them afterwards. HashRgba is the photo scaled to fit 100 × 100, straight
/// RGBA; System.Text.Json sends a byte[] as base64, which is what the C ABI
/// wants.
public sealed record NewPhoto(
    string Path, string Mime, uint Width, uint Height, string? TakenAt, double? Lat, double? Lng,
    string PreviewPath, byte[] HashRgba, uint HashWidth, uint HashHeight,
    string? SourceRef = null, string? LocalAsset = null);

/// A photo kept on a day, as this device sees it. PreviewPath and
/// OriginalPath are set only when this device has the files; otherwise draw
/// the ThumbHash.
public sealed record DayPhoto(
    string Id, string JournalId, string Date, string? Mime, uint? Width, uint? Height, string? TakenAt,
    string? Caption, string? Thumbhash, string? PreviewPath, string? OriginalPath, string? SourceRef,
    bool FromThisDevice);

/// A decoded ThumbHash: a small straight-RGBA image to draw stretched.
public sealed record ThumbImage(uint Width, uint Height, byte[] Rgba);

/// Error envelope from the C ABI.
public sealed record CoreErrorBody(string Kind, string Message);
