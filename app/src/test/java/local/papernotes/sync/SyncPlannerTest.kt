package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"

    private fun lf(id: String, folder: String = "", size: Long = 10, modified: Long = 100) = LocalFile(id, folder, size, modified)
    private fun sf(folder: String = "", size: Long = 10, modified: Long = 100) = SyncedFile(folder, size, modified)
    private fun snap(folders: Set<String> = emptySet(), vararg f: LocalFile) = LocalSnapshot(folders, f.associateBy { it.id })

    @Test
    fun nothingToDoWhenEqual() {
        val plan = SyncPlanner.plan(snap(setOf("X"), lf(a, "X")), SyncState("s", setOf("X"), mapOf(a to sf("X"))))
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.count)
    }

    @Test
    fun firstRunUploadsEverythingAndCreatesParentsFirst() {
        val plan = SyncPlanner.plan(snap(setOf("B/C", "A", "B"), lf(a, "B/C"), lf(b)), SyncState())
        assertEquals(
            listOf(
                SyncOp.MakeFolder("A"), SyncOp.MakeFolder("B"), SyncOp.MakeFolder("B/C"),
                SyncOp.Upload(lf(a, "B/C"), true), SyncOp.Upload(lf(b), true),
            ),
            plan.ops,
        )
        assertEquals(3, plan.newFolders)
        assertEquals(2, plan.uploads)
    }

    @Test
    fun changedNoteIsUploadedAgainBySizeOrTime() {
        val st = SyncState("s", emptySet(), mapOf(a to sf(size = 10, modified = 100), b to sf(size = 10, modified = 100)))
        val plan = SyncPlanner.plan(snap(emptySet(), lf(a, size = 11), lf(b, modified = 101)), st)
        assertEquals(listOf(SyncOp.Upload(lf(a, size = 11), false), SyncOp.Upload(lf(b, modified = 101), false)), plan.ops)
    }

    @Test
    fun movedNoteIsAMoveNotAnUpload() {
        val st = SyncState("s", setOf("X"), mapOf(a to sf("X")))
        val plan = SyncPlanner.plan(snap(setOf("X", "Y"), lf(a, "Y")), st)
        assertEquals(listOf(SyncOp.MakeFolder("Y"), SyncOp.Move(a, "X", "Y")), plan.ops)
        assertEquals(1, plan.moves)
        assertEquals(0, plan.uploads)
    }

    @Test
    fun movedAndChangedNoteMovesThenUploads() {
        val st = SyncState("s", setOf("X"), mapOf(a to sf("X")))
        val plan = SyncPlanner.plan(snap(setOf("X"), lf(a, "", size = 99)), st)
        assertEquals(listOf(SyncOp.Move(a, "X", ""), SyncOp.Upload(lf(a, "", size = 99), false)), plan.ops)
    }

    @Test
    fun folderRenameMakesNewMovesNotesThenDeletesOld() {
        val st = SyncState("s", setOf("Alt"), mapOf(a to sf("Alt"), b to sf("Alt")))
        val plan = SyncPlanner.plan(snap(setOf("Neu"), lf(a, "Neu"), lf(b, "Neu")), st)
        assertEquals(
            listOf(SyncOp.MakeFolder("Neu"), SyncOp.Move(a, "Alt", "Neu"), SyncOp.Move(b, "Alt", "Neu"), SyncOp.DeleteFolder("Alt")),
            plan.ops,
        )
    }

    @Test
    fun deletesFilesThenFoldersDeepestFirst() {
        val st = SyncState("s", setOf("A", "A/B"), mapOf(a to sf("A/B"), b to sf("A")))
        val plan = SyncPlanner.plan(snap(), st)
        assertEquals(
            listOf(SyncOp.DeleteFile(a, "A/B"), SyncOp.DeleteFile(b, "A"), SyncOp.DeleteFolder("A/B"), SyncOp.DeleteFolder("A")),
            plan.ops,
        )
        assertEquals(2, plan.fileDeletes)
        assertEquals(4, plan.deletes)
    }

    @Test
    fun emptyStateNeverDeletes() {
        val plan = SyncPlanner.plan(snap(), SyncState())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun trashedNoteCountsAsDelete() {
        // Eine Notiz im Papierkorb liegt in .trash und ist damit nicht mehr im Snapshot.
        val st = SyncState("s", emptySet(), mapOf(a to sf(), b to sf()))
        val plan = SyncPlanner.plan(snap(emptySet(), lf(b)), st)
        assertEquals(listOf(SyncOp.DeleteFile(a, "")), plan.ops)
    }

    @Test
    fun orderIsFoldersMovesUploadsDeletes() {
        val st = SyncState("s", setOf("Old"), mapOf(a to sf("Old"), b to sf(), c to sf()))
        val plan = SyncPlanner.plan(snap(setOf("New"), lf(a, "New"), lf(b, size = 1)), st)
        assertEquals(
            listOf(
                SyncOp.MakeFolder("New"), SyncOp.Move(a, "Old", "New"), SyncOp.Upload(lf(b, size = 1), false),
                SyncOp.DeleteFile(c, ""), SyncOp.DeleteFolder("Old"),
            ),
            plan.ops,
        )
    }
}
