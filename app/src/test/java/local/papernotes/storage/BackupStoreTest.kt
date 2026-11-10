package local.papernotes.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = tmp.root

    @Test
    fun createBackupAndList() {
        var now = 1_700_000_000_000L
        val repo = NoteRepository(root) { now }
        val store = BackupStore(root) { now }

        val note = TestData.bigNote(pages = 2, strokesPerPage = 2, points = 10)
        assertTrue(repo.save(note) is PnoteResult.Ok)

        val sourceFile = repo.noteFile(note.id)!!
        assertTrue(sourceFile.isFile)

        // Erstelle Sicherung 1
        val res1 = store.backup(note.id, sourceFile, timestamp = now)
        assertTrue(res1 is PnoteResult.Ok)
        val backupFile1 = (res1 as PnoteResult.Ok).value
        assertTrue(backupFile1.isFile)
        assertEquals("$now.pnote", backupFile1.name)
        assertEquals(sourceFile.length(), backupFile1.length())

        // Erstelle Sicherung 2
        now += 60_000L
        val res2 = store.backup(note.id, sourceFile, timestamp = now)
        assertTrue(res2 is PnoteResult.Ok)

        val list = store.list(note.id)
        assertEquals(2, list.size)
        assertEquals(now, list[0].timestamp)
        assertEquals(1_700_000_000_000L, list[1].timestamp)
        assertEquals(2, store.count())
        assertEquals(list.sumOf { it.sizeBytes }, store.totalSizeBytes())
    }

    @Test
    fun rotationLimitsToMax10Backups() {
        val store = BackupStore(root)
        val note = TestData.simpleNote()
        val repo = NoteRepository(root)
        assertTrue(repo.save(note) is PnoteResult.Ok)
        val sourceFile = repo.noteFile(note.id)!!

        val baseTime = 1_000_000L
        // Erzeuge 15 Sicherungen
        for (i in 1..15) {
            val ts = baseTime + (i * 10_000L)
            assertTrue(store.backup(note.id, sourceFile, timestamp = ts) is PnoteResult.Ok)
        }

        val backups = store.list(note.id)
        assertEquals(BackupStore.MAX_BACKUPS_PER_NOTE, backups.size)
        assertEquals(10, backups.size)

        // Die ältesten 5 (1..5) wurden gelöscht, die jüngsten 10 (6..15) sind vorhanden
        val oldestRemaining = baseTime + (6 * 10_000L)
        val newestRemaining = baseTime + (15 * 10_000L)
        assertEquals(newestRemaining, backups.first().timestamp)
        assertEquals(oldestRemaining, backups.last().timestamp)
    }

    @Test
    fun restoreAsNewCopy() {
        var now = 1_700_000_000_000L
        val repo = NoteRepository(root) { now }
        val store = BackupStore(root) { now }

        val original = TestData.bigNote(pages = 2, strokesPerPage = 3, points = 20, folder = "Projekte")
        assertTrue(repo.createFolder("Projekte") is PnoteResult.Ok)
        assertTrue(repo.save(original) is PnoteResult.Ok)

        val sourceFile = repo.noteFile(original.id)!!
        val backupTs = now
        assertTrue(store.backup(original.id, sourceFile, timestamp = backupTs) is PnoteResult.Ok)

        // Wiederherstellen mit neuem Titel
        now += 300_000L
        val restoreTitle = "${original.title} (Sicherung 12:00)"
        val restoredRes = store.restore(
            noteId = original.id,
            backupFileName = "$backupTs.pnote",
            newTitle = restoreTitle,
        )
        assertTrue(restoredRes is PnoteResult.Ok)
        val restoredId = (restoredRes as PnoteResult.Ok).value
        assertNotEquals(original.id, restoredId)

        // Prüfe, dass die Original-Notiz unberührt bleibt
        val loadedOriginal = repo.load(original.id).valueOrNull()!!
        assertEquals(original.title, loadedOriginal.title)
        assertEquals(original.id, loadedOriginal.id)

        // Prüfe die wiederhergestellte Notiz
        val restoredNote = repo.load(restoredId).valueOrNull()!!
        assertEquals(restoreTitle, restoredNote.title)
        assertEquals("Projekte", restoredNote.folderPath)
        assertEquals(original.pageIds.size, restoredNote.pageIds.size)
        // Frische Seiten-IDs
        assertFalse(restoredNote.pageIds.any { it in original.pageIds })
        // Striche und Vorlagen müssen übereinstimmen
        for (i in 0 until original.pageIds.size) {
            val origPage = original.pages.getValue(original.pageIds[i])
            val restPage = restoredNote.pages.getValue(restoredNote.pageIds[i])
            assertEquals(origPage.template, restPage.template)
            assertEquals(origPage.strokes.size, restPage.strokes.size)
        }
    }

    @Test
    fun pathSecurityAndInvalidIds() {
        val store = BackupStore(root)
        val dummyFile = File(root, "dummy.pnote").apply { writeBytes(ByteArray(10)) }

        val invalidIds = listOf(
            "../etc",
            "../../secret",
            "/root",
            "not-a-uuid",
            "12345",
            "../../../Documents",
            "some/path/traversal",
        )

        for (invalidId in invalidIds) {
            assertTrue(store.backup(invalidId, dummyFile) is PnoteResult.Err)
            assertTrue(store.list(invalidId).isEmpty())
            assertTrue(store.restore(invalidId, "123.pnote") is PnoteResult.Err)
        }

        // Ungültige Sicherungsdateinamen
        val validNoteId = TestData.uuid()
        assertTrue(store.restore(validNoteId, "../other.pnote") is PnoteResult.Err)
        assertTrue(store.restore(validNoteId, "invalid_name") is PnoteResult.Err)
        assertTrue(store.restore(validNoteId, "/absolute/path.pnote") is PnoteResult.Err)
    }

    @Test
    fun backupDirIgnoredByRepository() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)

        val note = TestData.simpleNote(title = "Geheimnotiz")
        assertTrue(repo.save(note) is PnoteResult.Ok)
        val file = repo.noteFile(note.id)!!
        assertTrue(store.backup(note.id, file, timestamp = 12345L) is PnoteResult.Ok)

        // .backup existiert auf der Platte
        assertTrue(File(root, ".backup").isDirectory)
        assertTrue(File(root, ".backup/${note.id}/12345.pnote").isFile)

        // NoteRepository ignoriert .backup vollständig:
        val rootList = repo.list("").valueOrNull()!!
        assertEquals(1, rootList.size)
        assertEquals(note.id, rootList[0].id)

        // listFolders sieht .backup nicht
        val folders = repo.listFolders("").valueOrNull()!!
        assertTrue(folders.none { it.name == ".backup" })

        // listAllFolders sieht .backup nicht
        val allFolders = repo.listAllFolders().valueOrNull()!!
        assertTrue(allFolders.none { it.contains(".backup") })

        // search findet die Sicherungsdateien nicht
        val searchHits = repo.search("Geheimnotiz").valueOrNull()!!
        assertEquals(1, searchHits.size) // nur die echte Notiz, nicht das Backup

        // deleteFolder auf .backup schlägt fehl
        assertTrue(repo.deleteFolder(".backup") is PnoteResult.Err)
        assertTrue(repo.deleteFolderWithContents(".backup") is PnoteResult.Err)
    }

    @Test
    fun trashOperationsDoNotTouchBackups() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)

        val note = TestData.simpleNote(title = "Zu Löschen")
        assertTrue(repo.save(note) is PnoteResult.Ok)
        val file = repo.noteFile(note.id)!!
        assertTrue(store.backup(note.id, file, timestamp = 99999L) is PnoteResult.Ok)

        // Notiz in Papierkorb verschieben
        assertTrue(repo.delete(note.id) is PnoteResult.Ok)
        assertEquals(1, repo.trashCount())

        // Backup existiert noch
        assertEquals(1, store.list(note.id).size)

        // Papierkorb leeren
        assertTrue(repo.emptyTrash() is PnoteResult.Ok)
        assertEquals(0, repo.trashCount())

        // Backup existiert IMMER NOCH (bewusst zur Notfall-Rettung)
        assertEquals(1, store.list(note.id).size)
        assertTrue(File(root, ".backup/${note.id}/99999.pnote").isFile)
    }

    @Test
    fun listGroupsAndSummary() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)

        val noteA = TestData.bigNote(pages = 2, strokesPerPage = 1, points = 5, folder = "OrdnerA")
        val noteB = TestData.bigNote(pages = 3, strokesPerPage = 2, points = 5, folder = "")
        assertTrue(repo.save(noteA) is PnoteResult.Ok)
        assertTrue(repo.save(noteB) is PnoteResult.Ok)

        val fileA = repo.noteFile(noteA.id)!!
        val fileB = repo.noteFile(noteB.id)!!

        assertTrue(store.backup(noteA.id, fileA, timestamp = 1000L) is PnoteResult.Ok)
        assertTrue(store.backup(noteA.id, fileA, timestamp = 2000L) is PnoteResult.Ok)
        assertTrue(store.backup(noteB.id, fileB, timestamp = 3000L) is PnoteResult.Ok)

        val groups = store.listGroups()
        assertEquals(2, groups.size)
        // noteB ist jüngste Sicherung (3000)
        assertEquals(noteB.id, groups[0].noteId)
        assertEquals(3000L, groups[0].latestTimestamp)
        assertEquals(1, groups[0].backupCount)
        assertEquals(3, groups[0].latestPageCount)

        // noteA ist älter (2000)
        assertEquals(noteA.id, groups[1].noteId)
        assertEquals(2000L, groups[1].latestTimestamp)
        assertEquals(2, groups[1].backupCount)
        assertEquals(2, groups[1].latestPageCount)
    }

    @Test
    fun deleteAllBackups() {
        val repo = NoteRepository(root)
        val store = BackupStore(root)

        val note1 = TestData.simpleNote()
        val note2 = TestData.simpleNote()
        assertTrue(repo.save(note1) is PnoteResult.Ok)
        assertTrue(repo.save(note2) is PnoteResult.Ok)

        store.backup(note1.id, repo.noteFile(note1.id)!!, timestamp = 10L)
        store.backup(note1.id, repo.noteFile(note1.id)!!, timestamp = 20L)
        store.backup(note2.id, repo.noteFile(note2.id)!!, timestamp = 30L)

        assertEquals(3, store.count())

        val deleteRes = store.deleteAll()
        assertTrue(deleteRes is PnoteResult.Ok)
        assertEquals(3, (deleteRes as PnoteResult.Ok).value)

        assertEquals(0, store.count())
        assertEquals(0L, store.totalSizeBytes())
        assertTrue(store.listGroups().isEmpty())
    }
}
