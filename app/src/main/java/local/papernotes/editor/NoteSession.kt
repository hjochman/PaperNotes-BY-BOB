package local.papernotes.editor

import local.papernotes.model.AudioAttachment
import local.papernotes.model.ImageAttachment
import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import local.papernotes.model.Page
import local.papernotes.model.Stroke
import local.papernotes.model.Textbox
import local.papernotes.storage.NoteTitles
import local.papernotes.storage.PnoteResult
import java.util.UUID
import java.util.concurrent.Executor

/** Speicher-Schnittstelle (im Test ersetzbar). Wird nie im UI-Thread aufgerufen. */
fun interface NoteStore {
    fun save(note: Note): PnoteResult<Unit>
}

/** Verzögerte Ausführung im UI-Thread (im Test ersetzbar). */
interface SaveScheduler {
    /** Plant [task] nach [delayMs] und gibt ein Token zum Abbrechen zurück. */
    fun postDelayed(delayMs: Long, task: Runnable): Any

    fun cancel(token: Any)
}

/**
 * Zustand einer geöffneten Notiz im Editor: Seiten, aktuelle Seite, Verläufe, Speichern. Ohne Android-Bezug.
 *
 * Threading: alle Methoden nur im UI-Thread. Das Schreiben der Datei läuft auf [writer] (in der App ein
 * Ein-Thread-Executor, damit Schreibvorgänge in Reihenfolge ablaufen); es wird eine unveränderliche Momentaufnahme
 * der [Note] übergeben, daher gibt es keine geteilten veränderlichen Daten. Atomares Schreiben macht [NoteStore]
 * (PnoteCodec: temporäre Datei, dann verschieben).
 *
 * Speicherzeitpunkte: [save] (für onPause/onStop), jeder Seitenwechsel, Seite hinzufügen/löschen, und
 * [debounceMs] nach der letzten Änderung: [DEBOUNCE_MS] (5 s) ohne Medien, [DEBOUNCE_MEDIA_MS] (30 s), sobald die Notiz
 * Bilder oder Aufnahmen enthält. Jedes Speichern schreibt die ganze Datei neu (alle Medien, bis 64 MB); das seltener zu
 * tun spart Akku und Heap. Kompromiss: Stürzt die App ab, gehen bei Notizen mit Medien die Änderungen seit der letzten
 * Schreibpause von 30 s (statt 5 s) verloren; Seitenwechsel, Seite hinzufügen/löschen und onPause/onStop/onDestroy
 * speichern weiter sofort. Die Wartezeit wird bei jeder Änderung neu gewählt und wechselt daher mit dem ersten bzw.
 * letzten Medium mit; es gibt keinen zusätzlichen Timer, nur den einen Debounce-Lauf.
 */
class NoteSession(
    initial: Note,
    private val store: NoteStore,
    private val writer: Executor,
    private val scheduler: SaveScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimit: Int = PageHistory.DEFAULT_LIMIT,
) {
    init {
        require(initial.pageIds.isNotEmpty()) { "Notiz ohne Seiten" }
    }

    var note: Note = initial
        private set

    var pageIndex: Int = 0
        private set

    private val histories = HashMap<String, PageHistory>()
    private var dirty = false
    private var pendingToken: Any? = null

    /** Wird (auf dem Schreib-Thread!) mit dem Ergebnis jedes Speicherversuchs aufgerufen; true = Erfolg. */
    @Volatile
    var onSaveResult: ((Boolean) -> Unit)? = null

    val pageCount: Int get() = note.pageIds.size
    val currentPage: Page get() = note.pages.getValue(note.pageIds[pageIndex])
    val currentHistory: PageHistory get() = historyFor(currentPage)
    val hasUnsavedChanges: Boolean get() = dirty

    fun currentPageNumberText(): String = "${pageIndex + 1}/$pageCount"

    private fun historyFor(page: Page): PageHistory =
        histories.getOrPut(page.id) { PageHistory(page.strokes, historyLimit) }

    /** Setzt den Titel der Notiz. False bei ungültigem Titel oder ohne Änderung. Speichert über den Debounce. */
    fun rename(title: String): Boolean {
        if (!NoteTitles.isValid(title) || title == note.title) return false
        note = note.copy(title = title)
        markDirty()
        return true
    }

    /** Übernimmt die Strichliste der aktuellen Seite ins Modell und plant das Speichern. */
    fun onStrokesChanged(strokes: List<Stroke>) {
        val page = currentPage
        if (page.strokes == strokes) return
        replacePage(page.copy(strokes = strokes))
        markDirty()
    }

    /**
     * Übernimmt die Bilder der aktuellen Seite ins Modell und plant das Speichern. [addedMedia] sind neue Dateien für
     * [Note.media]; Dateien, auf die keine Seite mehr verweist, werden dabei entfernt.
     */
    fun onImagesChanged(images: List<ImageAttachment>, addedMedia: Map<String, ByteArray> = emptyMap()) {
        val page = currentPage
        if (page.images == images && addedMedia.isEmpty()) return
        replacePage(page.copy(images = images))
        if (addedMedia.isNotEmpty()) note = note.copy(media = note.media + addedMedia)
        pruneMedia()
        markDirty()
    }

    /**
     * Hängt eine fertige Tonaufnahme an die Seite [pageId] und legt die Bytes in [Note.media] ab. False, wenn die Seite
     * inzwischen nicht mehr existiert. Speichert über den Debounce.
     */
    fun onAudioAdded(pageId: String, audio: AudioAttachment, bytes: ByteArray): Boolean {
        val page = note.pages[pageId] ?: return false
        replacePage(page.copy(audios = page.audios + audio))
        note = note.copy(media = note.media + (audio.filePath to bytes))
        markDirty()
        return true
    }

    /**
     * Übernimmt die Tonaufnahmen der aktuellen Seite ins Modell und plant das Speichern.
     * Dateien, auf die keine Seite mehr verweist, werden dabei aus [Note.media] entfernt.
     */
    fun onAudiosChanged(audios: List<AudioAttachment>) {
        val page = currentPage
        replacePage(page.copy(audios = audios))
        pruneMedia()
        markDirty()
    }

    /** Entfernt Mediendateien, die von keinem Bild und keiner Tonaufnahme einer Seite mehr verwendet werden. */
    private fun pruneMedia() {
        if (note.media.isEmpty()) return
        val used = note.pages.values.flatMap { p -> p.images.map { it.filePath } + p.audios.map { it.filePath } }.toSet()
        val kept = note.media.filterKeys { it in used }
        if (kept.size != note.media.size) note = note.copy(media = kept)
    }

    /** Übernimmt die Textboxen der aktuellen Seite ins Modell und plant das Speichern. */
    fun onTextboxesChanged(textboxes: List<Textbox>) {
        val page = currentPage
        if (page.textboxes == textboxes) return
        replacePage(page.copy(textboxes = textboxes))
        markDirty()
    }

    /** Wechselt zur Seite [index] (0-basiert) und speichert vorher. Gibt false bei ungültigem Index oder gleicher Seite. */
    fun goToPage(index: Int): Boolean {
        if (index !in 0 until pageCount || index == pageIndex) return false
        save()
        pageIndex = index
        return true
    }

    /**
     * Fügt hinter der aktuellen Seite eine leere Seite ein (gleiche Größe und Vorlage) und wechselt dorthin.
     * Annahme: Die neue Seite übernimmt die Vorlage der aktuellen Seite (so ist es in HiNote üblich).
     * Mit [atEnd] wird sie stattdessen als letzte Seite angehängt (Seitenleiste "Neue Seite").
     */
    fun addPage(atEnd: Boolean = false): Page {
        val cur = currentPage
        val page = Page(
            id = UUID.randomUUID().toString(),
            template = cur.template.ifEmpty { NoteFactory.TEMPLATE_BLANK },
            width = cur.width,
            height = cur.height,
            strokes = emptyList(),
            bookmarked = false,
        )
        val at = if (atEnd) pageCount else pageIndex + 1
        val ids = note.pageIds.toMutableList().also { it.add(at, page.id) }
        note = note.copy(pageIds = ids, pages = note.pages + (page.id to page), modified = clock())
        pageIndex = at
        dirty = true
        save()
        return page
    }

    /**
     * Setzt die Vorlage der aktuellen Seite, mit [allPages] aller Seiten. Striche bleiben unverändert. Nur Vorlagen aus
     * dem [TemplateCatalog] sind erlaubt. Gibt false zurück, wenn nichts zu ändern war. Speichert über den Debounce.
     */
    fun setTemplate(template: String, allPages: Boolean): Boolean {
        if (!TemplateCatalog.isKnown(template)) return false
        val targets = if (allPages) note.pageIds else listOf(note.pageIds[pageIndex])
        var changed = false
        var pages = note.pages
        for (id in targets) {
            val page = pages.getValue(id)
            if (page.template != template) {
                pages = pages + (id to page.copy(template = template))
                changed = true
            }
        }
        if (!changed) return false
        note = note.copy(pages = pages)
        markDirty()
        return true
    }

    /** Löscht die aktuelle Seite. Die letzte verbleibende Seite kann nicht gelöscht werden (false). */
    fun deletePage(): Boolean = deletePageAt(pageIndex)

    /**
     * Löscht die Seite [index] (0-basiert). Die letzte verbleibende Seite und ungültige Indizes ergeben false. Bleibt die
     * aktuelle Seite erhalten, zeigt [pageIndex] weiter auf sie; wird sie gelöscht, rückt die folgende (bzw. letzte) nach.
     */
    fun deletePageAt(index: Int): Boolean {
        if (pageCount <= 1 || index !in 0 until pageCount) return false
        val id = note.pageIds[index]
        note = note.copy(
            pageIds = note.pageIds.filterIndexed { i, _ -> i != index },
            pages = note.pages - id,
            modified = clock(),
        )
        pruneMedia()
        histories.remove(id)
        if (index < pageIndex) pageIndex -= 1
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        dirty = true
        save()
        return true
    }

    /** Setzt oder entfernt das Lesezeichen der Seite [index]. False bei ungültigem Index oder ohne Änderung. Speichert über den Debounce. */
    fun setBookmark(index: Int, bookmarked: Boolean): Boolean {
        if (index !in 0 until pageCount) return false
        val page = note.pages.getValue(note.pageIds[index])
        if (page.bookmarked == bookmarked) return false
        replacePage(page.copy(bookmarked = bookmarked))
        markDirty()
        return true
    }

    /** Speichert sofort (nicht blockierend) und bricht einen geplanten Debounce-Lauf ab. Ohne Änderungen: nichts. */
    fun save() {
        cancelPending()
        if (!dirty) return
        dirty = false
        val snapshot = note
        writer.execute {
            val ok = try {
                store.save(snapshot) is PnoteResult.Ok
            } catch (e: Exception) {
                false
            }
            onSaveResult?.invoke(ok)
        }
    }

    /** Bricht geplante Läufe ab und speichert ausstehende Änderungen (für onDestroy). */
    fun close() = save()

    private fun markDirty() {
        note = note.copy(modified = clock())
        dirty = true
        cancelPending()
        pendingToken = scheduler.postDelayed(debounceMs(note.media.isNotEmpty())) {
            pendingToken = null
            save()
        }
    }

    private fun cancelPending() {
        pendingToken?.let { scheduler.cancel(it) }
        pendingToken = null
    }

    private fun replacePage(page: Page) {
        note = note.copy(pages = note.pages + (page.id to page))
    }

    companion object {
        /** Wartezeit nach der letzten Änderung bei Notizen ohne Medien. */
        const val DEBOUNCE_MS = 5_000L

        /** Wartezeit nach der letzten Änderung bei Notizen mit Medien (Bilder/Aufnahmen werden bei jedem Speichern neu verpackt). */
        const val DEBOUNCE_MEDIA_MS = 30_000L

        /** Wartezeit bis zum automatischen Speichern; [hasMedia] = [Note.media] ist nicht leer. */
        fun debounceMs(hasMedia: Boolean): Long = if (hasMedia) DEBOUNCE_MEDIA_MS else DEBOUNCE_MS
    }
}
