package local.papernotes.hinote

import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import java.io.IOException
import java.io.InputStream

/**
 * Eine zu importierende Quelle. [open] liefert einen frischen Strom (oder null); der Aufrufer-Thread schließt ihn.
 * [folder] = Zielordner der Notiz, [lastModified] = Dateizeitstempel (0 = unbekannt), [inSubfolder] = lag in einem Unterverzeichnis.
 */
class HinoteImportSource(
    val name: String,
    val folder: String = HinoteImporter.TARGET_FOLDER,
    val lastModified: Long = 0,
    val inSubfolder: Boolean = false,
    val open: () -> InputStream?,
)

/** Zählung des Laufs: importiert, übersprungen (bereits importiert), davon mit Warnungen, fehlgeschlagen. */
data class HinoteImportReport(val imported: Int, val skipped: Int, val withWarnings: Int, val failed: Int)

/**
 * Importiert mehrere `.hinote`-Dateien in das [NoteRepository] (Zielordner je Quelle, Standard [HinoteImporter.TARGET_FOLDER]).
 * Läuft auf dem aufrufenden (Hintergrund-)Thread. Zwei Durchgänge, damit nie mehr als ein Export im Speicher liegt:
 *
 * 1. Jede Quelle lesen und nur `noteId` und Seitenanzahl merken. Mehrere Dateien derselben `noteId` im Lauf: nur eine
 *    gewinnt (Regel in [HinoteImportPlanner]: neuester Dateizeitstempel, dann meiste Seiten); die übrigen zählen als übersprungen.
 * 2. Die Gewinner erneut lesen und importieren.
 *
 * Doppelimport: Eine Notiz mit gleicher `importSource` (`hinote:<noteId>`) im Bestand wird übersprungen (nicht verschoben).
 * Fehler einer Datei brechen den Lauf nicht ab und tragen keine Namen oder Pfade. Der Fortschritt zählt nur Durchgang 2.
 */
object HinoteImportRunner {
    fun run(
        sources: List<HinoteImportSource>,
        repo: NoteRepository,
        fallbackTitle: String,
        now: Long = System.currentTimeMillis(),
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): HinoteImportReport {
        val known = HashSet<String>()
        repo.listAll("").valueOrNull()?.forEach { s -> s.importSource?.let { known.add(it) } }
        var imported = 0
        var skipped = 0
        var withWarnings = 0
        var failed = 0

        // Durchgang 1: noteId + Seitenanzahl
        val candidates = ArrayList<HinoteImportPlanner.Candidate>()
        for ((i, source) in sources.withIndex()) {
            if (isCancelled()) break
            val container = readContainer(source)
            if (container == null) {
                failed++
                continue
            }
            candidates.add(
                HinoteImportPlanner.Candidate(i, container.noteId, source.lastModified, container.pages.size, source.inSubfolder),
            )
        }
        val winners = HinoteImportPlanner.selectWinners(candidates)
        skipped += candidates.size - winners.size
        val todo = sources.indices.filter { it in winners }

        // Durchgang 2: Gewinner importieren
        for ((n, index) in todo.withIndex()) {
            if (isCancelled()) break
            onProgress(n + 1, todo.size)
            val source = sources[index]
            val container = readContainer(source)
            if (container == null) {
                failed++
                continue
            }
            val sourceId = HinoteImporter.SOURCE_PREFIX + container.noteId
            if (sourceId in known) {
                skipped++
                continue
            }
            val result = HinoteImporter.importNote(container, source.folder, fallbackTitle, now)
            if (repo.save(result.note) is PnoteResult.Ok) {
                known.add(sourceId)
                imported++
                if (result.warnings > 0) withWarnings++
            } else {
                failed++
            }
        }
        return HinoteImportReport(imported, skipped, withWarnings, failed)
    }

    private fun readContainer(source: HinoteImportSource): HinoteContainer? = try {
        source.open()?.use { HinoteContainerReader.read(it) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }?.valueOrNull()
}
