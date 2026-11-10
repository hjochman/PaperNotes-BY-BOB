package local.papernotes.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutPolicyTest {
    @Test
    fun firstStartShowsAbout() {
        assertTrue(AboutPolicy.shouldShowAbout(null, "0.2.0"))
    }

    @Test
    fun sameVersionDoesNotShowAbout() {
        assertFalse(AboutPolicy.shouldShowAbout("0.2.0", "0.2.0"))
    }

    @Test
    fun updateShowsAboutAgain() {
        assertTrue(AboutPolicy.shouldShowAbout("0.1.0", "0.2.0"))
    }

    @Test
    fun downgradeCountsAsChange() {
        assertTrue(AboutPolicy.shouldShowAbout("0.3.0", "0.2.0"))
    }

    @Test
    fun unknownCurrentVersionShowsNothing() {
        assertFalse(AboutPolicy.shouldShowAbout(null, ""))
        assertFalse(AboutPolicy.shouldShowAbout("0.1.0", ""))
    }
}
