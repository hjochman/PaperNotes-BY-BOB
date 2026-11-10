package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLogicTest {
    @Test
    fun formatsMinutesAndHours() {
        assertEquals("0:00", AudioLogic.formatTime(0))
        assertEquals("0:09", AudioLogic.formatTime(9_999))
        assertEquals("1:05", AudioLogic.formatTime(65_000))
        assertEquals("1:01:01", AudioLogic.formatTime(3_661_000))
        assertEquals("0:00", AudioLogic.formatTime(-5))
    }

    @Test
    fun seekIsClampedToTrack() {
        assertEquals(0, AudioLogic.seekTarget(5_000, -10_000, 60_000))
        assertEquals(15_000, AudioLogic.seekTarget(5_000, 10_000, 60_000))
        assertEquals(60_000, AudioLogic.seekTarget(55_000, 10_000, 60_000))
        assertEquals(0, AudioLogic.seekTarget(0, 10_000, 0))
    }

    @Test
    fun trackIndexWrapsAndClamps() {
        assertEquals(1, AudioLogic.nextIndex(0, 3))
        assertEquals(0, AudioLogic.nextIndex(2, 3))
        assertEquals(0, AudioLogic.nextIndex(0, 0))
        assertEquals(2, AudioLogic.clampIndex(5, 3))
        assertEquals(0, AudioLogic.clampIndex(-1, 3))
        assertEquals(0, AudioLogic.clampIndex(4, 0))
    }
}
