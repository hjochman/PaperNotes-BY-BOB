package local.papernotes.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import local.papernotes.model.ParaFormat
import local.papernotes.model.RichTextOps
import local.papernotes.model.SpanType
import local.papernotes.model.TextSpan
import local.papernotes.model.Textbox

/**
 * Brücke zwischen dem Modell ([Textbox] mit [TextSpan]/[ParaFormat]) und Android-Spans (A3). Die Formate werden bei
 * jedem Aufbau aus dem Modell erzeugt; gespeichert wird nur das Modell (siehe `PnoteCodec`).
 */
object RichTextAndroid {
    /**
     * Eigene Unterklassen als Marker: Nur sie gelten als unsere Zeichenformate. Spans der Tastatur (Eingabe-Markierung,
     * Vorschläge) sind andere Objekte und werden weder gelesen noch entfernt.
     */
    class RtBold : StyleSpan(Typeface.BOLD)
    class RtItalic : StyleSpan(Typeface.ITALIC)
    class RtUnderline : UnderlineSpan()
    class RtColor(color: Int) : ForegroundColorSpan(color)
    class RtSize(factor: Float) : RelativeSizeSpan(factor)

    /**
     * Die Bereiche wachsen nicht von selbst mit; welches Format neu getippter Text bekommt, entscheidet [RichEditText]
     * ausdrücklich (Format am Cursor).
     */
    const val CHAR_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /** Einzug und Listenzeichen eines Absatzes. [dense] liefert die Absatzformate zum Zeitpunkt des Aufbaus. */
    class ParaMarginSpan(
        private val index: Int,
        private val dense: List<ParaFormat>,
        private val unit: Float,
    ) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int {
            val p = dense.getOrNull(index) ?: return 0
            val steps = if (p.list != ParaFormat.LIST_NONE) p.level + 1 else p.level
            return (steps * unit).toInt()
        }

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
        ) {
            if (!first) return
            val f = dense.getOrNull(index) ?: return
            val marker = RichTextOps.marker(dense, index)
            if (marker.isEmpty()) return
            val left = x + dir * f.level * unit
            val shape = marker[0]
            if (shape == '\u2022' || shape == '\u25A0' || shape == '\u25A1') {
                drawSymbol(c, p, shape, left + dir * unit * 0.3f, baseline - p.textSize * 0.4f)
            } else {
                c.drawText(marker, left, baseline.toFloat(), p)
            }
        }

        /**
         * Zeichnet Punkt und Quadrate selbst, mittig in ihrem Einzugsfeld; so hängt die Lage nicht von den
         * Seitenabständen der Schriftzeichen ab. Größen in Anteilen der Schrift.
         */
        private fun drawSymbol(c: Canvas, p: Paint, shape: Char, cx: Float, cy: Float) {
            val size = p.textSize
            val oldStyle = p.style
            val oldWidth = p.strokeWidth
            when (shape) {
                '\u2022' -> {
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, size * 0.17f, p)
                }
                '\u25A0' -> {
                    p.style = Paint.Style.FILL
                    val h = size * 0.178f
                    c.drawRect(cx - h, cy - h, cx + h, cy + h, p)
                }
                else -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = size * 0.06f
                    val h = size * 0.178f
                    c.drawRect(cx - h, cy - h, cx + h, cy + h, p)
                }
            }
            p.style = oldStyle
            p.strokeWidth = oldWidth
        }
    }

    /** Baut den anzuzeigenden Text einer Textbox. [unit] = Einzugsbreite je Stufe in Pixeln. */
    fun build(tb: Textbox, unit: Float): SpannableStringBuilder {
        val sb = SpannableStringBuilder(tb.text)
        setCharSpans(sb, tb.spans)
        val dense = RichTextOps.denseParas(tb.paragraphs, RichTextOps.paragraphCount(tb.text))
        applyParas(sb, dense, unit)
        return sb
    }

    /** Ein Android-Span für [type]/[value]. */
    fun makeSpan(type: SpanType, value: Int): Any = when (type) {
        SpanType.BOLD -> RtBold()
        SpanType.ITALIC -> RtItalic()
        SpanType.UNDERLINE -> RtUnderline()
        SpanType.COLOR -> RtColor(value)
        SpanType.SIZE -> RtSize(value / 100f)
    }

    /** Ersetzt alle Zeichenformate in [e] durch [spans]. */
    fun setCharSpans(e: Spannable, spans: List<TextSpan>) {
        for (s in e.getSpans(0, e.length, Any::class.java)) {
            if (isCharSpan(s)) e.removeSpan(s)
        }
        for (s in spans) {
            if (s.end > s.start && s.end <= e.length) e.setSpan(makeSpan(s.type, s.value), s.start, s.end, CHAR_FLAGS)
        }
    }

    private fun isCharSpan(s: Any) = s is RtBold || s is RtItalic || s is RtUnderline || s is RtColor || s is RtSize

    /** Liest unsere Zeichenformate aus [e] und bringt sie in kanonische Form. */
    fun readSpans(e: Spanned): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        for (s in e.getSpans(0, e.length, Any::class.java)) {
            val a = e.getSpanStart(s)
            val b = e.getSpanEnd(s)
            when (s) {
                is RtBold -> out.add(TextSpan(SpanType.BOLD, a, b))
                is RtItalic -> out.add(TextSpan(SpanType.ITALIC, a, b))
                is RtUnderline -> out.add(TextSpan(SpanType.UNDERLINE, a, b))
                is RtColor -> out.add(TextSpan(SpanType.COLOR, a, b, s.foregroundColor))
                is RtSize -> out.add(TextSpan(SpanType.SIZE, a, b, Math.round(s.sizeChange * 100f)))
            }
        }
        return RichTextOps.normalizeSpans(out, e.length)
    }

    /** Setzt Ausrichtung, Einzug und Listen aus [dense] neu (ersetzt vorhandene Absatzformate). */
    fun applyParas(e: Spannable, dense: List<ParaFormat>, unit: Float) {
        for (s in e.getSpans(0, e.length, ParaMarginSpan::class.java)) e.removeSpan(s)
        for (s in e.getSpans(0, e.length, AlignmentSpan.Standard::class.java)) e.removeSpan(s)
        val len = e.length
        var start = 0
        var idx = 0
        while (true) {
            val nl = TextUtils.indexOf(e, '\n', start)
            val end = if (nl < 0) len else nl + 1
            val f = dense.getOrNull(idx)
            if (f != null && end > start) {
                if (f.align != ParaFormat.ALIGN_LEFT) {
                    val a = if (f.align == ParaFormat.ALIGN_CENTER) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_OPPOSITE
                    e.setSpan(AlignmentSpan.Standard(a), start, end, Spanned.SPAN_PARAGRAPH)
                }
                if (f.list != ParaFormat.LIST_NONE || f.level > 0) {
                    e.setSpan(ParaMarginSpan(idx, dense, unit), start, end, Spanned.SPAN_PARAGRAPH)
                }
            }
            if (nl < 0) break
            start = end
            idx++
        }
    }
}
