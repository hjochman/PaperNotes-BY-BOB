package local.papernotes.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Auswahlrahmen zum Zuschneiden: liegt genau über dem Bild; ein Ziehen von Ecke zu Ecke legt den Ausschnitt fest.
 * Beim Loslassen wird [onSelected] mit dem Rahmen (Ansichtspunkte der Überlagerung) aufgerufen, null bei zu kleinem Rahmen.
 */
class CropOverlayView(context: Context, private val onSelected: (ImageUtil.Fractions?) -> Unit) : View(context) {
    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f
    private var active = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, paint)
        if (active) canvas.drawRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x1 = event.x
                y1 = event.y
                x2 = x1
                y2 = y1
                active = true
            }
            MotionEvent.ACTION_MOVE -> {
                x2 = event.x
                y2 = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                x2 = event.x
                y2 = event.y
                active = false
                invalidate()
                onSelected(ImageUtil.fractions(x1, y1, x2, y2, width.toFloat(), height.toFloat()))
            }
            MotionEvent.ACTION_CANCEL -> {
                active = false
                onSelected(null)
            }
        }
        return true
    }
}
