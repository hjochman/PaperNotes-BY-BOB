package local.papernotes.storage

import java.text.Collator

/** Wählbare Sortierung der Startseite. Fixierte Notizen stehen immer zuerst. */
enum class SortOrder {
    MODIFIED_DESC,
    MODIFIED_ASC,
    CREATED_DESC,
    TITLE_ASC,
    TITLE_DESC,
    ;

    companion object {
        val DEFAULT = MODIFIED_DESC

        /** Unbekannte oder fehlende Werte (z. B. aus alten Einstellungen) ergeben [DEFAULT]. */
        fun parse(name: String?): SortOrder = values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Reihenfolge in Notizlisten: fixierte zuerst, dann nach [SortOrder], bei Gleichstand nach ID (stabil). */
object NoteOrdering {
    val comparator: Comparator<NoteSummary> = comparator(SortOrder.DEFAULT)

    /** Vergleich von Titeln nach Sprachregeln des Geräts (Umlaute, Groß-/Kleinschreibung). */
    fun titleCollator(): Comparator<String> {
        val collator = Collator.getInstance()
        return Comparator { a, b -> collator.compare(a, b) }
    }

    fun comparator(order: SortOrder, titles: Comparator<String> = titleCollator()): Comparator<NoteSummary> {
        val key: Comparator<NoteSummary> = when (order) {
            SortOrder.MODIFIED_DESC -> compareByDescending<NoteSummary> { it.modified }
            SortOrder.MODIFIED_ASC -> compareBy<NoteSummary> { it.modified }
            SortOrder.CREATED_DESC -> compareByDescending<NoteSummary> { it.created }
            SortOrder.TITLE_ASC -> Comparator { a, b -> titles.compare(a.title, b.title) }
            SortOrder.TITLE_DESC -> Comparator { a, b -> titles.compare(b.title, a.title) }
        }
        return compareByDescending<NoteSummary> { it.pinned }.then(key).thenBy { it.id }
    }

    fun sort(notes: List<NoteSummary>): List<NoteSummary> = notes.sortedWith(comparator)

    fun sort(notes: List<NoteSummary>, order: SortOrder): List<NoteSummary> = notes.sortedWith(comparator(order))
}
