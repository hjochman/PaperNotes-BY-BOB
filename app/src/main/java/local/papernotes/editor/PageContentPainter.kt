package local.papernotes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import local.papernotes.model.ImageAttachment
import local.papernotes.model.Page
import local.papernotes.model.Textbox

/**
 * Zeichnet die Inhalte einer Seite außer Vorlage und Strichen (B1): eingefügte Bilder und Textboxen (mit Hintergrund und
 * Auszeichnungen aus A3). Gemeinsam genutzt von der Kachelvorschau, der Seitenvorschau und dem PDF-Export. Alle Methoden
 * zeichnen in Seitenkoordinaten; der Aufrufer skaliert die [Canvas] vorher. Reihenfolge wie im Editor: Bilder unter den
 * Strichen, Textboxen darüber. Tonaufnahmen werden nie ins Bild oder PDF gezeichnet; die App zeigt sie als eigenes
 * Symbol mit Zahl (Übersicht, Seitenleiste).
 * Muss nicht im UI-Thread laufen. Es wird nichts geloggt.
 */
internal class PageContentPainter {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    /**
     * Zeichnet die Bilder der Seite. [scale] ist der Maßstab Seitenkoordinaten -> Ausgabe-Pixel; er bestimmt, wie groß
     * ein Bild höchstens dekodiert wird (kleine Vorschauen laden keine großen Bitmaps).
     */
    fun drawImages(canvas: Canvas, page: Page, media: Map<String, ByteArray>, scale: Float) {
        for (img in page.images) {
            if (img.width <= 0f || img.height <= 0f) continue
            val bytes = media[img.filePath] ?: continue
            val bmp = decode(bytes, img, scale) ?: continue
            rect.set(img.x, img.y, img.x + img.width, img.y + img.height)
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            bmp.recycle()
        }
    }

    private fun decode(bytes: ByteArray, img: ImageAttachment, scale: Float): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val maxDim = PageContentLogic.decodeMaxDim(img.width, img.height, scale)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ImageUtil.sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Zeichnet alle Textboxen der Seite. */
    fun drawTextboxes(canvas: Canvas, page: Page) {
        for (tb in page.textboxes) drawTextbox(canvas, tb)
    }

    private fun drawTextbox(canvas: Canvas, tb: Textbox) {
        if (tb.width <= 0f || tb.height <= 0f || tb.fontSize <= 0f) return
        if (tb.bgColor != 0) {
            fill.color = tb.bgColor
            canvas.drawRect(tb.x, tb.y, tb.x + tb.width, tb.y + tb.height, fill)
        }
        val innerWidth = (tb.width - 2 * TEXT_PADDING).toInt()
        if (innerWidth <= 0 || tb.text.isEmpty()) return
        textPaint.textSize = tb.fontSize
        val text = RichTextAndroid.build(tb, tb.fontSize * TextboxView.INDENT_EM)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, innerWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.withClip(tb.x, tb.y, tb.x + tb.width, tb.y + tb.height) {
            translate(tb.x + TEXT_PADDING, tb.y + TEXT_PADDING)
            layout.draw(this)
        }
    }

    private companion object {
        const val TEXT_PADDING = 4f
    }
}

/** Reine Rechenhilfen für [PageContentPainter] (kein Android-Bezug, JVM-testbar). */
object PageContentLogic {
    private const val MIN_DECODE_DIM = 64
    private const val MAX_DECODE_DIM = 2048

    /**
     * Längste Kante in Bildpunkten, bis zu der ein Bild der Größe [width] x [height] (Seitenkoordinaten) für die Ausgabe
     * mit Maßstab [scale] dekodiert wird: doppelte Zielgröße, damit es nicht unscharf wird, höchstens 2048.
     */
    fun decodeMaxDim(width: Float, height: Float, scale: Float): Int {
        val target = maxOf(width, height) * scale * 2f
        return if (target.isNaN()) MIN_DECODE_DIM else target.toInt().coerceIn(MIN_DECODE_DIM, MAX_DECODE_DIM)
    }
}
