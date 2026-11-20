package local.papernotes.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Durchsichtige Anzeige über der Schreibfläche, berührungslos: Balken am rechten und unteren Rand zeigen, welchen Teil
 * der gezoomten Seite man sieht (nur bei Zoom bzw. wenn die Seite größer als die Ansicht ist), und beim Verschieben
 * zieht ein Pfeil wie ein Gummiband vom Aufsetzpunkt zum Finger. Die Stiftschicht wird nicht angefasst.
 *
 * E-Ink: Der Balken ändert sich nur bei Zoom, Größenänderung und Loslassen; der Pfeil nur, wenn die Spitze sich um
 * [ARROW_REDRAW_DP] bewegt hat ([PanIndicatorLogic.shouldRedrawArrow]). Keine Timer, keine Animation.
 */
class PanIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val rect = RectF()

    private var vertical: Thumb? = null
    private var horizontal: Thumb? = null

    private var arrowVisible = false
    private var startX = 0f
    private var startY = 0f
    private var endX = Float.NaN
    private var endY = Float.NaN

    init {
        // Keine eigene Berührung: Ereignisse laufen an der Anzeige vorbei.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Aktualisiert die Balken für [transform] einer Seite der Größe [pageW] x [pageH] in einer Ansicht [viewW] x [viewH]
     * (die Größen kommen vom Aufrufer, die eigene Größe kann während des Layouts noch veraltet sein). Zeichnet nur bei Änderung.
     */
    fun setViewport(transform: PageTransform, pageW: Int, pageH: Int, viewW: Float, viewH: Float) {
        val v = PanIndicatorLogic.thumb(transform.offsetY, viewH, pageH * transform.scale)
        val h = PanIndicatorLogic.thumb(transform.offsetX, viewW, pageW * transform.scale)
        if (v == vertical && h == horizontal) return
        vertical = v
        horizontal = h
        invalidate()
    }

    /** Zeigt den Pfeil von ([sx], [sy]) nach ([ex], [ey]) (Ansichtspixel); zeichnet nur bei genug Bewegung neu. */
    fun showArrow(sx: Float, sy: Float, ex: Float, ey: Float) {
        if (hypot(ex - sx, ey - sy) < MIN_ARROW_DP * density) {
            hideArrow()
            return
        }
        if (arrowVisible && !PanIndicatorLogic.shouldRedrawArrow(endX, endY, ex, ey, ARROW_REDRAW_DP * density)) return
        arrowVisible = true
        startX = sx
        startY = sy
        endX = ex
        endY = ey
        invalidate()
    }

    fun hideArrow() {
        if (!arrowVisible) return
        arrowVisible = false
        endX = Float.NaN
        endY = Float.NaN
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val thickness = BAR_DP * density
        val margin = BAR_MARGIN_DP * density
        val minLen = BAR_MIN_DP * density
        vertical?.let {
            var top = it.start * height
            var bottom = it.end * height
            if (bottom - top < minLen) bottom = (top + minLen).coerceAtMost(height.toFloat()).also { b -> top = (b - minLen).coerceAtLeast(0f) }
            rect.set(width - margin - thickness, top, width - margin, bottom)
            canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, barPaint)
        }
        horizontal?.let {
            var left = it.start * width
            var right = it.end * width
            if (right - left < minLen) right = (left + minLen).coerceAtMost(width.toFloat()).also { r -> left = (r - minLen).coerceAtLeast(0f) }
            rect.set(left, height - margin - thickness, right, height - margin)
            canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, barPaint)
        }
        if (arrowVisible) drawArrow(canvas)
    }

    private fun drawArrow(canvas: Canvas) {
        canvas.drawCircle(startX, startY, DOT_DP * density, dotPaint)
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)
        val angle = atan2(endY - startY, endX - startX)
        val head = HEAD_DP * density
        for (side in intArrayOf(-1, 1)) {
            val a = angle + Math.PI.toFloat() + side * HEAD_ANGLE
            canvas.drawLine(endX, endY, endX + head * cos(a), endY + head * sin(a), arrowPaint)
        }
    }

    companion object {
        /** Die Spitze muss sich so weit bewegen, bis der Pfeil neu gezeichnet wird. */
        const val ARROW_REDRAW_DP = 24f

        /** Kürzere Pfeile werden nicht gezeigt. */
        private const val MIN_ARROW_DP = 12f
        private const val DOT_DP = 5f
        private const val HEAD_DP = 16f
        private const val HEAD_ANGLE = 0.45f
        private const val BAR_DP = 4f
        private const val BAR_MARGIN_DP = 3f
        private const val BAR_MIN_DP = 24f
    }
}
