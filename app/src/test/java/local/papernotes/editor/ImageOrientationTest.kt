package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Logik der EXIF-Orientation (Wert -> Drehung/Spiegelung). Der Android-Teil (`ExifInterface`, `Matrix`) ist nicht Teil der JVM-Tests. */
class ImageOrientationTest {

    @Test
    fun allEightExifValuesMapToExpectedTransform() {
        assertEquals(ImageUtil.Orientation(0, false), ImageUtil.orientationOf(1))
        assertEquals(ImageUtil.Orientation(0, true), ImageUtil.orientationOf(2))
        assertEquals(ImageUtil.Orientation(180, false), ImageUtil.orientationOf(3))
        assertEquals(ImageUtil.Orientation(180, true), ImageUtil.orientationOf(4))
        assertEquals(ImageUtil.Orientation(270, true), ImageUtil.orientationOf(5))
        assertEquals(ImageUtil.Orientation(90, false), ImageUtil.orientationOf(6))
        assertEquals(ImageUtil.Orientation(90, true), ImageUtil.orientationOf(7))
        assertEquals(ImageUtil.Orientation(270, false), ImageUtil.orientationOf(8))
    }

    @Test
    fun normalUndefinedAndUnknownValuesAreIdentity() {
        for (v in listOf(0, 1, 9, 10, -1, 255, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val o = ImageUtil.orientationOf(v)
            assertTrue("Wert $v", o.isIdentity)
            assertFalse("Wert $v", o.swapsDimensions)
            assertEquals("Wert $v", ImageUtil.ORIENTATION_NONE, o)
        }
    }

    @Test
    fun onlyValues2To8AreNotIdentity() {
        for (v in 2..8) assertFalse("Wert $v", ImageUtil.orientationOf(v).isIdentity)
    }

    @Test
    fun dimensionsSwapExactlyForQuarterTurns() {
        val swapped = (1..8).filter { ImageUtil.orientationOf(it).swapsDimensions }
        assertEquals(listOf(5, 6, 7, 8), swapped)
        assertEquals(3000 to 4000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(6)))
        assertEquals(3000 to 4000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(8)))
        assertEquals(3000 to 4000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(5)))
        assertEquals(3000 to 4000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(7)))
        assertEquals(4000 to 3000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(2)))
        assertEquals(4000 to 3000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(3)))
        assertEquals(4000 to 3000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(4)))
        assertEquals(4000 to 3000, ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(0)))
    }

    @Test
    fun orientedSizeKeepsAspectOfInitialSizeConsistent() {
        // Hochformat-Foto, als Querformat gespeichert (Wert 6): das eingefügte Bild muss hochkant werden.
        val (w, h) = ImageUtil.orientedSize(4000, 3000, ImageUtil.orientationOf(6))
        val (iw, ih) = ImageUtil.initialSize(w, h, 1000f, 1500f)
        assertTrue(ih > iw)
    }

    @Test
    fun neutralOrientationIsSharedInstance() {
        assertSame(ImageUtil.ORIENTATION_NONE, ImageUtil.orientationOf(1))
    }

    // Pixelprobe: wendet "erst spiegeln, dann im Uhrzeigersinn drehen" auf ein Raster an und vergleicht mit der
    // EXIF-Definition der acht Werte (Spezifikation: Wert = wie das gespeicherte Bild angezeigt werden muss).

    private fun flipH(g: List<List<Char>>): List<List<Char>> = g.map { it.reversed() }

    private fun rotateCw(g: List<List<Char>>): List<List<Char>> {
        val h = g.size
        val w = g[0].size
        return List(w) { r -> List(h) { c -> g[h - 1 - c][r] } }
    }

    private fun apply(g: List<List<Char>>, o: ImageUtil.Orientation): List<List<Char>> {
        var out = if (o.flipHorizontal) flipH(g) else g
        repeat(o.rotationDegrees / 90) { out = rotateCw(out) }
        return out
    }

    private fun grid(vararg rows: String): List<List<Char>> = rows.map { it.toList() }

    @Test
    fun transformMatchesExifDefinitionOnPixelGrid() {
        val src = grid("ab", "cd", "ef") // 2 breit, 3 hoch
        val expected = mapOf(
            1 to grid("ab", "cd", "ef"),
            2 to grid("ba", "dc", "fe"), // waagerecht gespiegelt
            3 to grid("fe", "dc", "ba"), // 180 Grad
            4 to grid("ef", "cd", "ab"), // senkrecht gespiegelt
            5 to grid("ace", "bdf"), // Transponieren
            6 to grid("eca", "fdb"), // 90 Grad im Uhrzeigersinn
            7 to grid("fdb", "eca"), // an der Nebendiagonale gespiegelt
            8 to grid("bdf", "ace"), // 90 Grad gegen den Uhrzeigersinn
        )
        for ((v, want) in expected) {
            val got = apply(src, ImageUtil.orientationOf(v))
            assertEquals("Wert $v", want, got)
            val (w, h) = ImageUtil.orientedSize(2, 3, ImageUtil.orientationOf(v))
            assertEquals("Breite bei Wert $v", w, got[0].size)
            assertEquals("Höhe bei Wert $v", h, got.size)
        }
    }
}
