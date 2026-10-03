package uk.co.maybeitssoftware.librelog.editor

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import uniffi.librelog_core.Block
import uniffi.librelog_core.BlockKind
import uniffi.librelog_core.Mark
import uniffi.librelog_core.MarkKind

/**
 * The bridge between the core's block document (doc.rs) and Android text.
 *
 * Meaning lives in semantic spans ([KindSpan], [MarkerSpan], [FlagSpan],
 * [LinkSpan], [EntryLinkSpan]); fonts, colours and margins are [VisualSpan]s
 * that [restyle] derives from them and can strip and rebuild at will. So bold
 * inside a heading or italic inside a quote round-trips exactly.
 *
 * One block = one paragraph. List markers ("• ", "1. ", "☐ ") are real
 * characters under a [MarkerSpan] and are dropped on the way back to blocks.
 * Mark offsets are UTF-16 code units, the same unit Java strings index by, so
 * they map one to one. Mirrors apple/App/Editor/BlockText.swift.
 */
object BlockText {
    /** U+2002 EN SPACE separates a list marker from the item's text. */
    const val MARKER_GAP = ' '

    // MARK: Blocks → spannable

    fun spannable(blocks: List<Block>, style: EditorStyle? = null): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val doc = blocks.ifEmpty { listOf(Block(BlockKind.Paragraph, "", emptyList(), 0u)) }
        // Spans are attached only once the text is complete: an inclusive-end
        // span at the end of the buffer would otherwise swallow every later
        // append.
        val pending = mutableListOf<Triple<Any, Int, Int>>()
        var number = 0
        var previous: BlockKind? = null
        doc.forEachIndexed { i, block ->
            number = if (block.kind == BlockKind.Numbered) (if (previous == BlockKind.Numbered) number + 1 else 1) else 0
            val paraStart = out.length
            markerText(block.kind, number)?.let { m ->
                out.append(m)
                pending += Triple(MarkerSpan(), paraStart, out.length)
            }
            val textStart = out.length
            out.append(block.text)
            for (mark in block.marks) {
                val s = textStart + mark.start.toInt()
                val e = textStart + mark.end.toInt()
                if (s >= e || e > out.length) continue
                pending += Triple(semanticSpan(mark.kind), s, e)
            }
            if (i < doc.size - 1) out.append('\n')
            if (out.length > paraStart) {
                pending += Triple(KindSpan(encode(block.kind), block.indent.toInt()), paraStart, out.length)
            }
            previous = block.kind
        }
        for ((span, s, e) in pending) {
            val flags = when (span) {
                is MarkerSpan -> Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                is KindSpan -> Spanned.SPAN_INCLUSIVE_INCLUSIVE
                else -> Spanned.SPAN_EXCLUSIVE_INCLUSIVE // typing at a mark's end continues it
            }
            out.setSpan(span, s, e, flags)
        }
        if (style != null) restyle(out, 0, out.length, style)
        return out
    }

    fun markerText(kind: BlockKind, number: Int): String? = when (kind) {
        BlockKind.Bullet -> "•$MARKER_GAP"
        BlockKind.Numbered -> "${maxOf(number, 1)}.$MARKER_GAP"
        is BlockKind.Check -> if (kind.checked) "☑$MARKER_GAP" else "☐$MARKER_GAP"
        else -> null
    }

    private fun semanticSpan(kind: MarkKind): Any = when (kind) {
        is MarkKind.Link -> LinkSpan(kind.url)
        is MarkKind.EntryLink -> EntryLinkSpan(kind.entryId)
        else -> FlagSpan(kind)
    }

    // MARK: Spanned → blocks

    fun blocks(text: Spanned): List<Block> {
        val out = mutableListOf<Block>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            out += block(text, start, end, hasNewline = nl >= 0)
            if (nl < 0) break
            start = nl + 1
            // A trailing newline means an empty last paragraph. It has no
            // characters to carry spans (the newline's belong to the paragraph
            // before it), so it's always plain.
            if (start == text.length) {
                out += Block(BlockKind.Paragraph, "", emptyList(), 0u)
                break
            }
        }
        return out
    }

    private fun block(text: Spanned, start: Int, end: Int, hasNewline: Boolean): Block {
        val probeEnd = if (hasNewline) end + 1 else end
        val kindSpan = if (probeEnd > start) kindSpanAt(text, start) else null
        val kind = decode(kindSpan?.kind)
        val indent = (kindSpan?.indent ?: 0).coerceIn(0, 255)

        var contentStart = start
        text.getSpans(start, end, MarkerSpan::class.java)
            .filter { text.getSpanStart(it) <= start && text.getSpanEnd(it) > start }
            .maxOfOrNull { text.getSpanEnd(it) }
            ?.let { contentStart = minOf(it, end) }

        val string = text.subSequence(contentStart, end).toString()
        val marks = mutableListOf<Mark>()
        if (end > contentStart) {
            fun add(s: Int, e: Int, kind: MarkKind) {
                val cs = maxOf(s, contentStart)
                val ce = minOf(e, end)
                if (cs < ce) marks += Mark((cs - contentStart).toUInt(), (ce - contentStart).toUInt(), kind)
            }
            for (span in text.getSpans(contentStart, end, FlagSpan::class.java)) add(text.getSpanStart(span), text.getSpanEnd(span), span.kind)
            for (span in text.getSpans(contentStart, end, LinkSpan::class.java)) add(text.getSpanStart(span), text.getSpanEnd(span), MarkKind.Link(span.url))
            for (span in text.getSpans(contentStart, end, EntryLinkSpan::class.java)) add(text.getSpanStart(span), text.getSpanEnd(span), MarkKind.EntryLink(span.entryId))
        }
        return Block(kind, string, merge(marks), indent.toUByte())
    }

    /** Joins touching or overlapping marks of the same kind, then sorts. */
    private fun merge(marks: List<Mark>): List<Mark> {
        val out = mutableListOf<Mark>()
        for (m in marks.sortedWith(compareBy({ it.start }, { it.end }))) {
            val i = out.indexOfLast { it.kind == m.kind && it.end >= m.start }
            if (i >= 0) out[i] = out[i].copy(end = maxOf(out[i].end, m.end)) else out += m
        }
        return out.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /** The paragraph's kind span: the one covering its first character. */
    fun kindSpanAt(text: Spanned, paraStart: Int): KindSpan? {
        if (text.isEmpty()) return null
        val at = paraStart.coerceAtMost(text.length - 1)
        return text.getSpans(at, at + 1, KindSpan::class.java)
            .filter { text.getSpanStart(it) <= at && text.getSpanEnd(it) > at }
            .maxByOrNull { text.getSpanStart(it) }
    }

    // MARK: Kinds as span values

    fun encode(k: BlockKind): String = when (k) {
        BlockKind.Paragraph -> "p"
        is BlockKind.Heading -> "h${k.level}"
        BlockKind.Bullet -> "bullet"
        BlockKind.Numbered -> "numbered"
        is BlockKind.Check -> if (k.checked) "check1" else "check0"
        BlockKind.Quote -> "quote"
        BlockKind.Code -> "code"
        BlockKind.Rule -> "rule"
        is BlockKind.Image -> "image:${k.attachmentId}"
    }

    fun decode(s: String?): BlockKind = when {
        s == "h1" -> BlockKind.Heading(1u)
        s == "h2" -> BlockKind.Heading(2u)
        s == "h3" -> BlockKind.Heading(3u)
        s == "bullet" -> BlockKind.Bullet
        s == "numbered" -> BlockKind.Numbered
        s == "check0" -> BlockKind.Check(false)
        s == "check1" -> BlockKind.Check(true)
        s == "quote" -> BlockKind.Quote
        s == "code" -> BlockKind.Code
        s == "rule" -> BlockKind.Rule
        s != null && s.startsWith("image:") -> BlockKind.Image(s.removePrefix("image:"))
        else -> BlockKind.Paragraph
    }

    fun isList(k: BlockKind) = k == BlockKind.Bullet || k == BlockKind.Numbered || k is BlockKind.Check

    // MARK: Styling

    /** Derives the visual spans for every paragraph touching [from]..[to]. */
    fun restyle(s: Spannable, from: Int, to: Int, style: EditorStyle) {
        if (s.isEmpty()) return
        val text = s.toString()
        var start = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0).coerceAtMost(text.length - 1)).let {
            if (from == 0 || it < 0) 0 else it + 1
        }
        val stop = text.indexOf('\n', to.coerceAtMost(text.length)).let { if (it < 0) text.length else it }
        while (start <= stop && start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            restyleParagraph(s, start, end, if (nl < 0) end else end + 1, style)
            if (nl < 0) break
            start = nl + 1
        }
    }

    private fun restyleParagraph(s: Spannable, start: Int, end: Int, endWithNewline: Int, style: EditorStyle) {
        for (v in s.getSpans(start, endWithNewline, VisualSpan::class.java)) {
            val vs = s.getSpanStart(v)
            val ve = s.getSpanEnd(v)
            if (vs >= start && ve <= endWithNewline) s.removeSpan(v)
        }
        if (endWithNewline <= start) return
        val kindSpan = kindSpanAt(s, start)
        val k = decode(kindSpan?.kind)
        val indent = kindSpan?.indent ?: 0
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        // Paragraph-level look.
        when {
            // Wrapped lines align with the item's text, not its marker.
            isList(k) -> s.setSpan(VMargin(style.listIndent * indent, style.listIndent * (indent + 1)), start, endWithNewline, flag)
            k == BlockKind.Quote -> s.setSpan(VQuote(style.muted, style.quoteStripe, style.quoteGap), start, endWithNewline, flag)
            else -> {}
        }

        // Character runs: split the paragraph wherever a semantic span starts or ends.
        val cuts = sortedSetOf(start, end)
        for (span in s.getSpans(start, end, Any::class.java)) {
            if (span is VisualSpan || span is KindSpan) continue
            cuts += s.getSpanStart(span).coerceIn(start, end)
            cuts += s.getSpanEnd(span).coerceIn(start, end)
        }
        val list = cuts.toList()
        for (i in 0 until list.size - 1) {
            val rs = list[i]
            val re = list[i + 1]
            if (rs >= re) continue
            fun has(cls: Class<*>, kind: MarkKind? = null) = s.getSpans(rs, re, cls).any {
                s.getSpanStart(it) <= rs && s.getSpanEnd(it) >= re && (kind == null || (it as FlagSpan).kind == kind)
            }
            val isMarker = has(MarkerSpan::class.java)
            val isCode = has(FlagSpan::class.java, MarkKind.Code) || k == BlockKind.Code
            val bold = has(FlagSpan::class.java, MarkKind.Bold)
            val italic = has(FlagSpan::class.java, MarkKind.Italic)
            val entryLink = has(EntryLinkSpan::class.java)
            val link = has(LinkSpan::class.java)

            s.setSpan(VFont(style.font(k, bold, italic, isCode), style.size(k, isCode)), rs, re, flag)
            val color = when {
                entryLink -> style.primary
                isMarker || k == BlockKind.Quote -> style.muted
                else -> style.ink
            }
            s.setSpan(VColor(color), rs, re, flag)
            if (has(FlagSpan::class.java, MarkKind.Underline) || link || entryLink) s.setSpan(VUnderline(), rs, re, flag)
            if (has(FlagSpan::class.java, MarkKind.Strike) || (k == BlockKind.Check(true) && !isMarker)) s.setSpan(VStrike(), rs, re, flag)
            when {
                has(FlagSpan::class.java, MarkKind.Highlight) -> s.setSpan(VBackground(style.highlight), rs, re, flag)
                isCode -> s.setSpan(VBackground(style.codeBackground), rs, re, flag)
            }
        }
    }

    // MARK: Span surgery used by the controller

    /** Sets [kind] on the paragraph [start]..[endWithNewline], replacing any other. */
    fun setKindSpan(s: Spannable, start: Int, endWithNewline: Int, kind: BlockKind, indent: Int) {
        for (old in s.getSpans(start, endWithNewline, KindSpan::class.java)) {
            val os = s.getSpanStart(old)
            val oe = s.getSpanEnd(old)
            s.removeSpan(old)
            // Keep the parts that belong to neighbouring paragraphs.
            if (os < start) s.setSpan(KindSpan(old.kind, old.indent), os, start, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (oe > endWithNewline) s.setSpan(KindSpan(old.kind, old.indent), endWithNewline, oe, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        if (endWithNewline > start) {
            s.setSpan(KindSpan(encode(kind), indent), start, endWithNewline, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    /**
     * Makes every paragraph carry exactly one [KindSpan] covering exactly its
     * characters. Typing at paragraph edges stretches inclusive spans across
     * boundaries; this puts them back, keeping each paragraph's own kind.
     */
    fun normalizeKinds(s: Spannable) {
        val text = s.toString()
        val paras = mutableListOf<Triple<Int, Int, KindSpan?>>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl + 1
            // A paragraph's kind is the span that starts nearest its start.
            val own = s.getSpans(start, end, KindSpan::class.java)
                .filter { s.getSpanEnd(it) > start }
                .minByOrNull { kotlin.math.abs(s.getSpanStart(it) - start) }
            paras += Triple(start, end, own)
            start = end
        }
        val values = paras.map { (_, _, k) -> k?.let { it.kind to it.indent } }
        for (old in s.getSpans(0, s.length, KindSpan::class.java)) s.removeSpan(old)
        paras.forEachIndexed { i, (ps, pe, _) ->
            val (kind, indent) = values[i] ?: ("p" to 0)
            s.setSpan(KindSpan(kind, indent), ps, pe, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    /** Removes [cls] spans from [start]..[end], splitting spans that straddle it. */
    fun <T : Any> clear(s: Spannable, start: Int, end: Int, cls: Class<T>, matches: (T) -> Boolean = { true }, copy: (T) -> T) {
        for (span in s.getSpans(start, end, cls)) {
            if (!matches(span)) continue
            val ss = s.getSpanStart(span)
            val se = s.getSpanEnd(span)
            if (se <= start || ss >= end) continue
            val flags = s.getSpanFlags(span)
            s.removeSpan(span)
            if (ss < start) s.setSpan(copy(span), ss, start, flags)
            if (se > end) s.setSpan(copy(span), end, se, flags)
        }
    }

    fun paragraphStart(text: CharSequence, at: Int): Int {
        if (at <= 0) return 0
        val i = text.lastIndexOf('\n', (at - 1).coerceAtMost(text.length - 1))
        return if (i < 0) 0 else i + 1
    }

    /** End of the paragraph containing [at], excluding its newline. */
    fun paragraphEnd(text: CharSequence, at: Int): Int {
        val i = text.indexOf('\n', at.coerceAtMost(text.length))
        return if (i < 0) text.length else i
    }

}

// MARK: Semantic spans (saved)

/** The paragraph's block kind (as [BlockText.encode]) and list nesting. */
class KindSpan(val kind: String, val indent: Int)

/** List marker characters, skipped when saving. */
class MarkerSpan

class FlagSpan(val kind: MarkKind)

class LinkSpan(val url: String)

class EntryLinkSpan(val entryId: String)

// MARK: Visual spans (derived by restyle, never saved)

interface VisualSpan

class VFont(private val typeface: Typeface, private val sizePx: Float) : MetricAffectingSpan(), VisualSpan {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(tp: TextPaint) {
        tp.typeface = typeface
        tp.textSize = sizePx
    }
}

class VColor(color: Int) : ForegroundColorSpan(color), VisualSpan
class VUnderline : UnderlineSpan(), VisualSpan
class VStrike : StrikethroughSpan(), VisualSpan
class VBackground(color: Int) : BackgroundColorSpan(color), VisualSpan
class VMargin(first: Int, rest: Int) : LeadingMarginSpan.Standard(first, rest), VisualSpan
class VQuote(color: Int, stripe: Int, gap: Int) : QuoteSpan(color, stripe, gap), VisualSpan

/** Fonts, sizes and colours the editor draws with; built from the theme. */
data class EditorStyle(
    val serif: Typeface,
    val serifBold: Typeface,
    val serifItalic: Typeface,
    val serifBoldItalic: Typeface,
    val mono: Typeface,
    val bodyPx: Float,
    val scaledPx: (Float) -> Float,
    val ink: Int,
    val muted: Int,
    val primary: Int,
    val highlight: Int,
    val codeBackground: Int,
    val listIndent: Int,
    val quoteStripe: Int,
    val quoteGap: Int,
) {
    fun font(k: BlockKind, bold: Boolean, italic: Boolean, code: Boolean): Typeface {
        if (code) return mono
        val b = bold || k is BlockKind.Heading
        val i = italic || k == BlockKind.Quote
        return when {
            b && i -> serifBoldItalic
            b -> serifBold
            i -> serifItalic
            else -> serif
        }
    }

    fun size(k: BlockKind, code: Boolean): Float = when {
        code -> scaledPx(15f)
        k is BlockKind.Heading -> scaledPx(
            when (k.level.toInt()) {
                1 -> 26f
                2 -> 22f
                else -> 19f
            },
        )
        else -> bodyPx
    }
}
