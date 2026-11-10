package local.papernotes.widget

import local.papernotes.home.DateLabels
import local.papernotes.storage.NoteOrdering
import local.papernotes.storage.NoteSummary

/** Eine Zeile des Widgets (nur Anzeigedaten). */
data class WidgetRow(val noteId: String, val title: String, val dateText: String, val pinned: Boolean, val favorite: Boolean)

/**
 * Reine Logik des Widgets ohne Android-Bezug: welche Notizen (je Quelle), wie viele Zeilen in die Höhe passen,
 * ob Kopf- und Fußzeile erscheinen, Anzeigetexte der Zeilen. Maße in dp wie in `widget_notes.xml`.
 */
object WidgetLogic {
    const val ROW_DP = 48
    const val HEADER_DP = 48
    const val FOOTER_DP = 48
    const val LINE_DP = 1

    /** Rahmen: 2 x (2dp Linie + 4dp transparenter Außenrand `widget_inset`). */
    const val FRAME_DP = 12

    /** Muster für das Datum neben der Uhr (Wochentag, Tag, Monat) - die Reihenfolge liefert das Gerätegebietsschema. */
    const val CLOCK_DATE_SKELETON = "EEEdMMM"

    /**
     * Kopfzeile erscheint, wenn Titel, Uhr, "Neue Notiz" oder Akku/Benachrichtigungszähler an sind (der Status steht
     * immer in der Kopfzeile, nie in einer eigenen Zeile).
     */
    fun showHeader(config: WidgetConfig): Boolean = config.showClock || config.showTitle || config.showNewButton || config.usesStatus

    /** Fußzeile "Alle Notizen" erscheint, wenn der Titel (der sonst die Startseite öffnet) aus ist. */
    fun showFooter(config: WidgetConfig): Boolean = !config.showTitle

    /**
     * Anzahl der Zeilen, die in [heightDp] passen, höchstens [WidgetConfig.count]. Ist die Höhe unbekannt (<= 0),
     * gilt die eingestellte Anzahl. Ohne Kopfzeile gibt es mindestens eine Zeile, sonst kann die Antwort 0 sein.
     */
    fun visibleRows(heightDp: Int, config: WidgetConfig): Int {
        val wanted = config.count.coerceIn(1, WidgetConfig.MAX_ROWS)
        if (heightDp <= 0) return wanted
        var free = heightDp - FRAME_DP
        if (showHeader(config)) free -= HEADER_DP + LINE_DP
        if (showFooter(config)) free -= FOOTER_DP + LINE_DP
        var rows = free / ROW_DP
        if (rows < 1 && !showHeader(config)) rows = 1
        return rows.coerceIn(0, wanted)
    }

    /**
     * Wählt und sortiert die Notizen für das Widget und begrenzt sie auf [limit].
     *
     * Option [WidgetConfig.favoritesFirst] (bei der Quelle FAVORITES ohne Wirkung, dort sind ohnehin alle Einträge Favoriten):
     * Die Kandidaten der Quelle werden um ALLE Favoriten aus [all] ergänzt (auch außerhalb des gewählten Ordners; [all]
     * enthält keinen Papierkorb). Zuerst stehen die Favoriten in der Reihenfolge der Quelle (RECENT/PINNED: `modified`
     * absteigend; FOLDER: wie die Startseite, also Fixierte zuerst, dann `modified` absteigend), danach die übrigen
     * Einträge der Quelle in deren Reihenfolge. Jede Notiz erscheint nur einmal. Gibt es mehr Favoriten als [limit],
     * füllen die ersten Favoriten alle Zeilen. Ist die Option aus, ändert sich nichts gegenüber der reinen Quelle.
     */
    fun select(all: List<NoteSummary>, config: WidgetConfig, limit: Int): List<NoteSummary> {
        if (limit <= 0) return emptyList()
        val max = minOf(limit, WidgetConfig.MAX_ROWS)
        val ordered = order(all.filter { matches(it, config) }, config.source)
        if (!config.favoritesFirst || config.source == WidgetSource.FAVORITES) return ordered.take(max)
        val favorites = order(all.filter { it.favorite }, config.source)
        val favoriteIds = favorites.mapTo(HashSet()) { it.id }
        return (favorites + ordered.filter { it.id !in favoriteIds }).take(max)
    }

    private fun matches(note: NoteSummary, config: WidgetConfig): Boolean = when (config.source) {
        WidgetSource.RECENT -> true
        WidgetSource.FAVORITES -> note.favorite
        WidgetSource.PINNED -> note.pinned
        WidgetSource.FOLDER -> inFolder(note.folderPath, config.folder)
    }

    private fun order(notes: List<NoteSummary>, source: WidgetSource): List<NoteSummary> =
        if (source == WidgetSource.FOLDER) NoteOrdering.sort(notes) else byModified(notes)

    /** Zeilen mit Titel (einzeilig, ohne Steuerzeichen; leer = [untitled]) und Datumstext wie auf der Startseite. */
    fun rows(notes: List<NoteSummary>, labels: DateLabels, untitled: String): List<WidgetRow> = notes.map {
        val title = it.title.map { c -> if (c.isISOControl()) ' ' else c }.joinToString("").trim().ifEmpty { untitled }
        WidgetRow(it.id, title, labels.format(it.modified), it.pinned, it.favorite)
    }

    private fun byModified(notes: List<NoteSummary>): List<NoteSummary> =
        notes.sortedWith(compareByDescending<NoteSummary> { it.modified }.thenBy { it.id })

    /** Der Ordner "" umfasst alles; sonst der Ordner selbst und seine Unterordner ("A" trifft "A/B", nicht "AB"). */
    private fun inFolder(path: String, folder: String): Boolean =
        folder.isEmpty() || path == folder || path.startsWith("$folder/")
}
