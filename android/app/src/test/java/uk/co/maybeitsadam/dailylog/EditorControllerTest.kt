package uk.co.maybeitsadam.dailylog

import android.text.Editable
import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.maybeitsadam.dailylog.editor.EditorController
import uk.co.maybeitsadam.dailylog.editor.EditorHost
import uniffi.dailylog_core.Block
import uniffi.dailylog_core.BlockKind
import uniffi.dailylog_core.Mark
import uniffi.dailylog_core.MarkKind

/** Typing behaviour, driven the way a TextWatcher would drive it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EditorControllerTest {
    private class FakeHost : EditorHost {
        override val buffer: Editable = SpannableStringBuilder()
        override var caretStart = 0
        override var caretEnd = 0
        override fun select(start: Int, end: Int) {
            caretStart = start
            caretEnd = end
        }
    }

    private lateinit var host: FakeHost
    private lateinit var c: EditorController

    @Before
    fun setUp() {
        host = FakeHost()
        c = EditorController(style = null).also { it.host = host }
        c.load(emptyList())
    }

    private fun type(s: String) {
        for (ch in s) {
            val at = host.caretStart
            c.beforeChange(at, host.caretEnd - at, 1)
            host.buffer.replace(at, host.caretEnd, ch.toString())
            host.select(at + 1)
            c.afterChange()
        }
    }

    private fun backspace() {
        val at = host.caretStart
        c.beforeChange(at - 1, 1, 0)
        host.buffer.delete(at - 1, at)
        host.select(at - 1)
        c.afterChange()
    }

    private fun p(text: String) = Block(BlockKind.Paragraph, text, emptyList(), 0u)

    @Test
    fun dashSpaceStartsAListThatContinuesAndEndsOnAnEmptyItem() {
        type("- eggs\nmilk\n\nafter")
        assertEquals(
            listOf(
                Block(BlockKind.Bullet, "eggs", emptyList(), 0u),
                Block(BlockKind.Bullet, "milk", emptyList(), 0u),
                p("after"),
            ),
            c.blocks(),
        )
    }

    @Test
    fun numberedItemsCountUp() {
        type("1. one\ntwo\nthree")
        val lines = host.buffer.toString().split("\n")
        assertTrue(lines[0].startsWith("1."))
        assertTrue(lines[1].startsWith("2."))
        assertTrue(lines[2].startsWith("3."))
        assertEquals(listOf("one", "two", "three"), c.blocks().map { it.text })
    }

    @Test
    fun backspaceIntoTheMarkerDropsTheList() {
        type("- item")
        repeat(4) { backspace() } // "item"
        backspace() // into the marker
        assertEquals(listOf(p("")), c.blocks())
        type("plain")
        assertEquals(listOf(p("plain")), c.blocks())
    }

    @Test
    fun headingsEndOnReturn() {
        type("# Title\nbody")
        assertEquals(listOf(Block(BlockKind.Heading(1u), "Title", emptyList(), 0u), p("body")), c.blocks())
    }

    @Test
    fun boldToggledWithNothingSelectedAppliesToWhatsTypedNext() {
        type("a ")
        c.toggle(MarkKind.Bold)
        type("bold")
        c.toggle(MarkKind.Bold)
        type(" b")
        assertEquals(listOf(Block(BlockKind.Paragraph, "a bold b", listOf(Mark(2u, 6u, MarkKind.Bold)), 0u)), c.blocks())
    }

    @Test
    fun tappingACheckboxFlipsIt() {
        type("[ ] task")
        assertEquals(BlockKind.Check(false), c.blocks()[0].kind)
        assertTrue(c.toggleCheck(0))
        assertEquals(Block(BlockKind.Check(true), "task", emptyList(), 0u), c.blocks()[0])
    }

    @Test
    fun toolbarKindAppliesToTheCaretParagraphAndTogglesBack() {
        type("note")
        c.setKind(BlockKind.Quote)
        assertEquals(BlockKind.Quote, c.blocks()[0].kind)
        c.setKind(BlockKind.Quote)
        assertEquals(BlockKind.Paragraph, c.blocks()[0].kind)
        c.setKind(BlockKind.Bullet)
        c.indent(1)
        assertEquals(Block(BlockKind.Bullet, "note", emptyList(), 1u), c.blocks()[0])
    }
}
