package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLogicTest {
    @Test
    fun baseScaleIs100Percent() {
        assertEquals(100, ZoomLogic.percent(2f, 2f))
        assertFalse(ZoomLogic.isVisible(100))
    }

    @Test
    fun roundsToSteps() {
        assertEquals(150, ZoomLogic.percent(1.51f, 1f))
        assertEquals(155, ZoomLogic.percent(1.54f, 1f))
        assertEquals(150, ZoomLogic.percent(1.52f, 1f))
        assertTrue(ZoomLogic.isVisible(150))
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
        assertFalse(ZoomLogic.isVisible(ZoomLogic.percent(1.02f, 1f)))
    }
}
