package local.papernotes.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {
    @Test
    fun rowsPerPageFollowsHeight() {
        assertEquals(3, Pagination.rowsPerPage(1000, 300))
        assertEquals(1, Pagination.rowsPerPage(100, 300)) // mindestens eine Zeile
        assertEquals(1, Pagination.rowsPerPage(0, 300))
        assertEquals(1, Pagination.rowsPerPage(1000, 0))
        assertEquals(4, Pagination.rowsPerPage(1200, 300))
    }

    @Test
    fun paginateGridWithFourColumns() {
        val spans = List(10) { 1 }
        val pages = Pagination.paginate(spans, columns = 4, rowsPerPage = 2)
        assertEquals(2, pages.size)
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)), pages[0])
        assertEquals(listOf(listOf(8, 9)), pages[1])
    }

    @Test
    fun paginateExactFitHasNoEmptyPage() {
        val pages = Pagination.paginate(List(8) { 1 }, columns = 4, rowsPerPage = 2)
        assertEquals(1, pages.size)
        assertEquals(2, pages[0].size)
    }

    @Test
    fun paginateEmptyIsNoPage() {
        assertTrue(Pagination.paginate(emptyList(), 4, 3).isEmpty())
    }

    @Test
    fun wideItemsStartNewRowWhenTheyDoNotFit() {
        // Ordnerkacheln (Breite 2) und Notizen (Breite 1) in 4 Spalten
        val spans = listOf(2, 2, 2, 1, 1, 1, 1)
        val pages = Pagination.paginate(spans, columns = 4, rowsPerPage = 10)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4), listOf(5, 6)), pages.single())
        // Passt ein breites Element nicht mehr in die Restzeile, beginnt es eine neue Zeile
        val wrap = Pagination.paginate(listOf(1, 1, 1, 2), columns = 4, rowsPerPage = 10)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), wrap.single())
        // Listenansicht: eine Spalte
        val list = Pagination.paginate(List(5) { 1 }, columns = 1, rowsPerPage = 2)
        assertEquals(3, list.size)
        assertEquals(listOf(listOf(4)), list[2])
    }

    @Test
    fun spanLargerThanColumnsIsClamped() {
        val pages = Pagination.paginate(listOf(9, 1), columns = 2, rowsPerPage = 5)
        assertEquals(listOf(listOf(0), listOf(1)), pages.single())
    }

    @Test
    fun navigationStopsAtBoundaries() {
        assertEquals(0, Pagination.previous(0, 3))
        assertEquals(1, Pagination.next(0, 3))
        assertEquals(2, Pagination.next(2, 3))
        assertEquals(1, Pagination.previous(2, 3))
        assertFalse(Pagination.hasPrevious(0))
        assertTrue(Pagination.hasPrevious(1))
        assertTrue(Pagination.hasNext(0, 2))
        assertFalse(Pagination.hasNext(1, 2))
        assertFalse(Pagination.hasNext(0, 0))
    }

    @Test
    fun clampKeepsPageValidAfterListShrinks() {
        assertEquals(1, Pagination.clamp(5, 2))
        assertEquals(0, Pagination.clamp(3, 0))
        assertEquals(0, Pagination.clamp(-4, 3))
    }

    @Test
    fun labelShowsOneBasedPage() {
        assertEquals("1/2", Pagination.label(0, 2))
        assertEquals("2/2", Pagination.label(1, 2))
        assertEquals("1/1", Pagination.label(0, 0))
        assertEquals("2/2", Pagination.label(9, 2))
    }
}
