package uk.co.maybeitssoftware.librelog.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import uk.co.maybeitssoftware.librelog.R
import uk.co.maybeitssoftware.librelog.ui.Tokens

/**
 * The entry body editor: an EditText driven by [EditorController]. Hosted in
 * Compose through [RichTextEditor].
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class EditorEditText(context: Context, private val controller: EditorController) : EditText(context), EditorHost {
    override val buffer: Editable get() = text
    override val caretStart: Int get() = selectionStart.coerceAtLeast(0)
    override val caretEnd: Int get() = selectionEnd.coerceAtLeast(0)

    override fun select(start: Int, end: Int) {
        val len = buffer.length
        setSelection(start.coerceIn(0, len), end.coerceIn(0, len))
    }

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        isSingleLine = false
        setHorizontallyScrolling(false)
        setLineSpacing(0f, 1.25f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = ResourcesCompat.getFont(context, R.font.arvo_regular)
        controller.host = this
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) =
                controller.beforeChange(start, count, after)
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = controller.afterChange()
        })
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Called from EditText's constructor, before `controller` is assigned.
        @Suppress("SENSELESS_COMPARISON")
        if (controller != null) controller.selectionChanged()
    }

    /** A tap on a checkbox marker flips it rather than moving the caret. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && layout != null) {
            val offset = getOffsetForPosition(event.x, event.y)
            if (controller.toggleCheck(offset)) return true
        }
        return super.onTouchEvent(event)
    }
}

@Composable
fun RichTextEditor(controller: EditorController, modifier: Modifier = Modifier, placeholder: String, onReady: (EditorEditText) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditorEditText(ctx, controller).apply {
                hint = placeholder
                onReady(this)
            }
        },
    )
}

/** Fonts, sizes and colours for the editor, from the theme's role colours. */
fun editorStyle(context: Context, t: Tokens): EditorStyle {
    val dm = context.resources.displayMetrics
    fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    fun font(id: Int) = ResourcesCompat.getFont(context, id) ?: Typeface.SERIF
    return EditorStyle(
        serif = font(R.font.arvo_regular),
        serifBold = font(R.font.arvo_bold),
        serifItalic = font(R.font.arvo_italic),
        serifBoldItalic = font(R.font.arvo_bold_italic),
        mono = ResourcesCompat.getFont(context, R.font.geist_mono) ?: Typeface.MONOSPACE,
        bodyPx = sp(17f),
        scaledPx = ::sp,
        ink = t.ink.toArgb(),
        muted = t.muted.toArgb(),
        primary = t.primary.toArgb(),
        highlight = t.warning.copy(alpha = 0.3f).toArgb(),
        codeBackground = t.well.toArgb(),
        listIndent = dp(22f),
        quoteStripe = dp(2f),
        quoteGap = dp(14f),
    )
}
