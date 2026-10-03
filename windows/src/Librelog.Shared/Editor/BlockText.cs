using Librelog.Core;

namespace Librelog.Editor;

/// The bridge between the core's block document (doc.rs) and the editor's
/// document. A port of apple/App/Editor/BlockText.swift: one block = one
/// paragraph, list markers are real characters flagged Marker, and mark
/// offsets are UTF-16 on both sides.
public static class BlockText
{
    private static readonly (Inline Flag, MarkKind Kind)[] FlagKinds =
    [
        (Inline.Bold, MarkKind.Bold), (Inline.Italic, MarkKind.Italic), (Inline.Underline, MarkKind.Underline),
        (Inline.Strike, MarkKind.Strike), (Inline.Highlight, MarkKind.Highlight), (Inline.Code, MarkKind.Code),
    ];

    // --- Blocks → document ------------------------------------------------------

    public static EditorDocument ToDocument(IReadOnlyList<Block> blocks)
    {
        var doc = new EditorDocument();
        var number = 0;
        BlockKind? previous = null;
        var list = blocks.Count == 0 ? [new Block(BlockKind.Paragraph, "")] : blocks;
        for (var i = 0; i < list.Count; i++)
        {
            var block = list[i];
            number = block.Kind == BlockKind.Numbered ? (previous == BlockKind.Numbered ? number + 1 : 1) : 0;
            var baseStyle = new CharStyle(Encode(block.Kind), block.Indent);

            if (MarkerText(block.Kind, number) is { } m) doc.Insert(doc.Length, m, baseStyle with { Marker = true });

            var start = doc.Length;
            doc.Insert(doc.Length, block.Text, baseStyle);
            foreach (var mark in block.Marks)
            {
                var s = start + (int)mark.Start;
                var len = (int)mark.End - (int)mark.Start;
                if (len <= 0 || s + len > doc.Length) continue;
                doc.Restyle(s, len, c => mark.Kind.T switch
                {
                    "link" => c with { Link = mark.Kind.Url },
                    "entry_link" => c with { EntryLink = mark.Kind.EntryId },
                    var t => c.With(FlagOf(t), true),
                });
            }
            if (i < list.Count - 1) doc.Insert(doc.Length, "\n", baseStyle);
            previous = block.Kind;
        }
        return doc;
    }

    public static string? MarkerText(BlockKind kind, int number) => kind.T switch
    {
        "bullet" => "• ",
        "numbered" => $"{Math.Max(number, 1)}. ",
        "check" => kind.Checked == true ? "☑ " : "☐ ",
        _ => null,
    };

    // --- Document → blocks ------------------------------------------------------

    public static List<Block> ToBlocks(EditorDocument doc)
    {
        var output = new List<Block>();
        var location = 0;
        do
        {
            var para = doc.ParagraphAt(location);
            output.Add(BlockAt(doc, para));
            location = para.Start + para.Length;
        } while (location < doc.Length);
        // A trailing newline means an empty last paragraph. It has no
        // characters to carry a style, so it's always plain.
        if (doc.Length > 0 && doc[doc.Length - 1] == '\n') output.Add(new Block(BlockKind.Paragraph, ""));
        return output;
    }

    private static Block BlockAt(EditorDocument doc, (int Start, int Length) para)
    {
        var (start, length) = para;
        if (length > 0 && doc[start + length - 1] == '\n') length--;

        var first = para.Length > 0 ? doc.StyleAt(para.Start) : new CharStyle();
        var kind = Decode(first.Kind);

        var contentStart = start;
        while (contentStart < start + length && doc.StyleAt(contentStart).Marker) contentStart++;
        var contentLength = start + length - contentStart;
        var text = doc.Substring(contentStart, contentLength);

        var marks = new List<Mark>();
        if (contentLength > 0)
        {
            foreach (var (flag, mk) in FlagKinds)
                foreach (var (s, l) in Spans(doc, contentStart, contentLength, c => c.Has(flag) ? "" : null))
                    marks.Add(new Mark((uint)(s - contentStart), (uint)(s + l - contentStart), mk));
            foreach (var (s, l, url) in ValueSpans(doc, contentStart, contentLength, c => c.Link))
                marks.Add(new Mark((uint)(s - contentStart), (uint)(s + l - contentStart), MarkKind.Link(url)));
            foreach (var (s, l, id) in ValueSpans(doc, contentStart, contentLength, c => c.EntryLink))
                marks.Add(new Mark((uint)(s - contentStart), (uint)(s + l - contentStart), MarkKind.EntryLink(id)));
        }
        // Same order as the Apple editor: by start, then end.
        marks = [.. marks.OrderBy(m => m.Start).ThenBy(m => m.End)];
        return new Block(kind, text, marks, (byte)Math.Clamp(first.Indent, 0, 255));
    }

    private static IEnumerable<(int Start, int Length)> Spans(EditorDocument doc, int start, int length, Func<CharStyle, string?> value) =>
        ValueSpans(doc, start, length, value).Select(v => (v.Start, v.Length));

    private static IEnumerable<(int Start, int Length, string Value)> ValueSpans(
        EditorDocument doc, int start, int length, Func<CharStyle, string?> value)
    {
        var i = start;
        var end = start + length;
        while (i < end)
        {
            var v = value(doc.StyleAt(i));
            if (v is null) { i++; continue; }
            var j = i + 1;
            while (j < end && value(doc.StyleAt(j)) == v) j++;
            yield return (i, j - i, v);
            i = j;
        }
    }

    private static Inline FlagOf(string markType) => markType switch
    {
        "bold" => Inline.Bold,
        "italic" => Inline.Italic,
        "underline" => Inline.Underline,
        "strike" => Inline.Strike,
        "highlight" => Inline.Highlight,
        "code" => Inline.Code,
        _ => Inline.None,
    };

    // --- Kinds as short codes (the same ones the Apple editor uses) -------------

    public static string Encode(BlockKind k) => k.T switch
    {
        "heading" => $"h{k.Level ?? 1}",
        "bullet" => "bullet",
        "numbered" => "numbered",
        "check" => k.Checked == true ? "check1" : "check0",
        "quote" => "quote",
        "code" => "code",
        "rule" => "rule",
        "image" => $"image:{k.AttachmentId}",
        _ => "p",
    };

    public static BlockKind Decode(string? s) => s switch
    {
        "h1" => BlockKind.Heading(1),
        "h2" => BlockKind.Heading(2),
        "h3" => BlockKind.Heading(3),
        "bullet" => BlockKind.Bullet,
        "numbered" => BlockKind.Numbered,
        "check0" => BlockKind.Check(false),
        "check1" => BlockKind.Check(true),
        "quote" => BlockKind.Quote,
        "code" => BlockKind.Code,
        "rule" => BlockKind.Rule,
        not null when s.StartsWith("image:", StringComparison.Ordinal) => BlockKind.Image(s[6..]),
        _ => BlockKind.Paragraph,
    };
}
