package local.papernotes.editor.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.os.Handler
import android.os.Looper
import local.papernotes.editor.TemplateCatalog
import local.papernotes.editor.TemplateRenderers
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Vorschaubilder der Vorlagen für den Dialog. Sie entstehen auf einem Hintergrund-Thread aus denselben Renderern wie
 * das Blatt (die Seite wird in voller Größe gezeichnet und herunterskaliert) und werden im Speicher gehalten:
 * 16 Bitmaps in `RGB_565`, etwa 30 KB je Stück. Kein Netz, keine Dateien.
 */
object TemplateThumbnails {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "template-thumbs").apply { isDaemon = true } }

    private fun key(id: String, w: Int, h: Int, pageW: Int, pageH: Int) = "$id|$w|$h|$pageW|$pageH|${Locale.getDefault()}"

    /** Liefert das fertige Bild oder null. Nur im UI-Thread aufrufen. */
    fun peek(id: String, w: Int, h: Int, pageW: Int, pageH: Int): Bitmap? = cache[key(id, w, h, pageW, pageH)]

    /**
     * Erzeugt fehlende Vorschauen für alle Vorlagen im Hintergrund. [onReady] (UI-Thread, optional) meldet je fertiges Bild.
     * Bereits geplante oder fertige Bilder werden nicht doppelt erzeugt.
     */
    fun prepareAll(context: Context, w: Int, h: Int, pageW: Int, pageH: Int, onReady: ((String) -> Unit)? = null) {
        if (w <= 0 || h <= 0 || pageW <= 0 || pageH <= 0) return
        val env = TemplateEnv(context.applicationContext)
        for (spec in TemplateCatalog.all) {
            val k = key(spec.id, w, h, pageW, pageH)
            if (cache.containsKey(k) || !pending.add(k)) continue
            worker.execute {
                try {
                    val bmp = render(env, spec.id, w, h, pageW, pageH)
                    cache[k] = bmp
                    if (onReady != null) main.post { onReady(spec.id) }
                } catch (e: Exception) {
                    // Vorschau ist nur Beiwerk; ohne Bild bleibt die Kachel leer.
                } finally {
                    pending.remove(k)
                }
            }
        }
    }

    private fun render(env: TemplateEnv, id: String, w: Int, h: Int, pageW: Int, pageH: Int): Bitmap {
        val bmp = createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.scale(w.toFloat() / pageW, h.toFloat() / pageH)
        // Mindestens 1 Vorschau-Pixel breite Linien, damit feine Raster sichtbar bleiben.
        TemplateRenderers.forTemplate(env, id, minStroke = pageW.toFloat() / w).draw(canvas, pageW, pageH)
        return bmp
    }
}
