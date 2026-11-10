package local.papernotes.home

import local.papernotes.storage.NoteOrdering
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import local.papernotes.storage.PnoteResult
import local.papernotes.storage.SortOrder

/** Ordnerkachel: Name, Pfad, Notizzahl (mit Unterordnern), jüngste Änderung und bis zu vier neueste Notizen für die Mini-Vorschauen. */
data class FolderItem(
    val path: String,
    val name: String,
    val noteCount: Int,
    val modified: Long,
    val previews: List<NoteSummary>,
)

/** Inhalt eines Ordners; [totalCount] zählt alle Notizen darin einschließlich der Unterordner. */
data class HomeContent(val folders: List<FolderItem>, val notes: List<NoteSummary>, val totalCount: Int)

/** Eintrag der Kachel- oder Listenansicht. */
sealed class Entry {
    data class Folder(val item: FolderItem) : Entry()
    data class Note(val summary: NoteSummary) : Entry()
    data object NewNote : Entry()
}

/** Hauptkategorie der Startseite im Dropdown-Menü. */
enum class HomeCategory {
    ALL,
    UNCATEGORIZED,
    FAVORITES,
}

/** Zählerstände für das Dropdown-Menü der Startseite. */
data class CategoryCounts(
    val all: Int,
    val uncategorized: Int,
    val favorites: Int,
    val trash: Int,
)

object HomeModel {
    const val MAX_FOLDER_PREVIEWS = 4

    /** Liest Ordner und Notizen von [folderPath], Notizen nach [order]. Dateizugriffe: nicht im UI-Thread aufrufen. */
    fun load(repo: NoteRepository, folderPath: String, order: SortOrder = SortOrder.DEFAULT): PnoteResult<HomeContent> {
        val subfolders = when (val r = repo.listFolders(folderPath)) {
            is PnoteResult.Ok -> r.value
            is PnoteResult.Err -> return r
        }
        val notes = when (val r = repo.list(folderPath)) {
            is PnoteResult.Ok -> NoteOrdering.sort(r.value, order)
            is PnoteResult.Err -> return r
        }
        val folders = subfolders.map { f ->
            val all = repo.listAll(f.path).valueOrNull().orEmpty()
            val newest = all.sortedByDescending { it.modified }
            FolderItem(f.path, f.name, all.size, newest.firstOrNull()?.modified ?: 0L, newest.take(MAX_FOLDER_PREVIEWS))
        }
        return PnoteResult.Ok(HomeContent(folders, notes, folders.sumOf { it.noteCount } + notes.size))
    }

    /** Liest nur nicht-kategorisierte Notizen (direkt im Hauptverzeichnis liegende Notizen, ohne Ordner). */
    fun loadUncategorized(repo: NoteRepository, order: SortOrder = SortOrder.DEFAULT): PnoteResult<HomeContent> {
        val notes = when (val r = repo.list("")) {
            is PnoteResult.Ok -> NoteOrdering.sort(r.value, order)
            is PnoteResult.Err -> return r
        }
        return PnoteResult.Ok(HomeContent(emptyList(), notes, notes.size))
    }

    /** Liest alle als Favorit markierten Notizen über alle Ordner hinweg. */
    fun loadFavorites(repo: NoteRepository, order: SortOrder = SortOrder.DEFAULT): PnoteResult<HomeContent> {
        val all = when (val r = repo.listAll("")) {
            is PnoteResult.Ok -> r.value
            is PnoteResult.Err -> return r
        }
        val favs = NoteOrdering.sort(all.filter { it.favorite }, order)
        return PnoteResult.Ok(HomeContent(emptyList(), favs, favs.size))
    }

    /** Ermittelt die Zählerstände für alle Kategorien (Alle, Nicht-kategorisiert, Favoriten, Papierkorb). */
    fun categoryCounts(repo: NoteRepository): CategoryCounts {
        val allNotes = repo.listAll("").valueOrNull().orEmpty()
        val uncategorized = repo.list("").valueOrNull().orEmpty().size
        val favorites = allNotes.count { it.favorite }
        val trash = repo.trashCount()
        return CategoryCounts(
            all = allNotes.size,
            uncategorized = uncategorized,
            favorites = favorites,
            trash = trash,
        )
    }

    /** Reihenfolge: (falls [withNewTile]) die Kachel "+ Kurznotiz", dann Ordner, dann die Notizen (fixierte zuerst). */
    fun entries(content: HomeContent, withNewTile: Boolean): List<Entry> {
        val out = ArrayList<Entry>(content.folders.size + content.notes.size + 1)
        if (withNewTile) out.add(Entry.NewNote)
        content.folders.forEach { out.add(Entry.Folder(it)) }
        content.notes.forEach { out.add(Entry.Note(it)) }
        return out
    }

    /**
     * Teilt [entries] in Seiten auf: Seiten -> Zeilen -> Indizes in [entries]. Zeilen mit einer Ordnerkachel sind höher
     * (siehe [GridMetrics.rowHeight]); es wird nie eine halbe Zeile gezeigt. [availableHeight] ist die Höhe des Inhaltsbereichs.
     */
    fun pageLayout(entries: List<Entry>, metrics: GridMetrics, availableHeight: Int): List<List<List<Int>>> {
        val spans = entries.map { if (metrics.columns == 1) 1 else span(it) }
        val rows = Pagination.layoutRows(spans, metrics.columns)
        val heights = rows.map { row -> metrics.rowHeight(row.any { entries[it] is Entry.Folder }) }
        val usable = (availableHeight - metrics.topPadding).coerceAtLeast(1)
        return Pagination.splitByHeight(heights, usable).map { rowIdx -> rowIdx.map { rows[it] } }
    }

    /** Spaltenbreite eines Eintrags: Ordner belegen [GridMetrics.FOLDER_SPAN] Spalten, alles andere eine. */
    fun span(entry: Entry): Int = if (entry is Entry.Folder) GridMetrics.FOLDER_SPAN else 1

    /** Kürzester Ordnername eines Pfads ("A/B" -> "B"). */
    fun lastSegment(path: String): String = path.substringAfterLast('/')

    fun parent(path: String): String = path.substringBeforeLast('/', "")
}
