package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLogicTest {
    @Test
    fun baseScaleIs100Percent() {
        assertEquals(100, ZoomLogic.percent(2f, 2f))
    }

    @Test
    fun roundsToSteps() {
        assertEquals(150, ZoomLogic.percent(1.51f, 1f))
        assertEquals(155, ZoomLogic.percent(1.54f, 1f))
        assertEquals(150, ZoomLogic.percent(1.52f, 1f))
    }

    @Test
    fun clampsToRange() {
        assertEquals(100, ZoomLogic.percent(0.5f, 1f))
        assertEquals(500, ZoomLogic.percent(9f, 1f))
    }

    @Test
    fun invalidBaseGives100() {
        assertEquals(100, ZoomLogic.percent(3f, 0f))
        assertEquals(100, ZoomLogic.percent(Float.NaN, 1f))
    }

    @Test
    fun smallDeviationStillRoundsTo100() {
        assertEquals(100, ZoomLogic.percent(1.02f, 1f))
    }

    @Test
    fun stepUpWalksThroughStepsAndStopsAtMax() {
        assertEquals(150, ZoomLogic.stepUp(100))
        assertEquals(200, ZoomLogic.stepUp(150))
        assertEquals(500, ZoomLogic.stepUp(400))
        assertEquals(500, ZoomLogic.stepUp(500))
    }

    @Test
    fun stepDownWalksThroughStepsAndStopsAt100() {
        assertEquals(400, ZoomLogic.stepDown(500))
        assertEquals(100, ZoomLogic.stepDown(150))
        assertEquals(100, ZoomLogic.stepDown(100))
    }

    @Test
    fun stepsBetweenStepsSnapToNeighbours() {
        assertEquals(200, ZoomLogic.stepUp(160))
        assertEquals(150, ZoomLogic.stepDown(160))
    }

    @Test
    fun clampOffsetCentersSmallContentAndBoundsLargeContent() {
        assertEquals(50f, ZoomLogic.clampOffset(999f, 200f, 100f), 0f)
        assertEquals(0f, ZoomLogic.clampOffset(30f, 100f, 300f), 0f)
        assertEquals(-200f, ZoomLogic.clampOffset(-999f, 100f, 300f), 0f)
        assertEquals(-50f, ZoomLogic.clampOffset(-50f, 100f, 300f), 0f)
    }

    // Seite 1000 x 2000, Ansicht 500 x 800 (Grundmaßstab 0,5; die Seite ist bei 100 % höher als die Ansicht).
    private val fit = PageTransform(0.5f)

    @Test
    fun zoomTo100GivesFitWidth() {
        val t = ZoomLogic.zoomed(PageTransform(1.5f, -100f, -300f), 500f, 800f, 1000f, 2000f, 100)
        assertEquals(PageTransform(0.5f), t)
    }

    @Test
    fun zoomKeepsViewCenterAndStaysInsidePage() {
        val t = ZoomLogic.zoomed(fit, 500f, 800f, 1000f, 2000f, 200)
        assertEquals(1f, t.scale, 0f)
        // Bildmitte (250, 400) zeigte vorher auf Seitenpunkt (500, 800); dieser bleibt in der Mitte.
        assertEquals(500f, t.viewToPageX(250f), 0.01f)
        assertEquals(800f, t.viewToPageY(400f), 0.01f)
        assertTrue(t.offsetX <= 0f && t.offsetX >= 500f - 1000f)
        assertTrue(t.offsetY <= 0f && t.offsetY >= 800f - 2000f)
    }

    @Test
    fun zoomNearEdgeIsClamped() {
        val atTopLeft = PageTransform(0.5f, 0f, 0f)
        val t = ZoomLogic.zoomed(atTopLeft, 500f, 800f, 1000f, 2000f, 500)
        assertTrue(t.offsetX <= 0f)
        assertTrue(t.offsetY <= 0f)
        assertTrue(t.offsetX >= 500f - 1000f * t.scale)
    }

    @Test
    fun canPanOnlyWhenPageIsLargerThanView() {
        // Bei 100 % ist die Seite 1000 hoch * 0,5 = 1000 > 800: Verschieben senkrecht möglich.
        assertTrue(ZoomLogic.canPan(fit, 500f, 800f, 1000f, 2000f))
        // Kleine Seite, die komplett hineinpasst.
        assertFalse(ZoomLogic.canPan(fit, 500f, 800f, 1000f, 1400f))
        assertTrue(ZoomLogic.canPan(PageTransform(1f), 500f, 800f, 1000f, 1400f))
    }

    @Test
    fun pannedMovesAndClamps() {
        val zoomed = PageTransform(1f, -100f, -200f)
        val moved = ZoomLogic.panned(zoomed, 30f, 40f, 500f, 800f, 1000f, 2000f)
        assertEquals(-70f, moved.offsetX, 0f)
        assertEquals(-160f, moved.offsetY, 0f)
        assertEquals(1f, moved.scale, 0f)
        val clamped = ZoomLogic.panned(zoomed, 999f, 999f, 500f, 800f, 1000f, 2000f)
        assertEquals(0f, clamped.offsetX, 0f)
        assertEquals(0f, clamped.offsetY, 0f)
        val far = ZoomLogic.panned(zoomed, -9999f, -9999f, 500f, 800f, 1000f, 2000f)
        assertEquals(-500f, far.offsetX, 0f)
        assertEquals(-1200f, far.offsetY, 0f)
    }
}
