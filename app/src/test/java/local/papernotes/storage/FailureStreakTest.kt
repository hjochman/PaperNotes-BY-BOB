package local.papernotes.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureStreakTest {
    @Test
    fun noticeOnlyAfterThresholdFailuresInARow() {
        val s = FailureStreak(3)
        assertFalse(s.onFailure())
        assertFalse(s.onFailure())
        assertTrue(s.onFailure())
    }

    @Test
    fun noticeIsShownOnlyOnce() {
        val s = FailureStreak(2)
        assertFalse(s.onFailure())
        assertTrue(s.onFailure())
        repeat(10) { assertFalse(s.onFailure()) }
    }

    @Test
    fun successResetsStreakBeforeThreshold() {
        val s = FailureStreak(2)
        assertFalse(s.onFailure())
        s.onSuccess()
        assertFalse(s.onFailure())
        assertTrue(s.onFailure())
    }

    @Test
    fun successAfterNoticeAllowsNextNotice() {
        val s = FailureStreak(2)
        s.onFailure()
        assertTrue(s.onFailure())
        s.onSuccess()
        assertFalse(s.onFailure())
        assertTrue(s.onFailure())
        assertFalse(s.onFailure())
    }

    @Test
    fun thresholdOneNotifiesImmediately() {
        val s = FailureStreak(1)
        assertTrue(s.onFailure())
        assertFalse(s.onFailure())
    }

    @Test(expected = IllegalArgumentException::class)
    fun thresholdMustBePositive() {
        FailureStreak(0)
    }
}
