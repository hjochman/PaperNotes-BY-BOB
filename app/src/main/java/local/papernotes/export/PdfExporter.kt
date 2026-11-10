package local.papernotes.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import local.papernotes.editor.PageContentPainter
import local.papernotes.editor.TemplateRenderers
import local.papernotes.editor.templates.TemplateEnv
import local.papernotes.model.Note
import local.papernotes.model.Page
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Schreibt eine Notiz als PDF (`android.graphics.pdf.PdfDocument`, Teil von Android): eine PDF-Seite je Notizseite,
 * Breite 595 pt, Höhe nach Seitenverhältnis ([PdfLayout]). Vorlage und Striche werden wie in `PreviewRenderer` gezeichnet
 * (gleiche Renderer, Linienzüge, Farbe und Breite aus dem Stroke; die Vorschau kennt keine Marker-Transparenz, daher auch
 * hier keine), aber als Vektoren auf dem PDF-Canvas statt in eine Bitmap. B1: Bilder liegen unter den Strichen, Textboxen
 * (Hintergrund, Auszeichnungen) darüber ([PageContentPainter]); Tonaufnahmen erscheinen nicht auf der Seite, optional hängt [PdfAttachments] sie danach als Dateianhänge an die Seiten. Läuft im Hintergrund-Thread; Titel und Strichdaten werden nicht geloggt.
 */
internal class PdfExporter(context: Context) {
    enum class Result { OK, CANCELLED, FAILED }

    private val env = TemplateEnv(context.applicationContext)
    private val content = PageContentPainter()

    /**
     * Schreibt [note] nach [target]. [onPage] wird vor jeder Seite mit (Seite, Seitenzahl) aufgerufen (1-basiert), [isCancelled]
     * vor jeder Seite geprüft. Bei Abbruch oder Fehler wird [target] entfernt.
     */
    fun write(note: Note, target: File, onPage: (Int, Int) -> Unit, isCancelled: () -> Boolean): Result {
        val pages = note.pageIds.mapNotNull { note.pages[it] }
        if (pages.isEmpty()) return Result.FAILED
        val doc = PdfDocument()
        var ok = false
        try {
            for ((i, page) in pages.withIndex()) {
                if (isCancelled()) return Result.CANCELLED
                onPage(i + 1, pages.size)
                val size = PdfLayout.pageSize(page.width, page.height)
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(size.widthPt, size.heightPt, i + 1).create())
                try {
                    drawPage(pdfPage.canvas, page, size.scale, note.media)
                } finally {
                    doc.finishPage(pdfPage)
                }
            }
            if (isCancelled()) return Result.CANCELLED
            FileOutputStream(target).use { doc.writeTo(it) }
            ok = true
            return Result.OK
        } catch (e: IOException) {
            return Result.FAILED
        } catch (e: RuntimeException) {
            return Result.FAILED
        } finally {
            doc.close()
            if (!ok && target.exists()) target.delete()
        }
    }

    private fun drawPage(canvas: Canvas, page: Page, scale: Float, media: Map<String, ByteArray>) {
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.save()
        canvas.scale(scale, scale)
        // Seitenkoordinaten wie in der Vorschau; feine Raster nicht dünner als ca. 0,25 pt.
        TemplateRenderers.forTemplate(env, page.template, minStroke = 0.25f / scale).draw(canvas, page.width, page.height)
        content.drawImages(canvas, page, media, scale)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (s in page.strokes) {
            val pts = s.points
            if (pts.isEmpty()) continue
            paint.color = s.color
            paint.strokeWidth = max(0.25f / scale, s.width)
            path.rewind()
            path.moveTo(pts[0].x, pts[0].y)
            if (pts.size == 1) path.lineTo(pts[0].x + 0.1f, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, paint)
        }
        content.drawTextboxes(canvas, page)
        canvas.restore()
    }
}
