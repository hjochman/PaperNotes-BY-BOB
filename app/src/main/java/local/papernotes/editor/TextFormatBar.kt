package local.papernotes.editor

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import local.papernotes.R
import local.papernotes.model.ParaFormat
import local.papernotes.model.RichTextOps
import local.papernotes.model.SpanType

/**
 * Formatleiste der Textbox (A3), wie in HiNote fest über der Tastatur: Nummerierung, Buchstaben, Aufzählung | Fett,
 * Kursiv, Unterstrichen, Schriftgröße | Links, Mitte, Rechts | Einzug größer/kleiner. Die Zeichenformate gelten für die
 * nächsten getippten Zeichen bzw. für die Auswahl; die Ausrichtung ist in Listen gesperrt (grau). Die Größenzeile
 * (Schieber mit −/+) klappt über „Aa“ auf.
 */
class TextFormatBar @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) :
    LinearLayout(context, attrs) {

    private var edit: RichEditText? = null
    private var updating = false

    private val sizeRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val sizeSeek = SeekBar(context)
    private val sizeLabel = TextView(context)

    private val bNumbers = listButton(listOf("1.", "2.", "3."), R.string.rt_numbers) { edit?.toggleList(ParaFormat.LIST_NUMBER) }
    private val bLetters = listButton(listOf("a.", "b.", "c."), R.string.rt_letters) { edit?.toggleList(ParaFormat.LIST_LETTER) }
    private val bBullets = listButton(listOf("\u2022", "\u2022", "\u2022"), R.string.rt_bullets) { edit?.toggleList(ParaFormat.LIST_BULLET) }
    private val bSquares = listButton(listOf("\u25A0", "\u25A0", "\u25A0"), R.string.rt_squares) { edit?.toggleList(ParaFormat.LIST_SQUARE) }
    private val bBold = textButton("B", R.string.rt_bold) { edit?.toggleChar(SpanType.BOLD) }.apply {
        setTypeface(typeface, Typeface.BOLD)
    }
    private val bItalic = textButton("I", R.string.rt_italic) { edit?.toggleChar(SpanType.ITALIC) }.apply {
        setTypeface(typeface, Typeface.ITALIC)
    }
    private val bUnderline = textButton("U", R.string.rt_underline) { edit?.toggleChar(SpanType.UNDERLINE) }.apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }
    private val bSize = textButton("Aa", R.string.rt_size) {
        sizeRow.visibility = if (sizeRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refresh()
    }
    private val bLeft = iconButton(R.drawable.ic_align_left, R.string.rt_left) { edit?.setAlign(ParaFormat.ALIGN_LEFT) }
    private val bCenter = iconButton(R.drawable.ic_align_center, R.string.rt_center) { edit?.setAlign(ParaFormat.ALIGN_CENTER) }
    private val bRight = iconButton(R.drawable.ic_align_right, R.string.rt_right) { edit?.setAlign(ParaFormat.ALIGN_RIGHT) }
    private val bIndentMore = iconButton(R.drawable.ic_indent_more, R.string.rt_indent_more) { edit?.changeLevel(1) }
    private val bIndentLess = iconButton(R.drawable.ic_indent_less, R.string.rt_indent_less) { edit?.changeLevel(-1) }

    init {
        orientation = VERTICAL
        setBackgroundColor(android.graphics.Color.WHITE)
        visibility = View.GONE
        val density = resources.displayMetrics.density

        // Größenzeile: Aa  −  Schieber  +
        val minus = iconButton(R.drawable.ic_minus, R.string.rt_size_down) { step(false) }
        val plus = iconButton(R.drawable.ic_add, R.string.rt_size_up) { step(true) }
        sizeSeek.apply {
            max = RichTextOps.SIZE_STEPS.size - 1
            progressDrawable = context.getDrawable(R.drawable.seekbar_track)
            thumb = context.getDrawable(R.drawable.seekbar_thumb)
            splitTrack = false
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            contentDescription = context.getString(R.string.rt_size)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating) edit?.setSize(RichTextOps.SIZE_STEPS[progress])
                }

                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        sizeLabel.apply {
            minWidth = (56 * density).toInt()
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(android.graphics.Color.BLACK)
        }
        sizeRow.addView(minus)
        sizeRow.addView(sizeSeek, LayoutParams(0, (48 * density).toInt(), 1f))
        sizeRow.addView(plus)
        sizeRow.addView(sizeLabel)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun divider() = View(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = LayoutParams((1 * density).toInt().coerceAtLeast(1), (28 * density).toInt()).apply {
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        for (v in listOf(bNumbers, bLetters, bBullets, bSquares)) row.addView(v)
        row.addView(divider())
        for (v in listOf(bBold, bItalic, bUnderline, bSize)) row.addView(v)
        row.addView(divider())
        for (v in listOf(bLeft, bCenter, bRight)) row.addView(v)
        row.addView(divider())
        row.addView(bIndentMore)
        row.addView(bIndentLess)

        addView(View(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        addView(sizeRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()))
    }

    /** Verbindet die Leiste mit dem Eingabefeld; `null` blendet sie aus. */
    fun attach(target: RichEditText?) {
        edit?.onStateChanged = null
        edit = target
        if (target == null) {
            visibility = View.GONE
            sizeRow.visibility = View.GONE
            return
        }
        target.onStateChanged = { refresh() }
        visibility = View.VISIBLE
        refresh()
    }

    private fun step(larger: Boolean) {
        val e = edit ?: return
        e.setSize(RichTextOps.nextSize(e.sizePercent(), larger))
    }

    /** Zeigt den Zustand an Cursor bzw. Auswahl (aktive Formate schwarz hinterlegt, gesperrte grau). */
    private fun refresh() {
        val e = edit ?: return
        updating = true
        try {
            bBold.isSelected = e.isOn(SpanType.BOLD)
            bItalic.isSelected = e.isOn(SpanType.ITALIC)
            bUnderline.isSelected = e.isOn(SpanType.UNDERLINE)
            bSize.isSelected = sizeRow.visibility == View.VISIBLE

            val size = e.sizePercent()
            val idx = RichTextOps.SIZE_STEPS.indexOfFirst { it >= size }.let { if (it < 0) RichTextOps.SIZE_STEPS.size - 1 else it }
            sizeSeek.progress = idx
            sizeLabel.text = context.getString(R.string.rt_size_percent, size)

            val paras = e.paraFormats()
            fun allList(kind: Int) = paras.isNotEmpty() && paras.all { it.list == kind }
            bNumbers.isSelected = allList(ParaFormat.LIST_NUMBER)
            bLetters.isSelected = allList(ParaFormat.LIST_LETTER)
            bBullets.isSelected = allList(ParaFormat.LIST_BULLET)
            bSquares.isSelected = allList(ParaFormat.LIST_SQUARE)

            val alignOk = paras.none { it.list != ParaFormat.LIST_NONE }
            for (b in listOf(bLeft, bCenter, bRight)) {
                b.isEnabled = alignOk
                b.isSelected = false
            }
            if (alignOk && paras.isNotEmpty()) {
                val a = paras.first().align
                if (paras.all { it.align == a }) {
                    when (a) {
                        ParaFormat.ALIGN_CENTER -> bCenter
                        ParaFormat.ALIGN_RIGHT -> bRight
                        else -> bLeft
                    }.isSelected = true
                }
            }
            // Einzug: Listenpunkte und linksbündiger Text, nicht Mitte/Rechts (wie in HiNote).
            val indentable = paras.filter { RichTextOps.indentable(it) }
            bIndentMore.isEnabled = indentable.any { it.level < ParaFormat.MAX_LEVEL }
            bIndentLess.isEnabled = indentable.any { it.level > 0 }
        } finally {
            updating = false
        }
    }

    /** Listensymbol: drei Zeilen, je Zeichen (Nummer, Buchstabe, Punkt) links und ein Strich rechts davon. */
    private fun listButton(markers: List<String>, description: Int, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        val tint = context.getColorStateList(R.color.icon_tint)
        val v = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f * d
                typeface = Typeface.DEFAULT_BOLD
                strokeWidth = 2f * d
                strokeCap = Paint.Cap.ROUND
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color = tint.getColorForState(drawableState, tint.defaultColor)
                val w = width.toFloat()
                val h = height.toFloat()
                val rows = markers.size
                val gap = 12f * d
                val top = h / 2f - gap * (rows - 1) / 2f
                val left = w / 2f - 12f * d
                for (i in 0 until rows) {
                    val y = top + gap * i
                    paint.style = Paint.Style.FILL
                    paint.textSize = 9f * d * RichTextOps.markerScale(markers[i])
                    val baseline = y - (paint.ascent() + paint.descent()) / 2f
                    canvas.drawText(markers[i], left, baseline, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(left + 10f * d, y, left + 24f * d, y, paint)
                }
            }
        }
        return v.apply {
            setBackgroundResource(R.drawable.bg_tool)
            contentDescription = context.getString(description)
            isClickable = true
            isFocusable = false
            layoutParams = LayoutParams((48 * d).toInt(), (48 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun textButton(label: String, description: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(context.getColorStateList(R.color.text_tint_selected))
        setBackgroundResource(R.drawable.bg_tool)
        contentDescription = context.getString(description)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt())
        setOnClickListener { onClick() }
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = context.getColorStateList(R.color.icon_tint)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setBackgroundResource(R.drawable.bg_tool)
        contentDescription = context.getString(description)
        isFocusable = false
        layoutParams = LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt())
        setOnClickListener { onClick() }
    }
}
