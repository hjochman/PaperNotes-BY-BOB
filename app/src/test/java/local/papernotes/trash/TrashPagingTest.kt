package local.papernotes.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPagingTest {
    @Test
    fun emptyListHasNoPage() {
        assertTrue(TrashPaging.pages(0, 1000, 100).isEmpty())
        assertTrue(TrashPaging.pages(-1, 1000, 100).isEmpty())
    }

    @Test
    fun splitsByRowsThatFit() {
        val pages = TrashPaging.pages(7, availableHeight = 300, rowHeight = 100)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6)), pages)
    }

    @Test
    fun exactFitHasNoTrailingEmptyPage() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), TrashPaging.pages(4, 200, 100))
        assertEquals(1, TrashPaging.pages(2, 200, 100).size)
    }

    @Test
    fun tooSmallAreaStillShowsOneEntryPerPage() {
        assertEquals(listOf(listOf(0), listOf(1)), TrashPaging.pages(2, 10, 100))
        assertEquals(listOf(listOf(0), listOf(1)), TrashPaging.pages(2, 0, 100))
        assertEquals(listOf(listOf(0), listOf(1)), TrashPaging.pages(2, 1000, 0)) // ungültige Zeilenhöhe: eine Zeile je Seite
    }
}
