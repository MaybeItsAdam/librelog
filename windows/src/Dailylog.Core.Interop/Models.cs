using System.Text.Json.Serialization;

namespace Dailylog.Core;

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
    IReadOnlyList<Tag> Tags, bool HasConflict, string UpdatedAt);

public sealed record EntrySummary(
    string Id, string JournalId, string Date, string? Time, string? Title, string Excerpt,
    uint WordCount, double? Rating, bool IsFavorite, IReadOnlyList<string> TagNames);

public sealed record DayMark(string Date, uint EntryCount, double? Rating);

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

/// Error envelope from the C ABI.
public sealed record CoreErrorBody(string Kind, string Message);
