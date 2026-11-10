package local.papernotes.editor

import android.content.Context
import android.graphics.Canvas
import local.papernotes.editor.templates.ShapeTemplateRenderer
import local.papernotes.editor.templates.TemplateEnv
import android.view.View

/** Zeichnet die Vorlage einer Seite (Hintergrund). Austauschbar. */
interface TemplateRenderer {
    fun draw(canvas: Canvas, width: Int, height: Int)
}

object TemplateRenderers {
    /** Renderer für [name]; unbekannte IDs ergeben die leere Vorlage ("blank"). */
    fun forTemplate(env: TemplateEnv, name: String, minStroke: Float = 1f): TemplateRenderer =
        ShapeTemplateRenderer(TemplateCatalog.lookup(name).id, env, minStroke)
}

/**
 * Hintergrund-View unter der Schreibschicht. Zeichnet nur die Vorlage, keine Striche. Die Vorlage wird in
 * Seitenkoordinaten berechnet (Breite [pageWidth], Höhe = sichtbare Höhe der View in Seiteneinheiten) und über
 * [transform] (Seite -> Ansicht) auf die View gelegt, damit sie mit den Strichen deckungsgleich bleibt.
 * Bewusst die sichtbare Höhe und nicht die Seitenhöhe: Solange es kein Scrollen gibt (3-3), bleibt das Layout
 * höhenabhängiger Vorlagen (Woche, Monat, Cornell, Punkte) wie bisher, und alte Notizen sehen unverändert aus.
 */
class PageBackgroundView(context: Context) : View(context) {
    private val env = TemplateEnv(context)
    private val renderers = HashMap<String, TemplateRenderer>()
    private var current = TemplateCatalog.DEFAULT_ID
    private var pageWidth = 0
    private var transform = PageTransform.IDENTITY

    /** Wechselt die Vorlage (Renderer werden je ID einmal angelegt) und zeichnet sofort neu. */
    fun setTemplate(id: String) {
        val known = TemplateCatalog.lookup(id).id
        if (known != current) {
            current = known
            invalidate()
        }
    }

    /** Setzt Seitenbreite und Abbildung Seite -> Ansicht (ohne Aufruf zeichnet die View mit ihrer eigenen Größe). */
    fun setPageGeometry(pageWidth: Int, transform: PageTransform) {
        if (pageWidth == this.pageWidth && transform == this.transform) return
        this.pageWidth = pageWidth
        this.transform = transform
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = renderers.getOrPut(current) { TemplateRenderers.forTemplate(env, current) }
        if (pageWidth <= 0) {
            r.draw(canvas, width, height)
            return
        }
        val visibleHeight = Math.round(height / transform.scale).coerceAtLeast(1)
        canvas.save()
        canvas.translate(transform.offsetX, transform.offsetY)
        canvas.scale(transform.scale, transform.scale)
        r.draw(canvas, pageWidth, visibleHeight)
        canvas.restore()
    }
}
