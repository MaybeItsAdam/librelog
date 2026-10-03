package uk.co.maybeitssoftware.librelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.maybeitssoftware.librelog.editor.BlockText
import uniffi.librelog_core.Block
import uniffi.librelog_core.BlockKind
import uniffi.librelog_core.Mark
import uniffi.librelog_core.MarkKind

/**
 * The editor must save exactly what it loaded: blocks → spannable → blocks is
 * the identity, marks and all. Same cases as apple/Tests/BlockTextTests.swift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BlockTextTest {
    private fun block(kind: BlockKind, text: String, marks: List<Mark> = emptyList(), indent: Int = 0) =
        Block(kind, text, marks, indent.toUByte())

    @Test
    fun roundTripsEveryKindAndMark() {
        val doc = listOf(
            block(BlockKind.Heading(2u), "Morning"),
            block(
                BlockKind.Paragraph, "Ran 5k 😀 by the canal",
                listOf(
                    Mark(0u, 3u, MarkKind.Bold),
                    Mark(7u, 9u, MarkKind.Italic),
                    Mark(13u, 22u, MarkKind.Link("https://example.com")),
                ),
            ),
            block(BlockKind.Bullet, "eggs"),
            block(BlockKind.Bullet, "nested", indent = 1),
            block(BlockKind.Numbered, "first"),
            block(BlockKind.Numbered, "second"),
            block(BlockKind.Check(true), "done"),
            block(BlockKind.Check(false), "todo"),
            block(BlockKind.Quote, "a quote"),
            block(BlockKind.Code, "let x = 1"),
            block(BlockKind.Paragraph, ""),
        )
        assertEquals(doc, BlockText.blocks(BlockText.spannable(doc)))
    }

    @Test
    fun numberedMarkersCountUp() {
        val s = BlockText.spannable(
            listOf(
                block(BlockKind.Numbered, "a"),
                block(BlockKind.Numbered, "b"),
                block(BlockKind.Paragraph, "x"),
                block(BlockKind.Numbered, "c"),
            ),
        )
        val lines = s.toString().split("\n")
        assertTrue(lines[0].startsWith("1."))
        assertTrue(lines[1].startsWith("2."))
        assertTrue(lines[3].startsWith("1."))
    }

    @Test
    fun emptyDocumentIsOneEmptyParagraph() {
        assertEquals(listOf(block(BlockKind.Paragraph, "")), BlockText.blocks(BlockText.spannable(emptyList())))
    }

    @Test
    fun normalizingKeepsEachParagraphsOwnKind() {
        val doc = listOf(block(BlockKind.Heading(1u), "Title"), block(BlockKind.Bullet, "item"), block(BlockKind.Paragraph, "after"))
        val s = BlockText.spannable(doc)
        // Typing at a boundary stretches inclusive spans; normalizing undoes it.
        s.insert(5, "!")
        BlockText.normalizeKinds(s)
        val back = BlockText.blocks(s)
        assertEquals(listOf(BlockKind.Heading(1u), BlockKind.Bullet, BlockKind.Paragraph), back.map { it.kind })
        assertEquals("Title!", back[0].text)
    }
}
