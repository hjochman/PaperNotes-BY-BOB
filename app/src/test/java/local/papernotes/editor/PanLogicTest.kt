package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenGuardTest {
    @Test
    fun freshGuardAllowsFinger() {
        assertTrue(PenGuard().fingerAllowed(0L))
    }

    @Test
    fun penDownBlocksFinger() {
        val g = PenGuard(800)
        g.penDown(1000)
        assertTrue(g.isDown)
        assertFalse(g.fingerAllowed(1000))
        assertFalse(g.fingerAllowed(60_000))
    }

    @Test
    fun fingerStaysBlockedForGraceTimeAfterPenUp() {
        val g = PenGuard(800)
        g.penDown(1000)
        g.penUp(2000)
        assertFalse(g.isDown)
        assertFalse(g.fingerAllowed(2000))
        assertFalse(g.fingerAllowed(2799))
        assertTrue(g.fingerAllowed(2800))
    }

    @Test
    fun hoveringPenBlocksFingerForGraceTime() {
        val g = PenGuard(800)
        g.penNear(5000)
        assertFalse(g.fingerAllowed(5500))
        assertTrue(g.fingerAllowed(5800))
        g.penNear(5700)
        assertFalse(g.fingerAllowed(5800))
    }
}

class PanTrackerTest {
    private fun tracker() = PanTracker(slop = 10f)

    @Test
    fun smallMovementIsATapNotAPan() {
        val p = tracker()
        p.begin(100f, 100f)
        assertEquals(PanStep.NONE, p.move(105f, 104f))
        assertFalse(p.isPanning)
        assertFalse(p.swallowing)
    }

    @Test
    fun movementBeyondSlopStartsAndThenContinues() {
        val p = tracker()
        p.begin(100f, 100f)
        assertEquals(PanStep.STARTED, p.move(100f, 130f))
        assertTrue(p.isPanning)
        assertTrue(p.swallowing)
        assertEquals(PanStep.MOVED, p.move(90f, 160f))
        assertEquals(-10f, p.totalDx, 0f)
        assertEquals(60f, p.totalDy, 0f)
    }

    @Test
    fun movingBackIntoSlopAfterStartKeepsPanning() {
        val p = tracker()
        p.begin(0f, 0f)
        p.move(0f, 50f)
        assertEquals(PanStep.MOVED, p.move(0f, 1f))
        assertTrue(p.isPanning)
    }

    @Test
    fun abortBeforeStartCancelsQuietly() {
        val p = tracker()
        p.begin(0f, 0f)
        p.abort()
        assertEquals(PanStep.NONE, p.move(0f, 100f))
        assertFalse(p.swallowing)
    }

    @Test
    fun abortAfterStartSwallowsUntilEndButNeverApplies() {
        val p = tracker()
        p.begin(0f, 0f)
        p.move(0f, 50f)
        p.abort()
        assertFalse(p.isPanning)
        assertTrue(p.swallowing)
        assertEquals(PanStep.NONE, p.move(0f, 90f))
        p.end()
        assertFalse(p.swallowing)
    }

    @Test
    fun endResetsAndIdleIgnoresMoves() {
        val p = tracker()
        p.begin(0f, 0f)
        p.move(0f, 50f)
        p.end()
        assertEquals(0f, p.totalDy, 0f)
        assertEquals(PanStep.NONE, p.move(0f, 99f))
        assertFalse(p.swallowing)
    }
}

class PanIndicatorLogicTest {
    @Test
    fun noThumbWhenContentFitsView() {
        assertEquals(null, PanIndicatorLogic.thumb(0f, 800f, 800f))
        assertEquals(null, PanIndicatorLogic.thumb(0f, 800f, 600f))
        assertEquals(null, PanIndicatorLogic.thumb(0f, 0f, 600f))
    }

    @Test
    fun thumbAtTopCoversVisibleShare() {
        val t = PanIndicatorLogic.thumb(0f, 800f, 2000f)!!
        assertEquals(0f, t.start, 0f)
        assertEquals(0.4f, t.end, 0.0001f)
    }

    @Test
    fun thumbFollowsOffsetAndStopsAtEnd() {
        val mid = PanIndicatorLogic.thumb(-600f, 800f, 2000f)!!
        assertEquals(0.3f, mid.start, 0.0001f)
        assertEquals(0.7f, mid.end, 0.0001f)
        val bottom = PanIndicatorLogic.thumb(-1200f, 800f, 2000f)!!
        assertEquals(0.6f, bottom.start, 0.0001f)
        assertEquals(1f, bottom.end, 0.0001f)
        val beyond = PanIndicatorLogic.thumb(-5000f, 800f, 2000f)!!
        assertEquals(1f, beyond.start, 0f)
        assertEquals(1f, beyond.end, 0f)
    }

    @Test
    fun arrowRedrawsOnlyAfterEnoughMovement() {
        assertTrue(PanIndicatorLogic.shouldRedrawArrow(Float.NaN, Float.NaN, 10f, 10f, 24f))
        assertFalse(PanIndicatorLogic.shouldRedrawArrow(100f, 100f, 110f, 110f, 24f))
        assertTrue(PanIndicatorLogic.shouldRedrawArrow(100f, 100f, 100f, 124f, 24f))
        assertTrue(PanIndicatorLogic.shouldRedrawArrow(100f, 100f, 60f, 100f, 24f))
    }
}
