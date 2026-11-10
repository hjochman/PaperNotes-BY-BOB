package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenCatalogTest {
    @Test
    fun hasFourPensWithUniqueIdsAndTypes() {
        val pens = PenCatalog.pens
        assertEquals(4, pens.size)
        assertEquals(pens.size, pens.map { it.id }.toSet().size)
        assertEquals(pens.size, pens.map { it.frameworkType }.toSet().size)
    }

    @Test
    fun widthBoundsAreConsistent() {
        for (p in PenCatalog.pens) {
            assertTrue(p.id, p.minWidth >= 1)
            assertTrue(p.id, p.minWidth <= p.defaultWidth)
            assertTrue(p.id, p.defaultWidth <= p.maxWidth)
            assertEquals(p.minWidth, p.clampWidth(p.minWidth - 10))
            assertEquals(p.maxWidth, p.clampWidth(p.maxWidth + 10))
        }
    }

    @Test
    fun markerIsType6AndUnique() {
        val markers = PenCatalog.pens.filter { it.isMarker }
        assertEquals(1, markers.size)
        assertEquals(6, markers[0].frameworkType)
    }

    @Test
    fun markerColorIsHalfTransparentOthersOpaque() {
        val black = 0xFF000000.toInt()
        val marker = PenCatalog.pens.single { it.isMarker }
        assertEquals(0x80000000.toInt(), marker.colorFor(black))
        assertEquals(0x80505050.toInt(), marker.colorFor(0xFF505050.toInt()))
        for (p in PenCatalog.pens.filter { !it.isMarker }) assertEquals(black, p.colorFor(black))
    }

    @Test
    fun lookupAndDefault() {
        assertNotNull(PenCatalog.byFrameworkType(PenCatalog.default.frameworkType))
        assertNull(PenCatalog.byFrameworkType(-1))
        assertEquals(2, PenCatalog.default.frameworkType)
    }
}
