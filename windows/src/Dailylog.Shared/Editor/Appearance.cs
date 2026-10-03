using Dailylog.Core;

namespace Dailylog.Editor;

/// Colour roles from the house palette (SPEC.md §14). The view maps each to a
/// theme brush, so light and dark flip without the document knowing.
public enum ColorRole
{
    Ink,
    Muted,
    Azure,
    HighlightAmber,
    CodeWell,
}

public sealed record TextAppearance(
    string Font, double Size, bool Bold, bool Italic, bool Underline, bool Strike,
    ColorRole Foreground, ColorRole? Background);

public sealed record ParagraphAppearance(double LeftIndent, double FirstLineIndent, double SpaceBefore, double SpaceAfter);

/// How the editor draws each character and paragraph. The same rules as the
/// Apple editor's BlockText.restyle: Arvo for prose, Geist Mono for code,
/// headings bold and larger, quotes italic and muted, ticked items struck.
public static class Appearance
{
    public const string Serif = "Arvo";
    public const string Mono = "Geist Mono";
    public const double BodySize = 16;

    public static TextAppearance Text(CharStyle c)
    {
        var kind = BlockText.Decode(c.Kind);
        var code = c.Has(Inline.Code) || kind.T == "code";
        var size = kind.T == "heading" ? kind.Level switch { 1 => 24, 2 => 20, _ => 18 } : BodySize;
        var bold = c.Has(Inline.Bold) || kind.T == "heading";
        var italic = c.Has(Inline.Italic) || kind.T == "quote";
        var foreground = c.EntryLink is not null ? ColorRole.Azure
            : c.Marker || kind.T == "quote" ? ColorRole.Muted
            : ColorRole.Ink;
        ColorRole? background = c.Has(Inline.Highlight) ? ColorRole.HighlightAmber : code ? ColorRole.CodeWell : null;
        return new TextAppearance(
            code ? Mono : Serif,
            code ? BodySize - 2 : size,
            bold && !code,
            italic && !code,
            c.Has(Inline.Underline) || c.Link is not null || c.EntryLink is not null,
            c.Has(Inline.Strike) || (kind == BlockKind.Check(true) && !c.Marker),
            foreground,
            background);
    }

    public static ParagraphAppearance Paragraph(BlockKind kind, int indent) => kind.T switch
    {
        "heading" => new(0, 0, 10, 4),
        "bullet" or "numbered" or "check" => new(indent * 22 + 22, -22, 0, 2), // wrapped lines align with the text
        "quote" => new(16, 0, 0, 6),
        _ => new(0, 0, 0, 6),
    };
}
