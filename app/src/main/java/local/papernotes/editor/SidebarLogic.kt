package local.papernotes.editor

import local.papernotes.home.Pagination
import kotlin.math.max

/**
 * Reine Logik der Seitenleiste im Editor (JVM-getestet): Sprungfeld, Aufteilung der Vorschauliste auf Listenseiten
 * (Blättern statt Scrollen) und Lesezeichenliste. Alle Seitenindizes sind 0-basiert, angezeigt wird 1-basiert.
 */
object SidebarLogic {
    private const val MAX_DIGITS = 9

    /**
     * Wertet die Eingabe des Sprungfelds aus: Ziffern 1 bis [pageCount] (Leerraum außen wird ignoriert, führende Nullen
     * sind erlaubt). Ergebnis ist der 0-basierte Seitenindex oder null bei leerer, ungültiger oder außerhalb liegender Eingabe.
     */
    fun parseJump(input: String?, pageCount: Int): Int? {
        val text = input?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_DIGITS || !text.all { it in '0'..'9' }) return null
        val number = text.toInt()
        return if (number in 1..pageCount) number - 1 else null
    }

    /** Wie viele Zeilen der Höhe [rowHeight] in [availableHeight] passen; mindestens 1. */
    fun itemsPerListPage(availableHeight: Int, rowHeight: Int): Int = Pagination.rowsPerPage(availableHeight, rowHeight)

    /** Anzahl der Listenseiten für [itemCount] Einträge mit je [perPage] pro Seite; eine leere Liste hat keine Seite. */
    fun listPageCount(itemCount: Int, perPage: Int): Int {
        require(perPage >= 1)
        return if (itemCount <= 0) 0 else (itemCount + perPage - 1) / perPage
    }

    /** Die Einträge von [items], die auf der Listenseite [listPage] stehen; eine ungültige Listenseite wird begrenzt. */
    fun slice(items: List<Int>, perPage: Int, listPage: Int): List<Int> {
        val count = listPageCount(items.size, perPage)
        if (count == 0) return emptyList()
        val page = Pagination.clamp(listPage, count)
        return items.drop(page * perPage).take(perPage)
    }

    /** Listenseite, auf der der Eintrag an Position [position] steht (für das Öffnen auf der aktuellen Seite). */
    fun listPageOf(position: Int, perPage: Int): Int {
        require(perPage >= 1)
        return max(0, position) / perPage
    }

    /** Indizes der Seiten mit Lesezeichen in Seitenreihenfolge. */
    fun bookmarkedIndices(bookmarked: List<Boolean>): List<Int> =
        bookmarked.withIndex().filter { it.value }.map { it.index }
}
