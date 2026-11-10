package local.papernotes.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateThrottleTest {
    @Test
    fun firstRequestRunsImmediately() {
        assertEquals(0L, UpdateThrottle(30_000).request(1_000))
    }

    @Test
    fun earlyRequestIsDeferredOnceUntilIntervalEnds() {
        val t = UpdateThrottle(30_000)
        assertEquals(0L, t.request(1_000))
        assertEquals(24_000L, t.request(7_000))
        assertEquals(-1L, t.request(8_000)) // schon vorgemerkt
        t.scheduledRunStarted(31_000)
        assertEquals(30_000L, t.request(31_000))
    }

    @Test
    fun requestAfterIntervalRunsImmediately() {
        val t = UpdateThrottle(30_000)
        t.request(1_000)
        assertEquals(0L, t.request(31_000))
    }

    @Test
    fun forceAlwaysRunsAndRestartsInterval() {
        val t = UpdateThrottle(30_000)
        t.request(1_000)
        assertEquals(0L, t.request(2_000, force = true))
        assertEquals(29_000L, t.request(3_000))
    }

    @Test
    fun worksWithNegativeClockStart() {
        assertEquals(0L, UpdateThrottle(30_000).request(0))
    }
}
