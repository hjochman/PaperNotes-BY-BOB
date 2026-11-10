package local.papernotes.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import local.papernotes.model.Textbox

/**
 * Eine Textbox auf dem Blatt. Position, Größe und Schriftgröße liegen im Modell in Seitenkoordinaten; die Ansicht
 * arbeitet in Ansichts-Pixeln (Umrechnung über [PageTransform] in [bind]).
 *
 * Nur wenn [editable] gesetzt ist (Werkzeug Textfeld), nimmt die Ansicht Berührungen an: Ziehen verschiebt, Ziehen an
 * der Ecke unten rechts ändert die Größe, ein Tippen ruft [onTap] auf, langes Drücken [onLongPress]. Sonst reicht sie
 * Berührungen an die Schicht darunter durch (Stift und Lasso bleiben unberührt) und zeichnet keinen Rahmen (E-Ink:
 * kein Flackern).
 *
 * Bearbeiten (A3) geschieht wie in HiNote direkt auf dem Blatt: [startEditing] legt ein [RichEditText] über die Anzeige.
 * Dann verschiebt nur die Pille oben in der Mitte die Box, die Ecke unten rechts ändert die Größe; alles andere gehört
 * dem Eingabefeld (Cursor, Auswahl).
 */
class TextboxView(context: Context) : FrameLayout(context) {

    private val label = TextView(context).apply {
        setTextColor(Color.BLACK)
        setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handlePx = (HANDLE_DP * resources.displayMetrics.density).toInt()
    private val pillW = (PILL_W_DP * resources.displayMetrics.density).toInt()
    private val pillH = (PILL_H_DP * resources.displayMetrics.density).toInt()

    var model: Textbox? = null
        private set
    private var transform = PageTransform.IDENTITY

    /** Das Eingabefeld, solange die Box bearbeitet wird. */
    var edit: RichEditText? = null
        private set
    val isEditing: Boolean get() = edit != null

    /** Wird nach Verschieben oder Größenänderung mit dem neuen Modell (Seitenkoordinaten) aufgerufen. */
    var onGeometryChanged: ((TextboxView, Textbox) -> Unit)? = null

    /** Wird bei einem Tippen (ohne Ziehen) aufgerufen. */
    var onTap: ((TextboxView) -> Unit)? = null

    /** Wird bei langem Drücken (ohne Ziehen) aufgerufen. */
    var onLongPress: ((TextboxView) -> Unit)? = null

    /** Wird bei jeder Änderung von Text oder Format während des Bearbeitens mit dem neuen Modell aufgerufen. */
    var onContentEdited: ((TextboxView, Textbox) -> Unit)? = null

    var editable: Boolean = false
        set(value) {
            field = value
            background = if (value) {
                GradientDrawable().apply { setStroke(1, Color.BLACK) }
            } else {
                null
            }
        }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var startLeft = 0
    private var startTop = 0
    private var startWidth = 0
    private var startHeight = 0
    private var resizing = false
    private var dragging = false
    private var grabbed = false

    private val pillPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    init {
        addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Setzt Text, Position und Größe aus [tb] unter der Abbildung [t]. Ein laufendes Bearbeiten bleibt unberührt. */
    fun bind(tb: Textbox, t: PageTransform) {
        model = tb
        transform = t
        val textPx = tb.fontSize * t.scale
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        label.text = RichTextAndroid.build(tb, textPx * INDENT_EM)
        val bg = if (tb.bgColor == 0) Color.TRANSPARENT else tb.bgColor
        label.setBackgroundColor(bg)
        edit?.let {
            it.setMetrics(textPx, textPx * INDENT_EM)
            it.setBackgroundColor(bg)
        }
        val lp = (layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        lp.width = t.pageToViewWidth(tb.width).toInt()
        lp.height = t.pageToViewWidth(tb.height).toInt()
        lp.leftMargin = t.pageToViewX(tb.x).toInt()
        lp.topMargin = t.pageToViewY(tb.y).toInt()
        layoutParams = lp
    }

    /** Legt das Eingabefeld über die Anzeige, setzt den Cursor ans Textende und öffnet die Tastatur. */
    fun startEditing(onStateChanged: () -> Unit): RichEditText? {
        val tb = model ?: return null
        edit?.let { return it }
        val textPx = tb.fontSize * transform.scale
        val e = RichEditText(context).apply {
            setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
            load(tb, textPx * INDENT_EM)
            setBackgroundColor(if (tb.bgColor == 0) Color.TRANSPARENT else tb.bgColor)
            onContentChanged = { syncFromEdit() }
            this.onStateChanged = onStateChanged
        }
        edit = e
        label.visibility = INVISIBLE
        addView(e, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        e.requestFocus()
        e.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
        }
        invalidate()
        return e
    }

    /** Beendet das Bearbeiten, schließt die Tastatur und zeigt wieder die Anzeige. */
    fun stopEditing() {
        val e = edit ?: return
        edit = null
        e.onContentChanged = null
        e.onStateChanged = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(e.windowToken, 0)
        removeView(e)
        label.visibility = VISIBLE
        model?.let { bind(it, transform) }
        invalidate()
    }

    private fun syncFromEdit() {
        val e = edit ?: return
        val tb = model ?: return
        val next = tb.copy(text = e.text.toString(), spans = e.contentSpans(), paragraphs = e.contentParas())
        if (next == tb) return
        model = next
        onContentEdited?.invoke(this, next)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!editable) return
        val size = handlePx / 2f
        val w = width.toFloat()
        val h = height.toFloat()
        // Drei diagonale Linien als Größen-Griff unten rechts
        canvas.drawLine(w - size, h, w, h - size, handlePaint)
        canvas.drawLine(w - size + 4f, h, w, h - size + 4f, handlePaint)
        canvas.drawLine(w - size + 8f, h, w, h - size + 8f, handlePaint)
        if (isEditing) {
            // Pille zum Verschieben, oben in der Mitte
            val left = (w - pillW) / 2f
            canvas.drawRoundRect(left, 0f, left + pillW, pillH.toFloat(), pillH / 2f, pillH / 2f, pillPaint)
        }
    }

    private fun inPill(x: Float, y: Float): Boolean =
        x >= (width - pillW) / 2f && x <= (width + pillW) / 2f && y <= pillH

    private fun inCorner(x: Float, y: Float): Boolean = x > width - handlePx && y > height - handlePx

    /** Beim Bearbeiten gehören nur Pille und Ecke der Box, alles andere dem Eingabefeld. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editable || !isEditing) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) grabbed = inPill(ev.x, ev.y) || inCorner(ev.x, ev.y)
        return grabbed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tb = model
        if (!editable || tb == null) return false
        val lp = layoutParams as LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downTime = event.eventTime
                startLeft = lp.leftMargin
                startTop = lp.topMargin
                startWidth = lp.width
                startHeight = lp.height
                resizing = inCorner(event.x, event.y)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) dragging = true
                if (dragging) {
                    if (resizing) {
                        lp.width = (startWidth + dx).toInt().coerceAtLeast(minViewPx(MIN_WIDTH))
                        lp.height = (startHeight + dy).toInt().coerceAtLeast(minViewPx(MIN_HEIGHT))
                    } else {
                        lp.leftMargin = (startLeft + dx).toInt()
                        lp.topMargin = (startTop + dy).toInt()
                    }
                    layoutParams = lp
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val moved = tb.copy(
                        x = transform.viewToPageX(lp.leftMargin.toFloat()),
                        y = transform.viewToPageY(lp.topMargin.toFloat()),
                        width = transform.viewToPageWidth(lp.width.toFloat()),
                        height = transform.viewToPageWidth(lp.height.toFloat()),
                    )
                    model = moved
                    onGeometryChanged?.invoke(this, moved)
                } else if (!isEditing) {
                    if (event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()) {
                        onLongPress?.invoke(this)
                    } else {
                        onTap?.invoke(this)
                    }
                }
                dragging = false
                grabbed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) bind(tb, transform)
                dragging = false
                grabbed = false
            }
        }
        return true
    }

    private fun minViewPx(pageUnits: Float): Int = (pageUnits * transform.scale).toInt().coerceAtLeast(1)

    companion object {
        /** Einzugsbreite je Stufe in Vielfachen der Schriftgröße. */
        const val INDENT_EM = 1.5f
        const val MIN_WIDTH = 60f
        const val MIN_HEIGHT = 40f
        private const val HANDLE_DP = 32
        private const val PILL_W_DP = 56
        private const val PILL_H_DP = 12
        private const val PADDING_PX = 4
    }
}
