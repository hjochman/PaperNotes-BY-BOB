package local.papernotes.home

import android.content.Context
import local.papernotes.model.Note
import local.papernotes.model.Page

/**
 * Hängt vor dem Speichern die Vorschau (JPEG der ersten Seite) an eine Notiz, damit sie als `preview.jpg` im
 * `.pnote` landet. Gerendert wird nur, wenn sich Seite 1 gegenüber der letzten Vorschau geändert hat; sonst wird das
 * zuletzt erzeugte Bild wiederverwendet. Nur auf dem Schreib-Thread benutzen (ein Thread, keine eigene Sperre).
 * Schlägt das Rendern fehl, bleibt eine vorhandene Vorschau unverändert und die Notiz wird trotzdem gespeichert.
 */
class PreviewStamper(context: Context) {
    private val renderer = PreviewRenderer(context)
    private var lastPage: Page? = null
    private var lastBytes: ByteArray? = null

    fun stamp(note: Note): Note {
        val first = note.pageIds.firstOrNull()?.let { note.pages[it] } ?: return note
        if (first == lastPage && lastBytes != null) return note.copy(preview = lastBytes)
        val bytes = try {
            renderer.renderJpeg(note)
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return note
        lastPage = first
        lastBytes = bytes
        return note.copy(preview = bytes)
    }
}
