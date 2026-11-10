package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private fun id(n: Int) = "%08d-0000-4000-8000-000000000000".format(n)
    private fun files(n: Int) = (1..n).associate { id(it) to SyncedFile("", 1, 1) }
    private fun deletes(n: Int) = SyncPlan((1..n).map { SyncOp.DeleteFile(id(it), "") })
    private fun local(n: Int) = LocalSnapshot(emptySet(), (1..n).associate { id(it) to LocalFile(id(it), "", 1, 1) })

    @Test
    fun noFileDeletesNeverAsks() {
        assertFalse(SyncPolicy.needsConfirmation(SyncPlan(listOf(SyncOp.DeleteFolder("A"))), SyncState("s", emptySet(), files(5)), local(5)))
        assertFalse(SyncPolicy.needsConfirmation(SyncPlan(emptyList()), SyncState(), local(0)))
    }

    @Test
    fun fewDeletesOfManyPass() {
        assertFalse(SyncPolicy.needsConfirmation(deletes(2), SyncState("s", emptySet(), files(20)), local(18)))
        assertFalse(SyncPolicy.needsConfirmation(deletes(1), SyncState("s", emptySet(), files(1)).copy(files = files(2)), local(1)))
    }

    @Test
    fun emptyLocalWithKnownServerFilesAsks() {
        assertTrue(SyncPolicy.needsConfirmation(deletes(1), SyncState("s", emptySet(), files(1)), LocalSnapshot(emptySet(), emptyMap())))
    }

    @Test
    fun moreThanTenAsks() {
        assertTrue(SyncPolicy.needsConfirmation(deletes(11), SyncState("s", emptySet(), files(200)), local(189)))
        assertFalse(SyncPolicy.needsConfirmation(deletes(10), SyncState("s", emptySet(), files(200)), local(190)))
    }

    @Test
    fun moreThanHalfOfManyAsks() {
        assertTrue(SyncPolicy.needsConfirmation(deletes(4), SyncState("s", emptySet(), files(7)), local(3)))
        assertFalse(SyncPolicy.needsConfirmation(deletes(4), SyncState("s", emptySet(), files(8)), local(4)))
        assertFalse(SyncPolicy.needsConfirmation(deletes(3), SyncState("s", emptySet(), files(4)), local(1))) // bis 3 bleibt erlaubt
    }
}
