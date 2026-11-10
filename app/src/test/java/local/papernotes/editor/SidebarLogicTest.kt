package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarLogicTest {
    @Test
    fun jumpAcceptsNumbersInRange() {
        assertEquals(0, SidebarLogic.parseJump("1", 5))
        assertEquals(4, SidebarLogic.parseJump("5", 5))
        assertEquals(2, SidebarLogic.parseJump(" 3 ", 5))
        assertEquals(2, SidebarLogic.parseJump("003", 5))
    }

    @Test
    fun jumpRejectsInvalidInput() {
        assertNull(SidebarLogic.parseJump(null, 5))
        assertNull(SidebarLogic.parseJump("", 5))
        assertNull(SidebarLogic.parseJump("   ", 5))
        assertNull(SidebarLogic.parseJump("0", 5))
        assertNull(SidebarLogic.parseJump("6", 5))
        assertNull(SidebarLogic.parseJump("-1", 5))
        assertNull(SidebarLogic.parseJump("+2", 5))
        assertNull(SidebarLogic.parseJump("2.5", 5))
        assertNull(SidebarLogic.parseJump("abc", 5))
        assertNull(SidebarLogic.parseJump("99999999999999", 5))
        assertNull(SidebarLogic.parseJump("2147483648", 5))
    }

    @Test
    fun itemsPerListPageIsAtLeastOne() {
        assertEquals(3, SidebarLogic.itemsPerListPage(1000, 300))
        assertEquals(1, SidebarLogic.itemsPerListPage(100, 300))
        assertEquals(1, SidebarLogic.itemsPerListPage(0, 300))
    }

    @Test
    fun listPageCountRoundsUp() {
        assertEquals(0, SidebarLogic.listPageCount(0, 3))
        assertEquals(1, SidebarLogic.listPageCount(3, 3))
        assertEquals(2, SidebarLogic.listPageCount(4, 3))
        assertEquals(4, SidebarLogic.listPageCount(10, 3))
    }

    @Test
    fun sliceSplitsIntoListPagesAndClamps() {
        val items = (0 until 7).toList()
        assertEquals(listOf(0, 1, 2), SidebarLogic.slice(items, 3, 0))
        assertEquals(listOf(3, 4, 5), SidebarLogic.slice(items, 3, 1))
        assertEquals(listOf(6), SidebarLogic.slice(items, 3, 2))
        assertEquals(listOf(6), SidebarLogic.slice(items, 3, 9))
        assertEquals(listOf(0, 1, 2), SidebarLogic.slice(items, 3, -4))
        assertEquals(emptyList<Int>(), SidebarLogic.slice(emptyList(), 3, 0))
    }

    @Test
    fun listPageOfFindsTheCurrentEntry() {
        assertEquals(0, SidebarLogic.listPageOf(0, 3))
        assertEquals(1, SidebarLogic.listPageOf(3, 3))
        assertEquals(2, SidebarLogic.listPageOf(8, 3))
        assertEquals(0, SidebarLogic.listPageOf(-1, 3)) // aktuelle Seite nicht in der Liste (Reiter Lesezeichen)
    }

    @Test
    fun bookmarkedIndicesKeepPageOrder() {
        assertEquals(listOf(0, 2, 3), SidebarLogic.bookmarkedIndices(listOf(true, false, true, true)))
        assertEquals(emptyList<Int>(), SidebarLogic.bookmarkedIndices(listOf(false, false)))
        assertEquals(emptyList<Int>(), SidebarLogic.bookmarkedIndices(emptyList()))
    }
}
