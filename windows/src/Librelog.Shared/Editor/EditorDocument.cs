using System.Text;

namespace Librelog.Editor;

[Flags]
public enum Inline
{
    None = 0,
    Bold = 1,
    Italic = 2,
    Underline = 4,
    Strike = 8,
    Highlight = 16,
    Code = 32,
}

/// What one character means. The Windows counterpart of the Apple editor's
/// `dl.*` attributes: RichEditBox can't carry custom attributes, so the
/// document lives here and RichEditBox only displays it.
///
/// Kind uses the same short codes as BlockText.swift ("p", "h2", "bullet",
/// "check1", …). Marker is true for list-marker characters ("• ", "1. ",
/// "☐ "), which are dropped on the way back to blocks.
public readonly record struct CharStyle(
    string Kind = "p",
    int Indent = 0,
    bool Marker = false,
    Inline Flags = Inline.None,
    string? Link = null,
    string? EntryLink = null)
{
    public CharStyle With(Inline flag, bool on) => this with { Flags = on ? Flags | flag : Flags & ~flag };
    public bool Has(Inline flag) => (Flags & flag) != 0;
}

/// Plain text plus one CharStyle per UTF-16 code unit. Offsets are UTF-16,
/// as in .NET strings, RichEditBox and the core's marks.
public sealed class EditorDocument
{
    private readonly StringBuilder text = new();
    private readonly List<CharStyle> styles = [];

    public int Length => text.Length;
    public string Text => text.ToString();
    public char this[int i] => text[i];
    public CharStyle StyleAt(int i) => styles[i];

    public string Substring(int start, int length) => text.ToString(start, length);

    public void Insert(int at, string s, CharStyle style)
    {
        text.Insert(at, s);
        styles.InsertRange(at, Enumerable.Repeat(style, s.Length));
    }

    public void Insert(int at, string s, Func<int, CharStyle> styleOf)
    {
        text.Insert(at, s);
        styles.InsertRange(at, Enumerable.Range(0, s.Length).Select(styleOf));
    }

    public void Delete(int start, int length)
    {
        if (length <= 0) return;
        text.Remove(start, length);
        styles.RemoveRange(start, length);
    }

    public void Clear()
    {
        text.Clear();
        styles.Clear();
    }

    public void Restyle(int start, int length, Func<CharStyle, CharStyle> f)
    {
        for (var i = start; i < start + length && i < styles.Count; i++) styles[i] = f(styles[i]);
    }

    /// Like NSString.paragraphRange(for:): from just after the previous '\n'
    /// up to and including the next one. At the very end after a trailing
    /// newline it's the empty last paragraph (Length, 0).
    public (int Start, int Length) ParagraphAt(int index)
    {
        index = Math.Clamp(index, 0, text.Length);
        var start = index;
        while (start > 0 && text[start - 1] != '\n') start--;
        var end = index;
        while (end < text.Length && text[end] != '\n') end++;
        if (end < text.Length) end++; // include the newline
        return (start, end - start);
    }

    /// Start offsets of the paragraphs a selection covers. A selection that
    /// ends just after a newline doesn't reach into the next paragraph.
    public List<int> ParagraphStarts(int start, int length)
    {
        var result = new List<int>();
        var p = ParagraphAt(start).Start;
        var end = start + length;
        while (true)
        {
            result.Add(p);
            var (ps, pl) = ParagraphAt(p);
            var next = ps + pl;
            if (pl == 0 || next >= end) break;
            p = next;
        }
        return result;
    }

    /// Contiguous runs of identical style over [start, start + length).
    public IEnumerable<(int Start, int Length, CharStyle Style)> Runs(int start, int length)
    {
        var end = Math.Min(start + length, styles.Count);
        var i = start;
        while (i < end)
        {
            var s = styles[i];
            var j = i + 1;
            while (j < end && styles[j] == s) j++;
            yield return (i, j - i, s);
            i = j;
        }
    }
}
