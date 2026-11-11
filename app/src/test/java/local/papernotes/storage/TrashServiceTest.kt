package local.papernotes.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = tmp.root
    private val day = 86_400_000L

    private fun noteWithBackup(repo: NoteRepository, store: BackupStore, title: String): String {
        val note = TestData.simpleNote(title = title)
        assertTrue(repo.save(note) is PnoteResult.Ok)
        assertTrue(store.backup(note.id, repo.noteFile(note.id)!!, timestamp = 1000L) is PnoteResult.Ok)
        return note.id
    }

    private fun backupDir(id: String) = File(root, ".backup/$id")

    @Test
    fun policyExpiryUsesDaysAndNeverMeansZero() {
        assertFalse(TrashPolicy.isExpired(0L, 100 * day, TrashPolicy.RETENTION_NEVER))
        assertFalse(TrashPolicy.isExpired(0L, 29 * day, 30))
        assertTrue(TrashPolicy.isExpired(0L, 30 * day, 30))
        assertTrue(TrashPolicy.isExpired(0L, 31 * day, 30))
    }

    @Test
    fun policyExpiresAtAddsRetentionDays() {
        assertEquals(1000L + 30 * day, TrashPolicy.expiresAt(1000L, 30))
        assertEquals(null, TrashPolicy.expiresAt(1000L, TrashPolicy.RETENTION_NEVER))
    }

    @Test
    fun deleteForeverRemovesBackupsToo() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)
        val id = noteWithBackup(repo, store, "weg")
        assertTrue(repo.delete(id) is PnoteResult.Ok)
        assertTrue(backupDir(id).isDirectory)

        val entry = repo.listTrash().valueOrNull()!!.single().entryName
        assertTrue(TrashService(repo, store).deleteForever(entry) is PnoteResult.Ok)

        assertEquals(0, repo.trashCount())
        assertFalse(backupDir(id).exists())
    }

    @Test
    fun deleteForeverKeepsBackupsWhileNoteStillExists() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)
        val note = TestData.simpleNote(title = "doppelt")
        assertTrue(repo.save(note) is PnoteResult.Ok)
        assertTrue(store.backup(note.id, repo.noteFile(note.id)!!, timestamp = 1000L) is PnoteResult.Ok)
        assertTrue(repo.delete(note.id) is PnoteResult.Ok)
        // dieselbe Notiz ist wieder aktiv (z. B. durch Sync), der alte Eintrag liegt noch im Papierkorb
        assertTrue(repo.save(note) is PnoteResult.Ok)

        val entry = repo.listTrash().valueOrNull()!!.single().entryName
        assertTrue(TrashService(repo, store).deleteForever(entry) is PnoteResult.Ok)

        assertEquals(0, repo.trashCount())
        assertTrue(backupDir(note.id).isDirectory)
    }

    @Test
    fun emptyTrashRemovesBackupsOfAllTrashedNotesButNotOthers() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)
        val a = noteWithBackup(repo, store, "a")
        val b = noteWithBackup(repo, store, "b")
        val keep = noteWithBackup(repo, store, "keep")
        assertTrue(repo.delete(a) is PnoteResult.Ok)
        assertTrue(repo.delete(b) is PnoteResult.Ok)

        val res = TrashService(repo, store).emptyTrash()

        assertEquals(2, (res as PnoteResult.Ok).value)
        assertFalse(backupDir(a).exists())
        assertFalse(backupDir(b).exists())
        assertTrue(backupDir(keep).isDirectory)
    }

    @Test
    fun purgeExpiredDeletesOnlyOldEntriesWithTheirBackups() {
        var now = 1_000L
        val repo = NoteRepository(root) { now }
        val store = BackupStore(root) { now }
        val old = noteWithBackup(repo, store, "alt")
        assertTrue(repo.delete(old) is PnoteResult.Ok) // gelöscht bei now = 1000
        now += 20 * day
        val recent = noteWithBackup(repo, store, "neu")
        assertTrue(repo.delete(recent) is PnoteResult.Ok) // gelöscht bei 20 Tagen
        now += 15 * day // alt: 35 Tage, neu: 15 Tage

        val service = TrashService(repo, store) { now }
        assertEquals(0, service.purgeExpired(TrashPolicy.RETENTION_NEVER))
        assertEquals(1, service.purgeExpired(30))

        val left = repo.listTrash().valueOrNull()!!
        assertEquals(listOf(recent), left.map { it.id })
        assertFalse(backupDir(old).exists())
        assertTrue(backupDir(recent).isDirectory)
    }
}
