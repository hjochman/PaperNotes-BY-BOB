package local.papernotes.home

import kotlin.math.max

/** Seitenaufteilung für die Startseite (E-Ink: Blättern statt Scrollen). Reine Rechenlogik, JVM-getestet. */
object Pagination {
    /** Wie viele Zeilen der Höhe [rowHeight] in [availableHeight] passen; mindestens 1. */
    fun rowsPerPage(availableHeight: Int, rowHeight: Int): Int =
        if (rowHeight <= 0 || availableHeight <= 0) 1 else max(1, availableHeight / rowHeight)

    /**
     * Verteilt Elemente mit den Spaltenbreiten [spans] auf Zeilen zu je [columns] Spalten (fortlaufend, ein Element,
     * das nicht mehr in die Zeile passt, beginnt eine neue) und danach auf Seiten zu je [rowsPerPage] Zeilen.
     * Ergebnis: Seiten -> Zeilen -> Indizes in [spans]. Eine leere Eingabe ergibt keine Seite.
     */
    fun paginate(spans: List<Int>, columns: Int, rowsPerPage: Int): List<List<List<Int>>> {
        require(rowsPerPage >= 1)
        return layoutRows(spans, columns).chunked(rowsPerPage)
    }

    /** Verteilt Elemente mit den Spaltenbreiten [spans] fortlaufend auf Zeilen zu je [columns] Spalten (Indizes je Zeile). */
    fun layoutRows(spans: List<Int>, columns: Int): List<List<Int>> {
        require(columns >= 1)
        val rows = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var used = 0
        for ((index, raw) in spans.withIndex()) {
            val span = raw.coerceIn(1, columns)
            if (used + span > columns) {
                rows.add(current)
                current = ArrayList()
                used = 0
            }
            current.add(index)
            used += span
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /**
     * Teilt Zeilen unterschiedlicher Höhe ([rowHeights]) so auf Seiten der Höhe [availableHeight] auf, dass keine Zeile
     * abgeschnitten wird. Jede Seite enthält mindestens eine Zeile. Ergebnis: Seiten -> Zeilenindizes.
     */
    fun splitByHeight(rowHeights: List<Int>, availableHeight: Int): List<List<Int>> {
        val pages = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var used = 0
        for ((index, h) in rowHeights.withIndex()) {
            if (current.isNotEmpty() && used + h > availableHeight) {
                pages.add(current)
                current = ArrayList()
                used = 0
            }
            current.add(index)
            used += h
        }
        if (current.isNotEmpty()) pages.add(current)
        return pages
    }

    /** Hält [page] im Bereich 0 until [pageCount] (bei 0 Seiten: 0). */
    fun clamp(page: Int, pageCount: Int): Int = page.coerceIn(0, max(0, pageCount - 1))

    fun hasPrevious(page: Int): Boolean = page > 0

    fun hasNext(page: Int, pageCount: Int): Boolean = page < pageCount - 1

    /** Nächste Seite; am Ende bleibt es bei der letzten. */
    fun next(page: Int, pageCount: Int): Int = clamp(page + 1, pageCount)

    /** Vorherige Seite; am Anfang bleibt es bei 0. */
    fun previous(page: Int, pageCount: Int): Int = clamp(page - 1, pageCount)

    /** Anzeige "1/2"; ohne Seiten "1/1". */
    fun label(page: Int, pageCount: Int): String = "${clamp(page, pageCount) + 1}/${max(1, pageCount)}"
}
