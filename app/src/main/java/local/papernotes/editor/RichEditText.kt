package local.papernotes.editor

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import local.papernotes.model.ParaFormat
import local.papernotes.model.RichTextOps
import local.papernotes.model.SpanType
import local.papernotes.model.TextSpan
import local.papernotes.model.Textbox

/**
 * Eingabefeld einer Textbox mit Rich-Text (A3), direkt auf dem Blatt.
 *
 * Wie in HiNote gilt ein Format ohne Auswahl für die nächsten getippten Zeichen ([typing]); mit Auswahl wirkt es auf die
 * Auswahl. Wandert der Cursor, übernimmt [typing] das Format des Zeichens links vom Cursor. Absatzformate führt das Feld
 * selbst mit [dense] (ein Eintrag je Absatz) und hält sie bei Zeilenumbrüchen und Löschen aktuell.
 *
 * Die Spans sind eigene Marker-Klassen ([RichTextAndroid]); Spans der Tastatur bleiben unangetastet.
 */
class RichEditText(context: Context) : EditText(context) {

    /** Nach jeder Änderung an Text oder Format (zum Übernehmen ins Modell). */
    var onContentChanged: (() -> Unit)? = null

    /** Nach jeder Änderung, die die Anzeige der Format-Leiste betrifft (Cursor, Auswahl, Formate). */
    var onStateChanged: (() -> Unit)? = null

    private var ready = false
    private var busy = false
    private var textChanging = false
    private var unit = DEFAULT_UNIT
    private var dense: List<ParaFormat> = listOf(ParaFormat(0))
    private val typing = HashMap<SpanType, Int>()
    private var insStart = 0
    private var insEnd = 0
    private var beforePara = 0
    private var removedNl = 0

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setHorizontallyScrolling(false)
        setTextColor(android.graphics.Color.BLACK)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!ready || busy) return
                textChanging = true
                beforePara = RichTextOps.paragraphIndexAt(s, start)
                removedNl = 0
                for (i in start until start + count) if (s[i] == '\n') removedNl++
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (!ready || busy) return
                insStart = start
                insEnd = start + count
                var addedNl = 0
                for (i in start until start + count) if (s[i] == '\n') addedNl++
                val list = dense.toMutableList()
                if (list.isEmpty()) list.add(ParaFormat(0))
                val at = beforePara.coerceIn(0, list.size - 1)
                repeat(removedNl) { if (at + 1 < list.size) list.removeAt(at + 1) }
                val copy = list[at]
                repeat(addedNl) { list.add(at + 1, copy) }
                val n = RichTextOps.paragraphCount(s)
                while (list.size < n) list.add(ParaFormat(list.size))
                while (list.size > n) list.removeAt(list.size - 1)
                dense = list
            }

            override fun afterTextChanged(e: Editable) {
                if (!ready || busy) return
                busy = true
                try {
                    if (insEnd > insStart) applyTyping(e)
                    RichTextAndroid.applyParas(e, dense, unit)
                } finally {
                    busy = false
                    textChanging = false
                }
                if (insEnd <= insStart) recomputeTyping() // Löschen: Format vom Zeichen links des Cursors
                onContentChanged?.invoke()
                onStateChanged?.invoke()
            }
        })
        ready = true
    }

    /** Lädt Text und Formate aus [tb]. [indentUnit] = Einzugsbreite je Stufe in Pixeln. */
    fun load(tb: Textbox, indentUnit: Float) {
        unit = indentUnit
        busy = true
        try {
            setText(RichTextAndroid.build(tb, indentUnit), BufferType.EDITABLE)
        } finally {
            busy = false
        }
        dense = RichTextOps.denseParas(tb.paragraphs, RichTextOps.paragraphCount(tb.text))
        typing.clear()
        setSelection(text.length)
        recomputeTyping()
        onStateChanged?.invoke()
    }

    /** Neue Schriftgröße (Pixel) und Einzugsbreite, z. B. nach Zoom oder Größenänderung. */
    fun setMetrics(textPx: Float, indentUnit: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        if (indentUnit != unit) {
            unit = indentUnit
            refreshParas()
        }
    }

    /** Zeichenformate des Textes (kanonisch). */
    fun contentSpans(): List<TextSpan> = RichTextAndroid.readSpans(text)

    /** Abweichende Absatzformate (kanonisch). */
    fun contentParas(): List<ParaFormat> = RichTextOps.sparseParas(dense)

    // ---- Zustand für die Leiste ----

    private fun selA() = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
    private fun selB() = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
    private fun hasRange() = selA() != selB()

    /** `true`, wenn [type] (Fett, Kursiv, Unterstrichen) in der Auswahl komplett bzw. am Cursor für neuen Text gilt. */
    fun isOn(type: SpanType): Boolean =
        if (hasRange()) RichTextOps.isCovered(contentSpans(), type, selA(), selB()) else typing.containsKey(type)

    /** Relative Größe in Prozent am Anfang der Auswahl bzw. für neuen Text. */
    fun sizePercent(): Int =
        if (hasRange()) RichTextOps.valueAt(contentSpans(), SpanType.SIZE, selA(), RichTextOps.SIZE_DEFAULT)
        else typing[SpanType.SIZE] ?: RichTextOps.SIZE_DEFAULT

    fun paraRange(): IntRange {
        val r = RichTextOps.paragraphRange(text, selA(), selB())
        val last = (dense.size - 1).coerceAtLeast(0)
        return r.first.coerceIn(0, last)..r.last.coerceIn(0, last)
    }

    fun paraFormats(): List<ParaFormat> = paraRange().mapNotNull { dense.getOrNull(it) }

    // ---- Formatieren ----

    fun toggleChar(type: SpanType) {
        if (hasRange()) {
            val next = RichTextOps.toggle(contentSpans(), text.length, type, selA(), selB())
            replaceSpans(next)
        } else if (typing.remove(type) == null) {
            typing[type] = 0
        }
        changed()
    }

    fun setSize(percent: Int) {
        if (hasRange()) {
            replaceSpans(RichTextOps.setSpan(contentSpans(), text.length, SpanType.SIZE, selA(), selB(), percent))
        } else if (percent == RichTextOps.SIZE_DEFAULT) {
            typing.remove(SpanType.SIZE)
        } else {
            typing[SpanType.SIZE] = percent
        }
        changed()
    }

    fun setAlign(align: Int) = paraOp { RichTextOps.setAlign(dense, paraRange(), align) }

    fun toggleList(kind: Int) = paraOp { RichTextOps.toggleList(dense, paraRange(), kind) }

    fun changeLevel(delta: Int) = paraOp { RichTextOps.changeLevel(dense, paraRange(), delta) }

    private fun paraOp(op: () -> List<ParaFormat>) {
        dense = op()
        refreshParas()
        changed()
    }

    private fun changed() {
        onContentChanged?.invoke()
        onStateChanged?.invoke()
    }

    private fun replaceSpans(spans: List<TextSpan>) {
        busy = true
        try {
            RichTextAndroid.setCharSpans(text, spans)
        } finally {
            busy = false
        }
        invalidate()
    }

    private fun refreshParas() {
        busy = true
        try {
            RichTextAndroid.applyParas(text, dense, unit)
        } finally {
            busy = false
        }
        invalidate()
    }

    /** Neu getippter Text bekommt das Format aus [typing] (andere Formate dort werden entfernt). */
    private fun applyTyping(e: Editable) {
        var spans = RichTextAndroid.readSpans(e)
        for (t in SpanType.values()) {
            spans = RichTextOps.setSpan(spans, e.length, t, insStart, insEnd, typing[t])
        }
        RichTextAndroid.setCharSpans(e, spans)
    }

    /** Übernimmt für den Cursor das Format des Zeichens links davon (am Anfang: rechts davon). */
    private fun recomputeTyping() {
        if (hasRange() || text.isEmpty()) return
        val pos = selA()
        val ref = if (pos > 0) pos - 1 else 0
        val spans = contentSpans()
        typing.clear()
        for (t in SpanType.values()) {
            val v = RichTextOps.valueAt(spans, t, ref, NONE)
            if (v != NONE) typing[t] = v
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || busy || textChanging) return
        recomputeTyping()
        onStateChanged?.invoke()
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
        const val DEFAULT_UNIT = 24f
    }
}
