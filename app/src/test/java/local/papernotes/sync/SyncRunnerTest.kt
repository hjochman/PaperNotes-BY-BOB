package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Server im Speicher: bildet Ordner, Dateien, 409 bei fehlendem Elternordner und einen erzwingbaren Fehler nach. */
private class FakeRemote : SyncRemote {
    val folders = LinkedHashSet<String>()
    val files = HashMap<String, RemoteFile>()
    val foreign = HashMap<String, Int>() // Ordner -> Zahl fremder Dateien
    val calls = ArrayList<String>()
    var failAfter = Int.MAX_VALUE
    var failKind = RemoteException.Kind.NETWORK
    var onUpload: (File) -> Unit = {}

    private fun call(text: String) {
        if (calls.size >= failAfter) throw RemoteException(failKind)
        calls.add(text)
    }

    private fun parentOk(folder: String) = folder.isEmpty() || folder.substringBeforeLast('/', "").let { it.isEmpty() || it in folders }

    override fun listTree(): RemoteTree {
        calls.add("list")
        return RemoteTree(folders.toSet(), files.toMap())
    }

    override fun makeFolder(path: String) {
        call("mkdir $path")
        if (!parentOk(path)) throw RemoteException(RemoteException.Kind.CONFLICT, 409)
        folders.add(path)
    }

    override fun move(id: String, fromFolder: String, toFolder: String) {
        call("move $id $fromFolder->$toFolder")
        val f = files[id] ?: throw RemoteException(RemoteException.Kind.NOT_FOUND, 404)
        if (toFolder.isNotEmpty() && toFolder !in folders) throw RemoteException(RemoteException.Kind.CONFLICT, 409)
        files[id] = f.copy(folder = toFolder)
    }

    override fun upload(file: LocalFile, source: File) {
        call("put ${file.id} ${file.folder}")
        if (file.folder.isNotEmpty() && file.folder !in folders) throw RemoteException(RemoteException.Kind.CONFLICT, 409)
        onUpload(source)
        files[file.id] = RemoteFile(file.folder, file.size, file.modified)
    }

    override fun delete(id: String, folder: String) {
        call("delete $id")
        files.remove(id)
    }

    override fun hasChildren(path: String): Boolean {
        calls.add("children $path")
        return files.values.any { it.folder == path || it.folder.startsWith("$path/") } ||
            folders.any { it.startsWith("$path/") } || (foreign[path] ?: 0) > 0
    }

    override fun deleteFolder(path: String) {
        call("rmdir $path")
        folders.remove(path)
    }
}

private class MemStore(var state: SyncState? = null) : SyncStateStore {
    var saves = 0
    override fun load() = state
    override fun save(state: SyncState) {
        this.state = state
        saves++
    }
}

class SyncRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var remote: FakeRemote
    private lateinit var store: MemStore
    private val scope = "https://c|u|PaperNotes"

    private fun id(n: Int) = "%08d-0000-4000-8000-000000000000".format(n)

    @Before
    fun setUp() {
        root = tmp.newFolder("notes")
        remote = FakeRemote()
        store = MemStore()
    }

    private fun note(n: Int, folder: String = "", text: String = "inhalt", time: Long = 5_000): File {
        val dir = if (folder.isEmpty()) root else File(root, folder).also { it.mkdirs() }
        return File(dir, "${id(n)}.pnote").also { it.writeText(text); it.setLastModified(time) }
    }

    private fun run(confirmed: Boolean = false, force: Boolean = false, stop: () -> Boolean = { false }) =
        SyncRunner(root, remote, store, scope, stop).run(confirmed, force)

    @Test
    fun firstRunUploadsEverythingIncludingFolders() {
        note(1)
        note(2, "A/B")
        val r = run()
        assertEquals(SyncResult.Done(4, false), r) // A, A/B, 2 Notizen
        assertEquals(setOf("A", "A/B"), remote.folders)
        assertEquals(setOf(id(1), id(2)), remote.files.keys)
        assertEquals("A/B", remote.files[id(2)]!!.folder)
        assertEquals(SyncResult.NothingToDo, run())
    }

    @Test
    fun secondRunWithoutChangesDoesNotTouchTheServer() {
        note(1)
        run()
        remote.calls.clear()
        assertEquals(SyncResult.NothingToDo, run())
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun changeUploadsOnlyThatNote() {
        note(1)
        note(2)
        run()
        remote.calls.clear()
        note(2, text = "neu und länger", time = 9_000)
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals(listOf("put ${id(2)} "), remote.calls)
    }

    @Test
    fun moveAndFolderRenameDoNotReuploadAnything() {
        note(1, "Alt")
        note(2, "Alt")
        run()
        remote.calls.clear()
        File(root, "Alt").renameTo(File(root, "Neu"))
        assertEquals(SyncResult.Done(4, false), run())
        assertEquals(
            listOf("mkdir Neu", "move ${id(1)} Alt->Neu", "move ${id(2)} Alt->Neu", "children Alt", "rmdir Alt"),
            remote.calls,
        )
        assertEquals(setOf("Neu"), remote.folders)
        assertEquals("Neu", remote.files[id(1)]!!.folder)
    }

    @Test
    fun trashedNoteIsDeletedOnServer() {
        val f = note(1)
        note(2)
        run()
        File(root, ".trash").mkdirs()
        f.renameTo(File(root, ".trash/${id(1)}.pnote"))
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals(setOf(id(2)), remote.files.keys)
    }

    @Test
    fun deletedFolderIsRemovedButNotIfForeignContentRemains() {
        note(1, "X")
        note(2, "Y")
        note(3) // bleibt lokal: ein komplett leerer Bestand würde die Schutzgrenze auslösen
        run()
        remote.foreign["Y"] = 1 // z. B. eine .DS_Store, die ein anderes Programm hineingelegt hat
        File(root, "X/${id(1)}.pnote").delete(); File(root, "X").delete()
        File(root, "Y/${id(2)}.pnote").delete(); File(root, "Y").delete()
        run()
        assertEquals(setOf("Y"), remote.folders) // Y bleibt wegen Fremdinhalt
        assertEquals(setOf(id(3)), remote.files.keys)
        assertFalse(store.state!!.folders.contains("Y")) // trotzdem nicht mehr im Gedächtnis: keine Endlosschleife
    }

    @Test
    fun neverDeletesWhatItDidNotUpload() {
        remote.files[id(9)] = RemoteFile("", 1, 1) // fremde Notiz auf dem Server
        remote.folders.add("Fremd")
        note(1)
        run()
        assertTrue(remote.files.containsKey(id(9)))
        assertTrue(remote.folders.contains("Fremd"))
    }

    @Test
    fun firstRunReconcilesInsteadOfReuploadingEqualFiles() {
        val f = note(1, time = 7_000)
        note(2, time = 8_000)
        remote.files[id(1)] = RemoteFile("", f.length(), 7_000)
        remote.files[id(2)] = RemoteFile("", 999, 8_000) // andere Größe: muss neu hoch
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals(listOf("list", "put ${id(2)} "), remote.calls)
    }

    @Test
    fun firstRunMovesNoteFoundInOtherFolderOnServer() {
        val f = note(1, "Neu", time = 7_000)
        remote.folders.add("Neu"); remote.folders.add("Alt")
        remote.files[id(1)] = RemoteFile("Alt", f.length(), 7_000)
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals("Neu", remote.files[id(1)]!!.folder)
    }

    @Test
    fun scopeChangeStartsOverWithReconcile() {
        note(1)
        run()
        remote.calls.clear()
        val other = SyncRunner(root, remote, store, "https://anderer|u|PaperNotes").run()
        assertEquals(SyncResult.NothingToDo, other) // Server hat alles, Abgleich findet es
        assertEquals("list", remote.calls.first())
    }

    @Test
    fun stateIsSavedAfterEveryOperationSoAbortLosesNothing() {
        (1..4).forEach { note(it) }
        remote.failAfter = 3 // der Abgleich ("list") zählt als Aufruf: danach 2 Uploads, dann Fehler
        val r = run()
        assertTrue(r is SyncResult.Retry)
        assertEquals(2, remote.files.size)
        assertEquals(2, store.state!!.files.size)
        remote.failAfter = Int.MAX_VALUE
        remote.calls.clear()
        assertEquals(SyncResult.Done(2, false), run())
        assertEquals(4, remote.files.size)
        assertEquals(2, remote.calls.size) // nur noch die beiden fehlenden
    }

    @Test
    fun errorKindsMapToResults() {
        note(1)
        remote.failAfter = 0
        remote.failKind = RemoteException.Kind.AUTH
        assertEquals(SyncResult.AuthFailed, run())
        remote.failKind = RemoteException.Kind.QUOTA
        assertEquals(SyncResult.Failed(RemoteException.Kind.QUOTA), run())
        remote.failKind = RemoteException.Kind.SERVER
        assertTrue(run() is SyncResult.Retry)
    }

    @Test
    fun missingStorageDeletesNothing() {
        note(1)
        run()
        val gone = File(tmp.root, "weg")
        val r = SyncRunner(gone, remote, store, scope).run()
        assertEquals(SyncResult.NoStorage, r)
        assertEquals(setOf(id(1)), remote.files.keys)
    }

    @Test
    fun massDeleteNeedsConfirmationThenRuns() {
        (1..12).forEach { note(it) }
        run()
        (1..12).forEach { File(root, "${id(it)}.pnote").delete() }
        val r = run()
        assertTrue(r is SyncResult.NeedsConfirmation)
        assertEquals(12, remote.files.size)
        assertEquals(SyncResult.Done(12, false), run(confirmed = true))
        assertTrue(remote.files.isEmpty())
    }

    @Test
    fun noteChangedDuringUploadStaysPending() {
        val f = note(1)
        remote.onUpload = { f.writeText("währenddessen geändert, anderer Inhalt"); f.setLastModified(9_999) }
        assertEquals(SyncResult.Done(0, true), run())
        remote.onUpload = {}
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals(f.length(), remote.files[id(1)]!!.size)
    }

    @Test
    fun missingParentFolderIsCreatedAndRetriedOnce() {
        note(1, "A/B")
        // Gedächtnis kennt die Ordner, der Server nicht (z. B. dort von Hand gelöscht)
        val f = File(root, "A/B/${id(1)}.pnote")
        store.state = SyncState(scope, setOf("A", "A/B"), emptyMap())
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals(setOf("A", "A/B"), remote.folders)
        assertTrue(remote.files.containsKey(id(1)))
        assertTrue(f.exists())
    }

    @Test
    fun movedNoteMissingOnServerIsUploadedInstead() {
        note(1, "X")
        run()
        remote.files.clear() // Server-Datei wurde dort gelöscht
        File(root, "X/${id(1)}.pnote").renameTo(File(root, "${id(1)}.pnote"))
        assertEquals(SyncResult.Done(1, false), run())
        assertEquals("", remote.files[id(1)]!!.folder)
    }

    @Test
    fun stopRequestEndsWithRetryAndKeepsState() {
        (1..3).forEach { note(it) }
        var n = 0
        val r = run(stop = { n++ >= 1 })
        assertTrue(r is SyncResult.Retry)
        assertEquals(1, store.state!!.files.size)
    }
}
