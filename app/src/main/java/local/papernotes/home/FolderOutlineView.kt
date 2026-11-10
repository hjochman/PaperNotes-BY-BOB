package local.papernotes.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Ordnersymbol der Startseite: Umriss mit Reiter oben (nur Strichzeichnung auf Weiß, keine Schatten).
 * Der Inhalt (Mini-Vorschauen) wird vom Aufrufer darübergelegt. [tabHeight] ist die Höhe des Reiters in Pixeln.
 */
internal class FolderOutlineView(context: Context) : View(context) {
    var tabHeight: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF555555.toInt()
        strokeWidth = 1.2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = line.strokeWidth
        val r = 10f * density
        val tab = tabHeight.toFloat()
        val left = inset
        val right = w - inset
        val bottom = h - inset
        val tabStart = w * 0.28f
        val slope = 8f * density
        path.rewind()
        path.moveTo(left, tab + r)
        path.quadTo(left, tab, left + r, tab)
        path.lineTo(tabStart, tab)
        path.lineTo(tabStart + slope, inset)
        path.lineTo(right - r, inset)
        path.quadTo(right, inset, right, inset + r)
        path.lineTo(right, bottom - r)
        path.quadTo(right, bottom, right - r, bottom)
        path.lineTo(left + r, bottom)
        path.quadTo(left, bottom, left, bottom - r)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
    }
}
