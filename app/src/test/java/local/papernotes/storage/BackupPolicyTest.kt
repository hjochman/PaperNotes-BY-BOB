package local.papernotes.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {

    @Test
    fun constantsAndOptions() {
        assertEquals("backup_interval_minutes", BackupPolicy.PREF_KEY_INTERVAL_MINUTES)
        assertEquals(5, BackupPolicy.DEFAULT_INTERVAL_MINUTES)
        assertEquals(0, BackupPolicy.INTERVAL_OFF)
        assertEquals(listOf(0, 1, 2, 5, 10, 15, 30), BackupPolicy.INTERVAL_OPTIONS)
        assertTrue(BackupPolicy.isValidInterval(0))
        assertTrue(BackupPolicy.isValidInterval(1))
        assertTrue(BackupPolicy.isValidInterval(5))
        assertTrue(BackupPolicy.isValidInterval(30))
        assertFalse(BackupPolicy.isValidInterval(3))
        assertFalse(BackupPolicy.isValidInterval(-1))
    }

    @Test
    fun shouldBackupDisabledWhenIntervalIsOff() {
        val now = 100_000L
        val lastBackup = 0L
        // Even if modified, interval 0 means disabled
        assertFalse(BackupPolicy.shouldBackup(now, lastBackup, intervalMinutes = 0, modifiedSinceBackup = true))
        assertFalse(BackupPolicy.shouldBackup(now, lastBackup, intervalMinutes = -5, modifiedSinceBackup = true))
    }

    @Test
    fun shouldBackupDisabledWhenNotModified() {
        val interval = 5
        val now = 500_000L
        val lastBackup = 100_000L // 400s > 300s
        assertFalse(BackupPolicy.shouldBackup(now, lastBackup, intervalMinutes = interval, modifiedSinceBackup = false))
    }

    @Test
    fun shouldBackupBeforeIntervalElapsed() {
        val interval = 5 // 5 min = 300_000 ms
        val lastBackup = 1_000_000L
        val now = 1_000_000L + (4 * 60_000L) // only 4 min elapsed

        assertFalse(BackupPolicy.shouldBackup(now, lastBackup, intervalMinutes = interval, modifiedSinceBackup = true))
    }

    @Test
    fun shouldBackupWhenIntervalElapsedAndModified() {
        val interval = 5 // 5 min = 300_000 ms
        val lastBackup = 1_000_000L

        // Exactly 5 minutes
        val nowExact = lastBackup + (5 * 60_000L)
        assertTrue(BackupPolicy.shouldBackup(nowExact, lastBackup, intervalMinutes = interval, modifiedSinceBackup = true))

        // More than 5 minutes
        val nowLater = lastBackup + (6 * 60_000L)
        assertTrue(BackupPolicy.shouldBackup(nowLater, lastBackup, intervalMinutes = interval, modifiedSinceBackup = true))
    }

    @Test
    fun shouldBackupWhenNeverBackedUpBefore() {
        val interval = 1 // 1 min = 60_000 ms
        val lastBackup = 0L
        val now = 60_000L

        assertTrue(BackupPolicy.shouldBackup(now, lastBackup, intervalMinutes = interval, modifiedSinceBackup = true))
    }
}
