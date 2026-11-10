package local.papernotes.home

import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteError
import local.papernotes.storage.PnoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SelectionActionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: NoteRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("notes")
        repo = NoteRepository(root) { ++now }
    }

    private fun note(folder: String, title: String) = NoteFactory.newNote(title, folder).also {
        assertTrue(repo.save(it) is PnoteResult.Ok)
    }

    private fun snap(notes: List<String> = emptyList(), folders: List<String> = emptyList()) = SelectionSnapshot(notes, folders)

    @Test
    fun deleteMixedSelectionTrashesNotesAndFolderContents() {
        val a = note("", "a")
        val b = note("F", "b")
        val c = note("F/G", "c")
        val keep = note("", "keep")
        assertNull(SelectionActions.delete(repo, snap(listOf(a.id), listOf("F", "F/G"))))
        assertEquals(listOf("keep"), repo.list("").valueOrNull()!!.map { it.title })
        assertFalse(File(root, "F").exists())
        assertEquals(setOf(a.id, b.id, c.id), repo.listTrash().valueOrNull()!!.map { it.id }.toSet())
        assertTrue(repo.load(keep.id) is PnoteResult.Ok)
    }

    @Test
    fun deleteContinuesAfterErrorAndIgnoresVanishedNotes() {
        val a = note("", "a")
        File(root, "F").mkdirs()
        File(root, "F/fremd.txt").writeText("x")
        note("F", "n")
        val gone = NoteFactory.newNote("weg", "").id
        assertEquals(PnoteError.NotEmpty, SelectionActions.delete(repo, snap(listOf(gone, a.id), listOf("F"))))
        assertEquals(2, repo.trashCount()) // a und n liegen im Papierkorb
        assertTrue(File(root, "F/fremd.txt").isFile)
        assertNull(SelectionActions.delete(repo, snap(listOf(gone))))
    }

    @Test
    fun moveMixedSelectionToFolder() {
        val a = note("", "a")
        val b = note("Q", "b")
        val c = note("Q/Sub", "c")
        repo.createFolder("Ziel")
        assertNull(SelectionActions.move(repo, snap(listOf(a.id), listOf("Q")), "Ziel"))
        assertEquals("Ziel", repo.load(a.id).valueOrNull()!!.folderPath)
        assertEquals("Ziel/Q", repo.load(b.id).valueOrNull()!!.folderPath)
        assertEquals("Ziel/Q/Sub", repo.load(c.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun moveRejectsFolderIntoItselfWithoutChanges() {
        val n = note("A/B", "n")
        val a = note("", "a")
        assertEquals(PnoteError.InvalidFolder, SelectionActions.move(repo, snap(listOf(a.id), listOf("A")), "A/B"))
        assertEquals("", repo.load(a.id).valueOrNull()!!.folderPath) // auch die Notiz blieb unberührt
        assertEquals("A/B", repo.load(n.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun moveNestedSelectedFolderMovesOnlyTopLevel() {
        val n = note("A/B", "n")
        repo.createFolder("Ziel")
        assertNull(SelectionActions.move(repo, snap(folders = listOf("A", "A/B")), "Ziel"))
        assertEquals("Ziel/A/B", repo.load(n.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun notesToShareCombinesNotesAndFolderContentsWithoutDuplicates() {
        val a = note("", "a")
        val b = note("F", "b")
        val c = note("F/G", "c")
        note("", "unbeteiligt")
        val shared = SelectionActions.notesToShare(repo, snap(listOf(a.id, b.id, "kein-uuid"), listOf("F", "F/G")))
        assertEquals(setOf(a.id, b.id, c.id), shared.map { it.id }.toSet())
        assertEquals(3, shared.size)
    }
}
