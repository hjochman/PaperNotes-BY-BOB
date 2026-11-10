package local.papernotes.editor.templates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import local.papernotes.R
import local.papernotes.editor.TemplateRenderer
import kotlin.math.max

/** Bildschirmabhängige Größen und Beschriftungstexte für Vorlagen. */
class TemplateEnv(context: Context) {
    val dp: Float
    val mm: Float
    val textPx: Float
    private val texts: Array<String>

    init {
        val dm = context.resources.displayMetrics
        dp = dm.density
        // Millimeter aus der physischen Pixeldichte; unplausible Werte fallen auf die dp-Dichte (160dpi = 1dp) zurück.
        mm = (if (dm.xdpi in 80f..800f) dm.xdpi else dm.density * 160f) / 25.4f
        textPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, dm)
        texts = Array(TemplateLabel.values().size) { context.getString(labelRes(TemplateLabel.values()[it])) }
    }

    fun text(label: TemplateLabel): String = texts[label.ordinal]

    private fun labelRes(l: TemplateLabel): Int = when (l) {
        TemplateLabel.DONE -> R.string.tl_done
        TemplateLabel.TIME -> R.string.tl_time
        TemplateLabel.TODOS -> R.string.tl_todos
        TemplateLabel.SENDER -> R.string.tl_sender
        TemplateLabel.RECIPIENT -> R.string.tl_recipient
        TemplateLabel.SUBJECT -> R.string.tl_subject
        TemplateLabel.DATE -> R.string.tl_date
        TemplateLabel.TITLE -> R.string.tl_title
        TemplateLabel.PARTICIPANTS -> R.string.tl_participants
        TemplateLabel.AGENDA -> R.string.tl_agenda
        TemplateLabel.NOTES -> R.string.tl_notes
        TemplateLabel.DECISIONS -> R.string.tl_decisions
        TemplateLabel.MON -> R.string.tl_mon
        TemplateLabel.TUE -> R.string.tl_tue
        TemplateLabel.WED -> R.string.tl_wed
        TemplateLabel.THU -> R.string.tl_thu
        TemplateLabel.FRI -> R.string.tl_fri
        TemplateLabel.SAT -> R.string.tl_sat
        TemplateLabel.SUN -> R.string.tl_sun
        TemplateLabel.TASK -> R.string.tl_task
        TemplateLabel.PRIORITY -> R.string.tl_priority
        TemplateLabel.DUE -> R.string.tl_due
        TemplateLabel.ERROR -> R.string.tl_error
        TemplateLabel.CAUSE -> R.string.tl_cause
        TemplateLabel.SOLUTION -> R.string.tl_solution
        TemplateLabel.AUTHOR -> R.string.tl_author
        TemplateLabel.QUOTES -> R.string.tl_quotes
        TemplateLabel.CUES -> R.string.tl_cues
        TemplateLabel.SUMMARY -> R.string.tl_summary
        TemplateLabel.TOPIC -> R.string.tl_topic
        TemplateLabel.URGENT -> R.string.tl_urgent
        TemplateLabel.NOT_URGENT -> R.string.tl_not_urgent
        TemplateLabel.IMPORTANT -> R.string.tl_important
        TemplateLabel.NOT_IMPORTANT -> R.string.tl_not_important
        TemplateLabel.STRENGTHS -> R.string.tl_strengths
        TemplateLabel.WEAKNESSES -> R.string.tl_weaknesses
        TemplateLabel.OPPORTUNITIES -> R.string.tl_opportunities
        TemplateLabel.THREATS -> R.string.tl_threats
        TemplateLabel.POINT -> R.string.tl_point
        TemplateLabel.ACTION -> R.string.tl_action
    }
}

/**
 * Zeichnet eine Vorlage aus [TemplateGeometry]. Weiß als Grund, graue Linien, keine Flächen. Paints sind vorberechnet;
 * die Geometrie wird nur bei Größenwechsel neu berechnet. [minStroke] ist die kleinste Strichstärke in Zeichen-Pixeln
 * (für verkleinerte Vorschauen, damit feine Linien sichtbar bleiben).
 */
class ShapeTemplateRenderer(
    private val id: String,
    private val env: TemplateEnv,
    minStroke: Float = 1f,
) : TemplateRenderer {
    private val fine = line(Color.rgb(0xDD, 0xDD, 0xDD), max(minStroke, 0.5f * env.dp))
    private val main = line(Color.rgb(0xB0, 0xB0, 0xB0), max(minStroke, 0.75f * env.dp))
    private val head = line(Color.rgb(0x70, 0x70, 0x70), max(minStroke, 1f * env.dp))
    private val dotPaint = Paint().apply {
        color = Color.rgb(0x90, 0x90, 0x90)
        strokeWidth = max(minStroke * 1.5f, 1.8f * env.dp)
        strokeCap = Paint.Cap.ROUND
    }
    private val circlePaint = line(Color.rgb(0xA0, 0xA0, 0xA0), max(minStroke, 1f * env.dp)).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x80, 0x80, 0x80)
        textSize = env.textPx
    }

    private var shapes = TemplateShapes.EMPTY
    private var shapesW = -1
    private var shapesH = -1

    private fun line(c: Int, w: Float) = Paint().apply {
        color = c
        strokeWidth = w
    }

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.WHITE)
        if (width <= 0 || height <= 0) return
        if (width != shapesW || height != shapesH) {
            shapes = TemplateGeometry.build(id, TemplateMetrics(width.toFloat(), height.toFloat(), env.dp, env.mm, env.textPx))
            shapesW = width
            shapesH = height
        }
        val s = shapes
        if (s.isEmpty) return
        canvas.drawLines(s.fine, fine)
        canvas.drawLines(s.main, main)
        canvas.drawLines(s.head, head)
        if (s.dots.isNotEmpty()) canvas.drawPoints(s.dots, dotPaint)
        var i = 0
        while (i + 2 < s.circles.size) {
            canvas.drawCircle(s.circles[i], s.circles[i + 1], s.circles[i + 2], circlePaint)
            i += 3
        }
        for (item in s.labels) {
            textPaint.textAlign = when {
                item.centered -> Paint.Align.CENTER
                item.right -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            canvas.drawText(env.text(item.label), item.x, item.y, textPaint)
        }
    }
}
