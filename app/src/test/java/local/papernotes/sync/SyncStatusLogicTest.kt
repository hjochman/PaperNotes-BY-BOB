package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusLogicTest {
    private val base = SyncFacts(
        configured = true, enabled = true, running = false, pending = 0, error = null, needsConfirmation = false, online = true,
    )

    private fun ui(f: SyncFacts) = SyncStatusLogic.compute(f).ui

    @Test
    fun offWhenNotConfiguredOrDisabled() {
        assertEquals(SyncUi.OFF, ui(base.copy(configured = false, pending = 5)))
        assertEquals(SyncUi.OFF, ui(base.copy(enabled = false, running = true, error = "AUTH")))
    }

    @Test
    fun priorityRunningThenAuthThenLastingErrorThenConfirm() {
        assertEquals(SyncUi.RUNNING, ui(base.copy(running = true, error = "AUTH", pending = 3)))
        assertEquals(SyncUi.AUTH, ui(base.copy(error = "AUTH", needsConfirmation = true, pending = 3)))
        assertEquals(SyncUi.ERROR, ui(base.copy(error = "QUOTA", needsConfirmation = true, pending = 3)))
        assertEquals(SyncUi.ERROR, ui(base.copy(error = SyncStatusLogic.ERROR_STORAGE)))
        assertEquals(SyncUi.CONFIRM, ui(base.copy(needsConfirmation = true, pending = 12)))
    }

    @Test
    fun temporaryErrorsStayPendingOrOffline() {
        assertEquals(SyncUi.PENDING, ui(base.copy(error = "NETWORK", pending = 2)))
        assertEquals(SyncUi.PENDING, ui(base.copy(error = "SERVER", pending = 2)))
        assertEquals(SyncUi.OFFLINE, ui(base.copy(error = "NETWORK", pending = 2, online = false)))
    }

    @Test
    fun syncedPendingOffline() {
        assertEquals(SyncUi.SYNCED, ui(base))
        assertEquals(SyncUi.SYNCED, ui(base.copy(online = false))) // nichts zu tun: Netz ist egal
        assertEquals(SyncUi.PENDING, ui(base.copy(pending = 4)))
        assertEquals(SyncUi.OFFLINE, ui(base.copy(pending = 4, online = false)))
    }

    @Test
    fun badgeTexts() {
        fun badge(f: SyncFacts) = SyncStatusLogic.badge(SyncStatusLogic.compute(f))
        assertEquals("–", badge(base.copy(configured = false)))
        assertEquals("✓", badge(base))
        assertEquals("7", badge(base.copy(pending = 7)))
        assertEquals("7", badge(base.copy(pending = 7, online = false)))
        assertEquals("99", badge(base.copy(pending = 99)))
        assertEquals("99+", badge(base.copy(pending = 100)))
        assertEquals("…", badge(base.copy(running = true, pending = 3)))
        assertEquals("!", badge(base.copy(error = "AUTH")))
        assertEquals("?", badge(base.copy(needsConfirmation = true, pending = 20)))
        assertEquals("×", badge(base.copy(error = "PROTOCOL", pending = 3)))
        assertEquals("×", badge(base.copy(error = SyncStatusLogic.ERROR_STORAGE)))
    }

    @Test
    fun negativePendingIsZero() {
        assertEquals(0, SyncStatusLogic.compute(base.copy(pending = -3)).pending)
    }

    @Test
    fun dimmingAndSettingsShortcut() {
        assertTrue(SyncStatusLogic.dimmed(SyncStatus(SyncUi.OFF, 0)))
        assertTrue(SyncStatusLogic.dimmed(SyncStatus(SyncUi.OFFLINE, 2)))
        assertFalse(SyncStatusLogic.dimmed(SyncStatus(SyncUi.PENDING, 2)))
        assertTrue(SyncStatusLogic.opensSettings(SyncStatus(SyncUi.OFF, 0)))
        assertTrue(SyncStatusLogic.opensSettings(SyncStatus(SyncUi.AUTH, 0)))
        assertFalse(SyncStatusLogic.opensSettings(SyncStatus(SyncUi.SYNCED, 0)))
    }
}

class SyncAutoBlockTest {
    @Test
    fun onlyUserFixableErrorsBlockTheAutomaticRun() {
        listOf("AUTH", "QUOTA", "PROTOCOL", SyncStatusLogic.ERROR_STORAGE).forEach { assertTrue(it, SyncStatusLogic.blocksAutoSync(it)) }
        listOf(null, "NETWORK", "SERVER", "CONFLICT", "NOT_FOUND").forEach { assertFalse("$it", SyncStatusLogic.blocksAutoSync(it)) }
    }
}
