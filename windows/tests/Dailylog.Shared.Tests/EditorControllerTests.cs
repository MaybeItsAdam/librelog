using Dailylog.Core;
using Dailylog.Editor;

namespace Dailylog.Tests;

/// Editing behaviour, driven the way the Windows view drives it: keys and
/// text-change diffs. Shortcuts come from the real core.
public class EditorControllerTests
{
    private static EditorController Editor(params Block[] blocks)
    {
        var e = new EditorController();
        e.Load(blocks.Length == 0 ? [new Block(BlockKind.Paragraph, "")] : blocks);
        return e;
    }

    /// Types text at the caret, as RichEditBox would and the view would report.
    private static void Type(EditorController e, string text)
    {
        foreach (var ch in text)
        {
            if (ch == '\n') e.Return();
            else e.ApplyEdit(e.Selection.Start, e.Selection.Length, ch.ToString());
        }
    }

    private static string Kinds(EditorController e) =>
        string.Join(",", e.Blocks().Select(b => BlockText.Encode(b.Kind) + (b.Indent > 0 ? $"+{b.Indent}" : "")));

    private static string Texts(EditorController e) => string.Join("|", e.Blocks().Select(b => b.Text));

    [Fact]
    public void MarkdownShortcutsTurnParagraphsIntoBlocks()
    {
        var e = Editor();
        // A second Return ends a list by turning the empty item back into a
        // paragraph, which the next shortcut then converts in place.
        Type(e, "# Title\n- milk\neggs\n\n1. one\ntwo\n\n[ ] call mum\n");
        Assert.Equal("h1,bullet,bullet,numbered,numbered,check0,check0", Kinds(e));
        Assert.Equal("Title|milk|eggs|one|two|call mum|", Texts(e));
    }

    [Fact]
    public void ReturnOnAnEmptyItemEndsTheList()
    {
        var e = Editor(new Block(BlockKind.Bullet, "one"));
        Type(e, "\n\nafter");
        Assert.Equal("bullet,p", Kinds(e));
        Assert.Equal("one|after", Texts(e));
    }

    [Fact]
    public void ChecklistsContinueUnchecked()
    {
        var e = Editor(new Block(BlockKind.Check(true), "done"));
        Type(e, "\nnext");
        Assert.Equal("check1,check0", Kinds(e));
    }

    [Fact]
    public void ReturnInTheMiddleOfAnItemSplitsIt()
    {
        var e = Editor(new Block(BlockKind.Bullet, "milkeggs"));
        var markerLength = BlockText.MarkerText(BlockKind.Bullet, 1)!.Length;
        e.Select(markerLength + 4, 0);
        e.Return();
        Assert.Equal("bullet,bullet", Kinds(e));
        Assert.Equal("milk|eggs", Texts(e));
    }

    [Fact]
    public void BackspaceAtTheStartOfAnItemDropsTheList()
    {
        var e = Editor(new Block(BlockKind.Bullet, "milk"));
        e.Select(BlockText.MarkerText(BlockKind.Bullet, 1)!.Length, 0);
        Assert.True(e.Backspace());
        Assert.Equal("p", Kinds(e));
        Assert.Equal("milk", Texts(e));
        Assert.False(e.Backspace()); // now an ordinary backspace for the text box
    }

    [Fact]
    public void TabNestsListItemsOnly()
    {
        var e = Editor(new Block(BlockKind.Bullet, "a"), new Block(BlockKind.Paragraph, "b"));
        e.Select(2, 0);
        Assert.True(e.Tab(outdent: false));
        Assert.True(e.Tab(outdent: false));
        Assert.True(e.Tab(outdent: true));
        Assert.Equal("bullet+1,p", Kinds(e));
        e.Select(e.Document.Length, 0);
        Assert.False(e.Tab(outdent: false));
    }

    [Fact]
    public void NumbersRenumberWhenAnItemIsInsertedAbove()
    {
        var e = Editor(new Block(BlockKind.Numbered, "a"), new Block(BlockKind.Numbered, "b"));
        e.Select(BlockText.MarkerText(BlockKind.Numbered, 1)!.Length + 1, 0);
        e.Return();
        Type(e, "new");
        Assert.Equal("1. a\n2. new\n3. b", e.Document.Text);
    }

    [Fact]
    public void BoldOnASelectionAndForWhatYouTypeNext()
    {
        var e = Editor(new Block(BlockKind.Paragraph, "hello world"));
        e.Select(0, 5);
        e.Toggle(Inline.Bold);
        Assert.Equal([new Mark(0, 5, MarkKind.Bold)], e.Blocks()[0].Marks);

        e.Select(e.Document.Length, 0);
        e.Toggle(Inline.Italic);
        Assert.True(e.IsActive(Inline.Italic));
        Type(e, "!!");
        Assert.Contains(new Mark(11, 13, MarkKind.Italic), e.Blocks()[0].Marks);
    }

    [Fact]
    public void TypingAfterBoldTextStaysBold()
    {
        var e = Editor(new Block(BlockKind.Paragraph, "ab", [new Mark(0, 2, MarkKind.Bold)]));
        e.Select(2, 0);
        Type(e, "c");
        Assert.Equal([new Mark(0, 3, MarkKind.Bold)], e.Blocks()[0].Marks);
    }

    [Fact]
    public void FormatBarSetsAndClearsKinds()
    {
        var e = Editor(new Block(BlockKind.Paragraph, "one"), new Block(BlockKind.Paragraph, "two"));
        e.Select(0, e.Document.Length);
        e.SetKind(BlockKind.Numbered);
        Assert.Equal("numbered,numbered", Kinds(e));
        Assert.Equal("one|two", Texts(e));
        e.Select(0, 0);
        e.SetKind(BlockKind.Numbered);
        Assert.Equal("p,numbered", Kinds(e));
    }

    [Fact]
    public void ClickingACheckboxTicksIt()
    {
        var e = Editor(new Block(BlockKind.Check(false), "task"));
        Assert.True(e.ToggleCheck(0));
        Assert.Equal("check1", Kinds(e));
        Assert.False(e.ToggleCheck(3)); // text, not the marker
    }

    [Fact]
    public void TheCaretSkipsListMarkers()
    {
        var e = Editor(new Block(BlockKind.Bullet, "x"));
        e.Select(0, 0);
        Assert.Equal(BlockText.MarkerText(BlockKind.Bullet, 1)!.Length, e.Selection.Start);
    }

    [Theory]
    [InlineData("abc", "abXc", 3, 2, 0, "X")]
    [InlineData("aa", "aaa", 3, 2, 0, "a")]
    [InlineData("hello", "help", 4, 3, 2, "p")]
    [InlineData("abc", "ac", 1, 1, 1, "")]
    [InlineData("", "pasted", 6, 0, 0, "pasted")]
    public void TextDiffFindsTheEdit(string before, string after, int caret, int start, int removed, string inserted)
    {
        Assert.Equal((start, removed, inserted), TextDiff.Compute(before, after, caret));
    }
}
