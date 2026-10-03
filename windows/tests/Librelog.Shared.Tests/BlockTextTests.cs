using Librelog.Core;
using Librelog.Editor;

namespace Librelog.Tests;

/// The editor must save exactly what it loaded: blocks → document → blocks
/// is the identity, marks and all. Same cases as apple/Tests/BlockTextTests.swift.
public class BlockTextTests
{
    private static Block B(BlockKind kind, string text, Mark[]? marks = null, byte indent = 0) =>
        new(kind, text, marks ?? [], indent);

    [Fact]
    public void RoundTripsEveryKindAndMark()
    {
        Block[] doc =
        [
            B(BlockKind.Heading(2), "Morning"),
            B(BlockKind.Paragraph, "Ran 5k 😀 by the canal",
            [
                new Mark(0, 3, MarkKind.Bold),
                new Mark(7, 9, MarkKind.Italic),
                new Mark(13, 22, MarkKind.Link("https://example.com")),
            ]),
            B(BlockKind.Bullet, "eggs"),
            B(BlockKind.Bullet, "nested", indent: 1),
            B(BlockKind.Numbered, "first"),
            B(BlockKind.Numbered, "second"),
            B(BlockKind.Check(true), "done"),
            B(BlockKind.Check(false), "todo"),
            B(BlockKind.Quote, "a quote"),
            B(BlockKind.Code, "let x = 1"),
            B(BlockKind.Paragraph, ""),
        ];
        Assert.Equal(doc, BlockText.ToBlocks(BlockText.ToDocument(doc)));
    }

    [Fact]
    public void EntryLinksAndOverlappingMarksRoundTrip()
    {
        Block[] doc =
        [
            B(BlockKind.Paragraph, "see yesterday, bold italic",
            [
                new Mark(4, 13, MarkKind.EntryLink("e1")),
                new Mark(15, 26, MarkKind.Bold),
                new Mark(20, 26, MarkKind.Italic),
            ]),
        ];
        Assert.Equal(doc, BlockText.ToBlocks(BlockText.ToDocument(doc)));
    }

    [Fact]
    public void NumberedMarkersCountUp()
    {
        var doc = BlockText.ToDocument(
        [
            B(BlockKind.Numbered, "a"), B(BlockKind.Numbered, "b"), B(BlockKind.Paragraph, "x"), B(BlockKind.Numbered, "c"),
        ]);
        var lines = doc.Text.Split('\n');
        Assert.StartsWith("1.", lines[0]);
        Assert.StartsWith("2.", lines[1]);
        Assert.StartsWith("1.", lines[3]);
    }

    [Fact]
    public void EmptyDocumentIsOneEmptyParagraph()
    {
        Assert.Equal([B(BlockKind.Paragraph, "")], BlockText.ToBlocks(BlockText.ToDocument([])));
    }

    [Fact]
    public void KindCodesMatchTheAppleEditor()
    {
        foreach (var kind in new[]
                 {
                     BlockKind.Paragraph, BlockKind.Heading(1), BlockKind.Heading(3), BlockKind.Bullet, BlockKind.Numbered,
                     BlockKind.Check(false), BlockKind.Check(true), BlockKind.Quote, BlockKind.Code, BlockKind.Rule,
                     BlockKind.Image("a1"),
                 })
        {
            Assert.Equal(kind, BlockText.Decode(BlockText.Encode(kind)));
        }
        Assert.Equal("check1", BlockText.Encode(BlockKind.Check(true)));
        Assert.Equal("h2", BlockText.Encode(BlockKind.Heading(2)));
    }

    [Fact]
    public void AppearanceFollowsTheHouseStyle()
    {
        var heading = Appearance.Text(new CharStyle("h1"));
        Assert.True(heading.Bold);
        Assert.Equal(24, heading.Size);
        Assert.Equal(Appearance.Serif, heading.Font);

        var code = Appearance.Text(new CharStyle("p", Flags: Inline.Code));
        Assert.Equal(Appearance.Mono, code.Font);
        Assert.Equal(ColorRole.CodeWell, code.Background);

        Assert.True(Appearance.Text(new CharStyle("check1")).Strike);
        Assert.False(Appearance.Text(new CharStyle("check1", Marker: true)).Strike);
        Assert.Equal(ColorRole.Muted, Appearance.Text(new CharStyle("bullet", Marker: true)).Foreground);
    }
}
