package local.papernotes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.FrameLayout
import local.papernotes.model.ImageAttachment

/**
 * Ein eingefügtes Bild auf dem Blatt. Position und Größe liegen im Modell in Seitenkoordinaten; die Ansicht arbeitet in
 * Ansichts-Pixeln (Umrechnung über [PageTransform] in [bind]). Das Bild liegt unter der E-Ink-Schicht, damit Striche
 * darüber sichtbar bleiben.
 *
 * Nur wenn [editable] gesetzt ist (Werkzeug Bild), zeichnet die Ansicht einen Rahmen und wertet Berührungen aus, die
 * [PageCanvasView] ihr weiterreicht: Ziehen verschiebt, Ziehen an der Ecke unten rechts ändert die Größe (Seitenverhältnis
 * bleibt), ein Tippen ruft [onTap] auf. Sonst zeichnet sie keinen Rahmen (E-Ink: kein Flackern).
 */
class ImageAttachmentView(context: Context) : FrameLayout(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handlePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val dst = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handlePx = (HANDLE_DP * resources.displayMetrics.density).toInt()

    private var bitmap: Bitmap? = null
    private var bitmapFile: String? = null

    var model: ImageAttachment? = null
        private set
    private var transform = PageTransform.IDENTITY

    /** Wird nach Verschieben oder Größenänderung mit dem neuen Modell (Seitenkoordinaten) aufgerufen. */
    var onGeometryChanged: ((ImageAttachmentView, ImageAttachment) -> Unit)? = null

    /** Wird bei einem Tippen (ohne Ziehen) aufgerufen. */
    var onTap: ((ImageAttachmentView) -> Unit)? = null

    var editable: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var startWidth = 0
    private var startHeight = 0
    private var resizing = false
    private var dragging = false

    init {
        setWillNotDraw(false)
    }

    /** Setzt Bild, Position und Größe aus [img] unter der Abbildung [t]; [bytes] ist die Datei aus `Note.media`. */
    fun bind(img: ImageAttachment, bytes: ByteArray?, t: PageTransform) {
        model = img
        transform = t
        if (bytes != null && bitmapFile != img.filePath) {
            bitmapFile = img.filePath
            bitmap = decode(bytes)
        }
        val lp = (layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        lp.width = t.pageToViewWidth(img.width).toInt().coerceAtLeast(1)
        lp.height = t.pageToViewWidth(img.height).toInt().coerceAtLeast(1)
        lp.leftMargin = t.pageToViewX(img.x).toInt()
        lp.topMargin = t.pageToViewY(img.y).toInt()
        layoutParams = lp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(it, null, dst, paint)
        }
        if (editable) {
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)
            canvas.drawRect((width - handlePx / 2).toFloat(), (height - handlePx / 2).toFloat(), width.toFloat(), height.toFloat(), handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val img = model
        if (!editable || img == null) return false
        val lp = layoutParams as LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startLeft = lp.leftMargin
                startTop = lp.topMargin
                startWidth = lp.width
                startHeight = lp.height
                resizing = event.x > width - handlePx && event.y > height - handlePx
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) dragging = true
                if (dragging) {
                    if (resizing) {
                        val w = (startWidth + dx).toInt().coerceAtLeast(minViewPx())
                        lp.width = w
                        lp.height = (w.toLong() * startHeight / startWidth).toInt().coerceAtLeast(1)
                    } else {
                        lp.leftMargin = (startLeft + dx).toInt()
                        lp.topMargin = (startTop + dy).toInt()
                    }
                    layoutParams = lp
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val moved = img.copy(
                        x = transform.viewToPageX(lp.leftMargin.toFloat()),
                        y = transform.viewToPageY(lp.topMargin.toFloat()),
                        width = transform.viewToPageWidth(lp.width.toFloat()),
                        height = transform.viewToPageWidth(lp.height.toFloat()),
                    )
                    model = moved
                    onGeometryChanged?.invoke(this, moved)
                } else {
                    onTap?.invoke(this)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) bind(img, null, transform)
                dragging = false
            }
        }
        return true
    }

    private fun minViewPx(): Int = (MIN_WIDTH * transform.scale).toInt().coerceAtLeast(1)

    /** Treffer-Test in den Koordinaten des Elternelements. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    companion object {
        const val MIN_WIDTH = 60f
        private const val HANDLE_DP = 32
        private const val MAX_DECODE_DIM = 2048
        private const val MAX_CROP_DIM = 4096
        private const val JPEG_QUALITY = 90

        /**
         * Schneidet den Ausschnitt [f] aus dem Bild [bytes] und kodiert ihn neu (PNG bleibt PNG, sonst JPEG).
         * Liefert Bytes und Dateiendung; null bei unlesbarem Bild oder zu wenig Speicher.
         */
        fun cropBytes(bytes: ByteArray, f: ImageUtil.Fractions): Pair<ByteArray, String>? {
            val (w, h) = sizeOf(bytes) ?: return null
            return try {
                val opts = BitmapFactory.Options().apply { inSampleSize = ImageUtil.sampleSize(w, h, MAX_CROP_DIM) }
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val r = ImageUtil.pixelRect(f, full.width, full.height)
                val part = Bitmap.createBitmap(full, r.left, r.top, r.width, r.height)
                if (part !== full) full.recycle()
                val png = ImageUtil.extensionOf(bytes) == "png"
                val out = ByteArrayOutputStream()
                part.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                part.recycle()
                out.toByteArray() to (if (png) "png" else "jpg")
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /**
         * Stellt ein Foto mit EXIF-Orientation beim Import einmal aufrecht (das Modell kennt keine Drehung, und
         * `BitmapFactory` wertet das Tag nicht aus). Ohne Drehung/Spiegelung kommen die Originalbytes unverändert
         * zurück (kein Qualitätsverlust, kein Dekodieren). Sonst wird höchstens in [MAX_CROP_DIM] Bildpunkten
         * Kantenlänge dekodiert, gedreht und neu kodiert (PNG bleibt PNG, sonst JPEG); das Tag fällt dabei weg, es wird
         * also nicht doppelt gedreht. Null bei unlesbarem Bild oder zu wenig Speicher. Nicht im UI-Thread aufrufen.
         *
         * Bewusst die Framework-Klasse `android.media.ExifInterface` (keine neue Bibliothek, minSdk 29): Lint rät zur
         * AndroidX-Fassung wegen Fehlern in alten Android-Versionen; hier wird nur das Orientation-Tag gelesen.
         */
        @SuppressLint("ExifInterface")
        fun upright(bytes: ByteArray): ByteArray? {
            val o = try {
                val exif = android.media.ExifInterface(ByteArrayInputStream(bytes))
                ImageUtil.orientationOf(
                    exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_UNDEFINED),
                )
            } catch (e: java.io.IOException) {
                ImageUtil.ORIENTATION_NONE
            } catch (e: RuntimeException) {
                ImageUtil.ORIENTATION_NONE
            }
            if (o.isIdentity) return bytes
            val (w, h) = sizeOf(bytes) ?: return null
            return try {
                val opts = BitmapFactory.Options().apply { inSampleSize = ImageUtil.sampleSize(w, h, MAX_CROP_DIM) }
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val m = Matrix()
                if (o.flipHorizontal) m.postScale(-1f, 1f)
                m.postRotate(o.rotationDegrees.toFloat())
                val turned = Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
                if (turned !== full) full.recycle()
                val png = ImageUtil.extensionOf(bytes) == "png"
                val out = ByteArrayOutputStream()
                turned.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                turned.recycle()
                out.toByteArray()
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /** Breite und Höhe in Bildpunkten, ohne das Bild zu laden; null, wenn [bytes] kein lesbares Bild ist. */
        fun sizeOf(bytes: ByteArray): Pair<Int, Int>? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        }

        /** Dekodiert [bytes] höchstens in [MAX_DECODE_DIM] Bildpunkten Kantenlänge; null, wenn es kein Bild ist. */
        fun decode(bytes: ByteArray): Bitmap? {
            val (w, h) = sizeOf(bytes) ?: return null
            val opts = BitmapFactory.Options().apply { inSampleSize = ImageUtil.sampleSize(w, h, MAX_DECODE_DIM) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
