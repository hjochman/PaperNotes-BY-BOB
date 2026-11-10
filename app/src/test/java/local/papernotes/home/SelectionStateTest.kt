package local.papernotes.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    private val n1 = SelectionKey.Note("n1")
    private val n2 = SelectionKey.Note("n2")
    private val fA = SelectionKey.Folder("A")
    private val fAB = SelectionKey.Folder("A/B")
    private val fAx = SelectionKey.Folder("AB")

    private fun active() = SelectionState().also { it.start() }

    @Test
    fun startsInactiveAndEmpty() {
        val s = SelectionState()
        assertFalse(s.active)
        assertEquals(0, s.count)
        assertFalse(s.toggle(n1)) // ohne Modus wirkungslos
        assertEquals(0, s.count)
    }

    @Test
    fun toggleSetsAndClears() {
        val s = active()
        assertTrue(s.toggle(n1))
        assertTrue(s.isSelected(n1))
        assertTrue(s.toggle(fA)) // Notizen und Ordner gemeinsam
        assertEquals(2, s.count)
        assertFalse(s.toggle(n1))
        assertFalse(s.isSelected(n1))
        assertEquals(1, s.count)
    }

    @Test
    fun endClearsSelectionAndMode() {
        val s = active()
        s.toggle(n1)
        s.end()
        assertFalse(s.active)
        assertEquals(0, s.count)
        s.start()
        assertEquals(0, s.count)
    }

    @Test
    fun selectAllReplacesAndEmptyClears() {
        val s = active()
        s.toggle(n1)
        s.selectAll(listOf(n2, fA))
        assertFalse(s.isSelected(n1))
        assertEquals(2, s.count)
        s.selectAll(emptyList())
        assertEquals(0, s.count)
    }

    @Test
    fun retainOnlyDropsVanishedEntries() {
        val s = active()
        s.selectAll(listOf(n1, n2, fA))
        s.retainOnly(listOf(n2, fA, SelectionKey.Note("neu")))
        assertEquals(2, s.count)
        assertFalse(s.isSelected(n1))
    }

    @Test
    fun entryKeys() {
        val summary = local.papernotes.storage.NoteSummary("id", "t", "", 1, 1, false, false, 1)
        assertEquals(SelectionKey.Note("id"), Entry.Note(summary).selectionKey())
        assertEquals(SelectionKey.Folder("A/B"), Entry.Folder(FolderItem("A/B", "B", 0, 0, emptyList())).selectionKey())
        assertNull(Entry.NewNote.selectionKey())
    }

    @Test
    fun snapshotSeparatesNotesAndFoldersInOrder() {
        val s = active()
        s.toggle(fA)
        s.toggle(n2)
        s.toggle(n1)
        val snap = s.snapshot()
        assertEquals(listOf("n2", "n1"), snap.noteIds)
        assertEquals(listOf("A"), snap.folderPaths)
        s.toggle(n1) // Schnappschuss bleibt unverändert
        assertEquals(listOf("n2", "n1"), snap.noteIds)
    }

    @Test
    fun topLevelFoldersDropNestedAndKeepPrefixSiblings() {
        val s = active()
        s.selectAll(listOf(fAB, fA, fAx))
        assertEquals(listOf("A", "AB"), s.snapshot().topLevelFolders) // "AB" ist kein Unterordner von "A"
    }

    @Test
    fun moveTargetNeverSelfOrDescendant() {
        val s = active()
        s.selectAll(listOf(fA, n1))
        val snap = s.snapshot()
        assertFalse(snap.isValidMoveTarget("A"))
        assertFalse(snap.isValidMoveTarget("A/B"))
        assertFalse(snap.isValidMoveTarget("A/B/C"))
        assertTrue(snap.isValidMoveTarget(""))
        assertTrue(snap.isValidMoveTarget("AB"))
        assertTrue(snap.isValidMoveTarget("X/A"))
    }

    @Test
    fun noFolderSelectedAllowsEveryTarget() {
        val s = active()
        s.toggle(n1)
        assertTrue(s.snapshot().isValidMoveTarget("A/B"))
    }

    @Test
    fun encodeRestoreRoundTrip() {
        val s = active()
        s.selectAll(listOf(n1, fA, fAB))
        val r = SelectionState()
        r.restore(s.encode() + "kaputt")
        assertTrue(r.active)
        assertEquals(3, r.count)
        assertTrue(r.isSelected(n1) && r.isSelected(fA) && r.isSelected(fAB))
    }
}
