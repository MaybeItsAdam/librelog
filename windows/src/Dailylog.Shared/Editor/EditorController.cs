using Dailylog.Core;

namespace Dailylog.Editor;

/// Editing behaviour (SPEC.md §6.2), a port of the Apple app's
/// EditorController: Markdown shortcuts, lists that continue on Return,
/// checkboxes, indenting and the format bar.
///
/// It owns the EditorDocument. The view (RichEditBox on Windows) reports
/// keys and text changes here and redraws from the document; it never holds
/// meaning of its own, so what's on screen is always what Blocks() saves.
public sealed class EditorController(Func<string, Shortcut?>? shortcut = null)
{
    private readonly Func<string, Shortcut?> shortcut = shortcut ?? CoreFunctions.MarkdownShortcut;

    public EditorDocument Document { get; private set; } = new();

    /// UTF-16 offsets into Document.
    public (int Start, int Length) Selection { get; set; }

    /// The style the next typed characters get.
    public CharStyle Typing { get; private set; }

    /// Raised after every edit; the screen debounces the save.
    public event Action? Changed;

    /// Raised when the caret moves or the format state changes.
    public event Action? SelectionChanged;

    public void Load(IReadOnlyList<Block> blocks)
    {
        Document = BlockText.ToDocument(blocks);
        Selection = (Document.Length, 0);
        SyncTyping();
    }

    public List<Block> Blocks() => BlockText.ToBlocks(Document);

    // --- View callbacks ---------------------------------------------------------

    /// Return pressed. The controller always performs it, so the view should
    /// mark the key handled and redraw.
    public void Return()
    {
        var (selStart, selLength) = Selection;
        var para = Document.ParagraphAt(selStart);
        var kind = KindOf(para);

        if (kind.IsList)
        {
            if (ContentText(para).Length == 0)
            {
                // Return on an empty item ends the list.
                SetKindAt(BlockKind.Paragraph, para.Start);
                Selection = (para.Start, 0);
                Notify();
                return;
            }
            var next = kind.T == "check" ? BlockKind.Check(false) : kind;
            InsertParagraph(selStart, selLength, next, IndentOf(para));
            return;
        }
        if (kind.T == "heading")
        {
            InsertParagraph(selStart, selLength, BlockKind.Paragraph, 0);
            return;
        }
        InsertParagraph(selStart, selLength, kind, IndentOf(para), carryMarker: false);
    }

    /// Backspace pressed. Returns true if the controller handled it (at the
    /// start of a list item's text, it turns the item back into a paragraph);
    /// otherwise the view lets the text box delete and reports the change.
    public bool Backspace()
    {
        var (selStart, selLength) = Selection;
        if (selLength != 0) return false;
        var para = Document.ParagraphAt(selStart);
        var markerEnd = MarkerEnd(para);
        if (markerEnd <= para.Start || selStart != markerEnd) return false;
        SetKindAt(BlockKind.Paragraph, para.Start);
        Selection = (para.Start, 0);
        Notify();
        return true;
    }

    /// Tab / Shift+Tab. Returns true if it nested a list item; otherwise the
    /// view should let focus move on.
    public bool Tab(bool outdent) => Indent(outdent ? -1 : 1);

    /// A text change the view made itself (typing, paste, delete, cut):
    /// `removed` UTF-16 units at `start` replaced by `inserted`.
    public void ApplyEdit(int start, int removed, string inserted)
    {
        start = Math.Clamp(start, 0, Document.Length);
        removed = Math.Clamp(removed, 0, Document.Length - start);
        Document.Delete(start, removed);
        var typing = Typing;
        Document.Insert(start, inserted, typing);
        Selection = (start + inserted.Length, 0);

        var caret = Selection.Start;
        var para = Document.ParagraphAt(caret);
        var kind = KindOf(para);
        if (caret > para.Start && (kind.T == "paragraph" || kind.T == "bullet"))
        {
            var typed = Document.Substring(para.Start, caret - para.Start);
            if ((typed.EndsWith(' ') || typed == "---" || typed == "```")
                && shortcut(typed) is { } match && match.PrefixLen == typed.Length)
            {
                ApplyShortcut(match, para);
            }
        }
        Renumber();
        SyncTyping();
        Notify();
    }

    /// The caret moved. Keeps it out of list markers.
    public void Select(int start, int length)
    {
        start = Math.Clamp(start, 0, Document.Length);
        length = Math.Clamp(length, 0, Document.Length - start);
        if (length == 0 && start < Document.Length && Document.StyleAt(start).Marker)
        {
            var para = Document.ParagraphAt(start);
            start = MarkerEnd(para);
        }
        Selection = (start, length);
        SyncTyping();
        SelectionChanged?.Invoke();
    }

    // --- Format bar -------------------------------------------------------------

    public BlockKind CurrentKind => KindOf(Document.ParagraphAt(Selection.Start));

    public bool IsActive(Inline flag) =>
        Selection.Length == 0 ? Typing.Has(flag) : Document.StyleAt(Selection.Start).Has(flag);

    /// Bold, italic, … With no selection it changes what you type next.
    public void Toggle(Inline flag)
    {
        var (start, length) = Selection;
        if (length == 0)
        {
            Typing = Typing.With(flag, !Typing.Has(flag));
            SelectionChanged?.Invoke();
            return;
        }
        var on = !Document.StyleAt(start).Has(flag);
        Document.Restyle(start, length, c => c.Marker ? c : c.With(flag, on));
        Notify();
    }

    /// Turns the selected paragraphs into `kind`, or back to text if they
    /// already are.
    public void SetKind(BlockKind kind)
    {
        var target = CurrentKind == kind ? BlockKind.Paragraph : kind;
        var starts = Document.ParagraphStarts(Selection.Start, Selection.Length);
        var caretFromEnd = Document.Length - Selection.Start;
        for (var i = starts.Count - 1; i >= 0; i--) SetKindAt(target, starts[i]);
        Selection = (Math.Max(0, Document.Length - caretFromEnd), 0);
        Renumber();
        SyncTyping();
        Notify();
    }

    /// Nests or un-nests list items; other paragraphs don't indent.
    public bool Indent(int delta)
    {
        var para = Document.ParagraphAt(Selection.Start);
        if (!KindOf(para).IsList) return false;
        var level = Math.Clamp(IndentOf(para) + delta, 0, 4);
        Document.Restyle(para.Start, para.Length, c => c with { Indent = level });
        Typing = Typing with { Indent = level };
        Notify();
        return true;
    }

    /// Flips a checkbox when its marker is clicked. Returns true if it did.
    public bool ToggleCheck(int index)
    {
        if (index < 0 || index >= Document.Length || !Document.StyleAt(index).Marker) return false;
        var para = Document.ParagraphAt(index);
        var kind = KindOf(para);
        if (kind.T != "check") return false;
        var sel = Selection;
        SetKindAt(BlockKind.Check(kind.Checked != true), para.Start);
        Selection = sel;
        Notify();
        return true;
    }

    // --- Paragraph surgery ------------------------------------------------------

    private BlockKind KindOf((int Start, int Length) para) =>
        // An empty paragraph has no characters; ask the typing style.
        BlockText.Decode(para.Length == 0 || Document.Length == 0 ? Typing.Kind : Document.StyleAt(para.Start).Kind);

    private int IndentOf((int Start, int Length) para) =>
        para.Length == 0 ? Typing.Indent : Document.StyleAt(para.Start).Indent;

    private int MarkerEnd((int Start, int Length) para)
    {
        var i = para.Start;
        while (i < para.Start + para.Length && Document.StyleAt(i).Marker) i++;
        return i;
    }

    private string ContentText((int Start, int Length) para)
    {
        var start = MarkerEnd(para);
        var end = para.Start + para.Length;
        if (end > start && Document[end - 1] == '\n') end--;
        return end > start ? Document.Substring(start, end - start) : "";
    }

    /// Re-kinds the paragraph starting at `start`, swapping its list marker.
    private void SetKindAt(BlockKind kind, int start)
    {
        var para = Document.ParagraphAt(start);
        var indent = IndentOf(para);
        var markerLength = MarkerEnd(para) - para.Start;
        Document.Delete(para.Start, markerLength);
        var code = BlockText.Encode(kind);
        if (BlockText.MarkerText(kind, 1) is { } marker)
            Document.Insert(para.Start, marker, new CharStyle(code, indent, Marker: true));
        para = Document.ParagraphAt(para.Start);
        Document.Restyle(para.Start, para.Length, c => c with { Kind = code, Indent = indent });
        Typing = new CharStyle(code, indent);
    }

    private void InsertParagraph(int start, int length, BlockKind kind, int indent, bool carryMarker = true)
    {
        // The newline ends the current paragraph, so it takes that one's style.
        var current = Document.ParagraphAt(start);
        var endStyle = current.Length > 0 && start > current.Start
            ? Document.StyleAt(start - 1) with { Marker = false, Flags = Inline.None, Link = null, EntryLink = null }
            : new CharStyle(BlockText.Encode(KindOf(current)), IndentOf(current));
        Document.Delete(start, length);
        Document.Insert(start, "\n", endStyle);
        var caret = start + 1;
        var code = BlockText.Encode(kind);
        if (carryMarker && BlockText.MarkerText(kind, 1) is { } marker)
        {
            Document.Insert(caret, marker, new CharStyle(code, indent, Marker: true));
            caret += marker.Length;
        }
        // Text that followed the caret now starts the new paragraph.
        var rest = Document.ParagraphAt(caret);
        Document.Restyle(caret, rest.Start + rest.Length - caret, c => c with { Kind = code, Indent = indent });
        Selection = (caret, 0);
        Typing = new CharStyle(code, indent);
        Renumber();
        Notify();
    }

    private void ApplyShortcut(Shortcut match, (int Start, int Length) para)
    {
        Document.Delete(para.Start, (int)match.PrefixLen);
        SetKindAt(match.Kind, para.Start);
        Selection = (MarkerEnd(Document.ParagraphAt(para.Start)), 0);
    }

    /// Rewrites "1. 2. 3." markers after edits that could shift them.
    private void Renumber()
    {
        var number = 0;
        var location = 0;
        var (selStart, selLength) = Selection;
        while (location < Document.Length)
        {
            var para = Document.ParagraphAt(location);
            var markerEnd = MarkerEnd(para);
            if (KindOf(para).T == "numbered" && markerEnd > para.Start)
            {
                number++;
                var want = $"{number}. ";
                var have = Document.Substring(para.Start, markerEnd - para.Start);
                if (want != have)
                {
                    var style = Document.StyleAt(para.Start);
                    Document.Delete(para.Start, have.Length);
                    Document.Insert(para.Start, want, style);
                    if (para.Start < selStart) selStart += want.Length - have.Length;
                    para = Document.ParagraphAt(para.Start);
                }
            }
            else
            {
                number = 0;
            }
            var next = para.Start + para.Length;
            if (next <= location) break;
            location = next;
        }
        Selection = (Math.Clamp(selStart, 0, Document.Length), selLength);
    }

    private void SyncTyping()
    {
        var caret = Math.Min(Selection.Start, Document.Length);
        var para = Document.ParagraphAt(caret);
        var t = new CharStyle(BlockText.Encode(KindOf(para)), IndentOf(para));
        // Carry inline marks from the character before the caret, as editors do.
        if (caret > para.Start && caret > 0)
        {
            var before = Document.StyleAt(caret - 1);
            if (!before.Marker && Document[caret - 1] != '\n') t = t with { Flags = before.Flags };
        }
        Typing = t;
    }

    private void Notify()
    {
        Changed?.Invoke();
        SelectionChanged?.Invoke();
    }
}

/// Works out what a text box changed by comparing its text before and after.
public static class TextDiff
{
    /// `caret` is where the caret ended up; typing and pasting leave it at the
    /// end of what was inserted, which settles ambiguous runs ("aa" → "aaa").
    public static (int Start, int Removed, string Inserted) Compute(string before, string after, int caret)
    {
        caret = Math.Clamp(caret, 0, after.Length);
        var maxSuffix = Math.Min(after.Length - caret, before.Length);
        var suffix = 0;
        while (suffix < maxSuffix && before[^(suffix + 1)] == after[^(suffix + 1)]) suffix++;
        var maxPrefix = Math.Min(before.Length - suffix, after.Length - suffix);
        var prefix = 0;
        while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++;
        return (prefix, before.Length - suffix - prefix, after.Substring(prefix, after.Length - suffix - prefix));
    }
}
