package uk.co.maybeitssoftware.librelog.editor

import android.text.Editable
import android.text.Spanned
import uniffi.librelog_core.Block
import uniffi.librelog_core.BlockKind
import uniffi.librelog_core.MarkKind
import uniffi.librelog_core.markdownShortcut

/** What the controller needs from a text view. */
interface EditorHost {
    val buffer: Editable
    val caretStart: Int
    val caretEnd: Int
    fun select(start: Int, end: Int = start)
}

/**
 * Editing behaviour (SPEC.md §6.2): Markdown shortcuts, lists that continue on
 * Return, checkboxes, and the formatting toolbar. Mirrors
 * apple/App/Editor/EditorController.swift.
 *
 * Android has no "should change" hook, so the text view reports each edit
 * through [beforeChange] and [afterChange] (a TextWatcher), and the
 * controller reshapes the result: a Return after a list item gets a marker, a
 * backspace into a marker drops the list, and so on. Everything goes through
 * [BlockText]'s spans, so what's on screen is exactly what [blocks] saves.
 */
class EditorController(internal val style: EditorStyle?) {
    var host: EditorHost? = null
    /** Called after every edit; the screen debounces the save. */
    var onChange: (() -> Unit)? = null
    /** Called when the caret moves, so the toolbar can reflect the paragraph. */
    var onSelectionChange: (() -> Unit)? = null

    /**
     * The kind of an empty last paragraph, which has no characters to carry a
     * span. Applied to whatever is typed there first.
     */
    private var trailingKind: Pair<String, Int> = "p" to 0

    /** Marks switched on or off with nothing selected: apply to what's typed next. */
    private val typingOn = mutableSetOf<MarkKind>()
    private val typingOff = mutableSetOf<MarkKind>()
    private var expectedCaret = -1

    private var applying = false
    private var changeStart = 0
    private var changeInserted = 0
    private var deletedIntoMarker = false

    fun load(blocks: List<Block>) {
        val h = host ?: return
        applying = true
        h.buffer.replace(0, h.buffer.length, BlockText.spannable(blocks, style))
        trailingKind = "p" to 0
        applying = false
        h.select(h.buffer.length)
    }

    fun blocks(): List<Block> {
        val h = host ?: return emptyList()
        val out = BlockText.blocks(h.buffer).toMutableList()
        // The empty last paragraph's kind lives here, not in a span.
        if (out.isNotEmpty() && out.last().text.isEmpty() && (h.buffer.isEmpty() || h.buffer.last() == '\n')) {
            val (k, i) = trailingKind
            out[out.lastIndex] = out.last().copy(kind = BlockText.decode(k), indent = i.toUByte())
        }
        return out
    }

    // MARK: Text view callbacks

    fun beforeChange(start: Int, removed: Int, inserted: Int) {
        if (applying) return
        val h = host ?: return
        changeStart = start
        changeInserted = inserted
        // Backspace into a list marker: the edit removes one marker character.
        deletedIntoMarker = removed == 1 && inserted == 0 &&
            h.buffer.getSpans(start, start + 1, MarkerSpan::class.java).any {
                h.buffer.getSpanStart(it) <= start && h.buffer.getSpanEnd(it) > start
            }
    }

    fun afterChange() {
        if (applying) return
        val h = host ?: return
        val e = h.buffer
        applying = true
        try {
            val start = changeStart.coerceIn(0, e.length)
            val end = (changeStart + changeInserted).coerceIn(start, e.length)
            val inserted = e.subSequence(start, end).toString()
            val wasTrailing = changeInserted > 0 && end == e.length && (start == 0 || e[start - 1] == '\n')

            when {
                deletedIntoMarker -> dropList(BlockText.paragraphStart(e, start))
                inserted == "\n" -> handleReturn(start)
                else -> {
                    if (wasTrailing && inserted.isNotEmpty()) claimTrailing(start, end)
                    applyTyping(start, end)
                }
            }

            BlockText.normalizeKinds(e)
            if (inserted != "\n") shortcutAtCaret()
            repairMarkers()
            style?.let { BlockText.restyle(e, 0, e.length, it) }
            expectedCaret = h.caretStart
        } finally {
            applying = false
            deletedIntoMarker = false
        }
        onChange?.invoke()
        onSelectionChange?.invoke()
    }

    fun selectionChanged() {
        if (applying) return
        val h = host ?: return
        if (h.caretStart != expectedCaret) {
            typingOn.clear()
            typingOff.clear()
        }
        onSelectionChange?.invoke()
    }

    // MARK: Toolbar

    val currentKind: BlockKind
        get() {
            val h = host ?: return BlockText.decode(null)
            return kindAt(BlockText.paragraphStart(h.buffer, h.caretStart))
        }

    fun isActive(kind: MarkKind): Boolean {
        val h = host ?: return false
        val (s, e) = h.caretStart to h.caretEnd
        if (s == e) {
            if (kind in typingOn) return true
            if (kind in typingOff) return false
            return s > 0 && hasFlag(s - 1, s, kind)
        }
        return hasFlag(s, s + 1, kind)
    }

    /** Bold, italic, etc. With no selection it changes what you type next. */
    fun toggle(kind: MarkKind) {
        val h = host ?: return
        val (s, e) = h.caretStart to h.caretEnd
        if (s == e) {
            val on = !isActive(kind)
            typingOn.remove(kind)
            typingOff.remove(kind)
            (if (on) typingOn else typingOff) += kind
            expectedCaret = s
            onSelectionChange?.invoke()
            return
        }
        edit {
            if (hasFlag(s, s + 1, kind)) {
                BlockText.clear(h.buffer, s, e, FlagSpan::class.java, { it.kind == kind }) { FlagSpan(it.kind) }
            } else {
                h.buffer.setSpan(FlagSpan(kind), s, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
        }
        h.select(s, e)
    }

    /** Turns the selected paragraphs into [kind], or back to text if they already are. */
    fun setKind(kind: BlockKind) {
        val h = host ?: return
        val target = if (currentKind == kind) BlockKind.Paragraph else kind
        val e = h.buffer
        val starts = mutableListOf<Int>()
        var p = BlockText.paragraphStart(e, h.caretStart)
        val last = BlockText.paragraphStart(e, h.caretEnd)
        while (true) {
            starts += p
            if (p >= last) break
            val next = BlockText.paragraphEnd(e, p) + 1
            if (next > e.length || next <= p) break
            p = next
        }
        val caretFromEnd = e.length - h.caretStart
        edit {
            for (start in starts.reversed()) setKindAt(start, target)
        }
        h.select((e.length - caretFromEnd).coerceIn(0, e.length))
    }

    /** Nests or un-nests list items; other paragraphs don't indent. */
    fun indent(delta: Int) {
        val h = host ?: return
        val start = BlockText.paragraphStart(h.buffer, h.caretStart)
        val kind = kindAt(start)
        if (!BlockText.isList(kind)) return
        val now = BlockText.kindSpanAt(h.buffer, start)?.indent ?: 0
        val end = paragraphEndWithNewline(start)
        edit { BlockText.setKindSpan(h.buffer, start, end, kind, (now + delta).coerceIn(0, 4)) }
    }

    /** Flips a checkbox when its marker is tapped. Returns true if it was one. */
    fun toggleCheck(index: Int): Boolean {
        val h = host ?: return false
        if (index < 0 || index >= h.buffer.length) return false
        val onMarker = h.buffer.getSpans(index, index + 1, MarkerSpan::class.java).any {
            h.buffer.getSpanStart(it) <= index && h.buffer.getSpanEnd(it) > index
        }
        if (!onMarker) return false
        val start = BlockText.paragraphStart(h.buffer, index)
        val kind = kindAt(start) as? BlockKind.Check ?: return false
        val (s, e) = h.caretStart to h.caretEnd
        edit { setKindAt(start, BlockKind.Check(!kind.checked)) }
        h.select(s.coerceAtMost(h.buffer.length), e.coerceAtMost(h.buffer.length))
        return true
    }

    // MARK: Reshaping after an edit

    private fun handleReturn(newline: Int) {
        val h = host ?: return
        val e = h.buffer
        val paraStart = BlockText.paragraphStart(e, newline)
        val span = BlockText.kindSpanAt(e, paraStart)
        val kind = BlockText.decode(span?.kind)
        val indent = span?.indent ?: 0

        if (BlockText.isList(kind)) {
            val contentStart = markerEnd(paraStart) ?: paraStart
            if (newline <= contentStart) {
                // Return on an empty item ends the list.
                e.delete(newline, newline + 1)
                setKindAt(paraStart, BlockKind.Paragraph)
                h.select(paraStart)
                return
            }
            val next = if (kind is BlockKind.Check) BlockKind.Check(false) else kind
            val marker = BlockText.markerText(next, 1)!!
            e.insert(newline + 1, marker)
            e.setSpan(MarkerSpan(), newline + 1, newline + 1 + marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            BlockText.setKindSpan(e, paraStart, newline + 1, kind, indent)
            BlockText.setKindSpan(e, newline + 1, paragraphEndWithNewline(newline + 1), next, indent)
            h.select(newline + 1 + marker.length)
            return
        }

        // Headings end on Return; other kinds carry on into the new paragraph.
        val nextKind = if (kind is BlockKind.Heading) "p" else BlockText.encode(kind)
        BlockText.setKindSpan(e, paraStart, newline + 1, kind, indent)
        val nextStart = newline + 1
        if (nextStart == e.length) {
            trailingKind = nextKind to 0
        } else {
            BlockText.setKindSpan(e, nextStart, paragraphEndWithNewline(nextStart), BlockText.decode(nextKind), 0)
        }
        // Inline marks don't follow you onto the next line.
        for (flag in e.getSpans(newline, newline + 1, FlagSpan::class.java)) {
            if (e.getSpanEnd(flag) > newline) BlockText.clear(e, newline, e.length, FlagSpan::class.java, { it === flag }) { FlagSpan(it.kind) }
        }
    }

    /** The first characters typed into an empty last paragraph take its kind. */
    private fun claimTrailing(start: Int, end: Int) {
        val e = host?.buffer ?: return
        val (k, i) = trailingKind
        BlockText.setKindSpan(e, start, maxOf(end, BlockText.paragraphEnd(e, start)), BlockText.decode(k), i)
        trailingKind = "p" to 0
        if (BlockText.isList(BlockText.decode(k)) && markerEnd(start) == null) {
            val marker = BlockText.markerText(BlockText.decode(k), 1)!!
            e.insert(start, marker)
            e.setSpan(MarkerSpan(), start, start + marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applyTyping(start: Int, end: Int) {
        val e = host?.buffer ?: return
        if (end <= start) return
        for (k in typingOn) {
            BlockText.clear(e, start, end, FlagSpan::class.java, { it.kind == k }) { FlagSpan(it.kind) }
            e.setSpan(FlagSpan(k), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        for (k in typingOff) {
            BlockText.clear(e, start, end, FlagSpan::class.java, { it.kind == k }) { FlagSpan(it.kind) }
        }
    }

    private fun dropList(paraStart: Int) {
        val h = host ?: return
        setKindAt(paraStart, BlockKind.Paragraph)
        h.select(paraStart)
    }

    private fun shortcutAtCaret() {
        val h = host ?: return
        val e = h.buffer
        val caret = h.caretStart
        val paraStart = BlockText.paragraphStart(e, caret)
        if (caret <= paraStart) return
        val kind = kindAt(paraStart)
        if (kind != BlockKind.Paragraph && kind != BlockKind.Bullet) return
        val typed = e.subSequence(paraStart, caret).toString()
        if (!(typed.endsWith(" ") || typed == "---" || typed == "```")) return
        val match = markdownShortcut(typed) ?: return
        if (match.prefixLen.toInt() != typed.length) return
        e.delete(paraStart, paraStart + typed.length)
        setKindAt(paraStart, match.kind)
        h.select(markerEnd(paraStart) ?: paraStart)
    }

    /**
     * Keeps markers honest after any edit: list paragraphs start with exactly
     * their marker ("1. 2. 3." counted afresh), other paragraphs have none.
     */
    private fun repairMarkers() {
        val h = host ?: return
        val e = h.buffer
        var caret = h.caretStart
        var start = 0
        var number = 0
        while (start <= e.length) {
            val kind = kindAt(start)
            number = if (kind == BlockKind.Numbered) number + 1 else 0
            val want = BlockText.markerText(kind, number)
            val markerEnd = markerEnd(start)
            val have = markerEnd?.let { e.substring(start, it) }
            if (have != want) {
                val indent = BlockText.kindSpanAt(e, start)?.indent ?: trailingKind.second
                val oldLen = (markerEnd ?: start) - start
                if (markerEnd != null) e.delete(start, markerEnd)
                if (want != null) {
                    e.insert(start, want)
                    e.setSpan(MarkerSpan(), start, start + want.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    BlockText.setKindSpan(e, start, paragraphEndWithNewline(start), kind, indent)
                    if (start + want.length == e.length) trailingKind = "p" to 0
                }
                val newLen = want?.length ?: 0
                if (caret > start || (caret == start && newLen > 0)) {
                    caret = (caret + newLen - oldLen).coerceAtLeast(start + newLen)
                }
            }
            // Marker characters left mid-paragraph (say, after merging two items) go.
            removeStrayMarkers(start)
            val next = BlockText.paragraphEnd(e, start) + 1
            if (next > e.length || next <= start) break
            start = next
        }
        h.select(caret.coerceIn(0, e.length))
    }

    private fun removeStrayMarkers(paraStart: Int) {
        val e = host?.buffer ?: return
        val end = BlockText.paragraphEnd(e, paraStart)
        val strays = e.getSpans(paraStart, end, MarkerSpan::class.java).filter { e.getSpanStart(it) > paraStart }
        for (span in strays.sortedByDescending { e.getSpanStart(it) }) {
            val s = e.getSpanStart(span)
            val t = e.getSpanEnd(span)
            e.removeSpan(span)
            if (t > s) e.delete(s, t)
        }
    }

    // MARK: Paragraph surgery

    private fun kindAt(paraStart: Int): BlockKind {
        val e = host?.buffer ?: return BlockKind.Paragraph
        val end = BlockText.paragraphEnd(e, paraStart)
        // An empty last paragraph has no characters; its kind is held here.
        if (paraStart >= e.length || (end == paraStart && paraStart == e.length)) return BlockText.decode(trailingKind.first)
        return BlockText.decode(BlockText.kindSpanAt(e, paraStart)?.kind)
    }

    private fun markerEnd(paraStart: Int): Int? {
        val e = host?.buffer ?: return null
        if (paraStart >= e.length) return null
        return e.getSpans(paraStart, paraStart + 1, MarkerSpan::class.java)
            .filter { e.getSpanStart(it) == paraStart }
            .maxOfOrNull { e.getSpanEnd(it) }
    }

    private fun paragraphEndWithNewline(paraStart: Int): Int {
        val e = host?.buffer ?: return paraStart
        val end = BlockText.paragraphEnd(e, paraStart)
        return if (end < e.length) end + 1 else end
    }

    /** Re-kinds the paragraph starting at [start], swapping its list marker. */
    private fun setKindAt(start: Int, kind: BlockKind) {
        val e = host?.buffer ?: return
        val indent = BlockText.kindSpanAt(e, start)?.indent ?: trailingKind.second
        markerEnd(start)?.let { e.delete(start, it) }
        BlockText.markerText(kind, 1)?.let { m ->
            e.insert(start, m)
            e.setSpan(MarkerSpan(), start, start + m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val end = paragraphEndWithNewline(start)
        if (end > start) BlockText.setKindSpan(e, start, end, kind, indent) else trailingKind = BlockText.encode(kind) to indent
    }

    private fun hasFlag(from: Int, to: Int, kind: MarkKind): Boolean {
        val e = host?.buffer ?: return false
        if (from < 0 || to > e.length || from >= to) return false
        return e.getSpans(from, to, FlagSpan::class.java).any {
            it.kind == kind && e.getSpanStart(it) <= from && e.getSpanEnd(it) >= to
        }
    }

    /** Runs a programmatic edit, then repairs, restyles and reports it. */
    private fun edit(block: () -> Unit) {
        val h = host ?: return
        applying = true
        try {
            block()
            BlockText.normalizeKinds(h.buffer)
            repairMarkers()
            style?.let { BlockText.restyle(h.buffer, 0, h.buffer.length, it) }
            expectedCaret = h.caretStart
        } finally {
            applying = false
        }
        onChange?.invoke()
        onSelectionChange?.invoke()
    }
}
