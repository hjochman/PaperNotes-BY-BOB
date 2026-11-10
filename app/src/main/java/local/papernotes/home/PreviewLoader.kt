package local.papernotes.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import local.papernotes.storage.NoteRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Liefert Vorschaubilder für die Startseite. Bevorzugt wird die in der Notiz gespeicherte `preview.jpg`. Fehlt sie
 * (ältere Dateien), entsteht das Bild auf einem Hintergrund-Thread, liegt als PNG im App-Cache (`cacheDir/previews`,
 * Schlüssel = ID + `modified`, LRU-begrenzt auf [PreviewCache.DEFAULT_MAX_BYTES]) und zusätzlich in einem kleinen
 * Arbeitsspeicher-Cache. [onReady] wird im UI-Thread mit der Notiz-ID aufgerufen.
 * Nach [shutdown] kommen keine Rückrufe mehr. Es wird nichts geloggt.
 */
class PreviewLoader(context: Context, private val notesRoot: File) {
    private val appContext = context.applicationContext
    private val diskCache = PreviewCache(File(appContext.cacheDir, "previews"))
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "note-previews").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /** Wird im UI-Thread aufgerufen, sobald für eine Notiz ein Bild bereitsteht. */
    var onReady: ((noteId: String) -> Unit)? = null

    private fun key(id: String, modified: Long) = "$id-$modified"

    /** Fertiges Bild aus dem Arbeitsspeicher oder null (dann [request] aufrufen). Nur im UI-Thread. */
    fun peek(id: String, modified: Long): Bitmap? = memory.get(key(id, modified))

    /** Stößt die Erzeugung an, falls das Bild fehlt. Mehrfaches Anfordern erzeugt nichts doppelt. */
    fun request(id: String, modified: Long) {
        if (closed) return
        val k = key(id, modified)
        if (memory.get(k) != null || !pending.add(k)) return
        try {
            worker.execute {
                try {
                    val bmp = obtain(id, modified)
                    if (bmp != null && !closed) {
                        memory.put(k, bmp)
                        main.post { if (!closed) onReady?.invoke(id) }
                    }
                } catch (e: RuntimeException) {
                    // Vorschau ist nur Beiwerk; ohne Bild bleibt die Kachel leer.
                } catch (e: OutOfMemoryError) {
                    memory.evictAll()
                } finally {
                    pending.remove(k)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.remove(k)
        }
    }

    private fun obtain(id: String, modified: Long): Bitmap? {
        val repo = NoteRepository(notesRoot)
        // 1. Eingebettete Vorschau (preview.jpg im Paket): kein Rendern nötig.
        repo.loadPreview(id)?.let { jpeg ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { return it }
        }
        // 2. Rückfall für Notizen ohne eingebettete Vorschau (ältere Dateien): aus dem Cache oder neu rendern.
        diskCache.find(id, modified)?.let { f ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(f.path, opts)?.let { return it }
        }
        val note = repo.load(id).valueOrNull() ?: return null
        val bmp = PreviewRenderer(appContext).render(note) ?: return null
        val target = diskCache.prepare(id, modified)
        if (target != null && PreviewRenderer.writePng(bmp, target)) {
            diskCache.deleteOtherVersions(id, modified)
            diskCache.prune()
        }
        return bmp
    }

    /** Entfernt die Vorschau-Dateien einer gelöschten Notiz. */
    fun forget(id: String) {
        worker.execute { diskCache.deleteOtherVersions(id, -1L) }
    }

    fun shutdown() {
        closed = true
        onReady = null
        worker.shutdownNow()
        memory.evictAll()
    }

    private companion object {
        const val MEMORY_BYTES = 24 * 1024 * 1024
    }
}
