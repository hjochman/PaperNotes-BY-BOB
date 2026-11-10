package local.papernotes.trash

import local.papernotes.home.Pagination

/**
 * Seitenaufteilung der Papierkorb-Liste (E-Ink: Blättern statt Scrollen). Jeder Eintrag ist eine Zeile fester Höhe;
 * die Rechnung stützt sich auf [Pagination] der Startseite. Reine Logik, JVM-getestet.
 */
object TrashPaging {
    /** Seiten -> Indizes der Einträge. Eine leere Liste ergibt keine Seite; jede Seite enthält mindestens einen Eintrag. */
    fun pages(entryCount: Int, availableHeight: Int, rowHeight: Int): List<List<Int>> {
        if (entryCount <= 0) return emptyList()
        val perPage = Pagination.rowsPerPage(availableHeight, rowHeight)
        return Pagination.paginate(List(entryCount) { 1 }, columns = 1, rowsPerPage = perPage).map { rows -> rows.map { it.single() } }
    }
}
