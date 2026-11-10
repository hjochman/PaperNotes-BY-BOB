package local.papernotes.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteRepositoryTest {
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

    private fun ok(r: PnoteResult<*>) = assertTrue("erwartet Ok, war $r", r is PnoteResult.Ok)

    @Test
    fun createSaveListLoad() {
        val a = TestData.simpleNote("", "Alpha").copy(modified = 1_000L)
        val b = TestData.simpleNote("", "Beta").copy(modified = 5_000L)
        ok(repo.save(a))
        ok(repo.save(b))
        val list = repo.list("").valueOrNull()!!
        assertEquals(2, list.size)
        assertEquals("Beta", list[0].title) // neueste zuerst
        assertEquals(1, list[0].pageCount)
        assertEquals(a, repo.load(a.id).valueOrNull())
        assertTrue(File(root, "${a.id}.pnote").isFile)
    }

    @Test
    fun foldersAndCounts() {
        ok(repo.createFolder("BIT"))
        ok(repo.createFolder("BIT/Sub"))
        ok(repo.save(TestData.simpleNote("BIT", "n1")))
        ok(repo.save(TestData.simpleNote("BIT", "n2")))
        val top = repo.listFolders("").valueOrNull()!!
        assertEquals(listOf(FolderSummary("BIT", "BIT", 2)), top)
        val sub = repo.listFolders("BIT").valueOrNull()!!
        assertEquals(listOf(FolderSummary("Sub", "BIT/Sub", 0)), sub)
        assertEquals(2, repo.list("BIT").valueOrNull()!!.size)
        assertEquals(0, repo.list("").valueOrNull()!!.size)
        assertEquals(PnoteError.AlreadyExists, repo.createFolder("BIT").errorOrNull())
    }

    @Test
    fun moveChangesManifestAndDirectory() {
        ok(repo.createFolder("Ziel"))
        val n = TestData.simpleNote("", "m")
        ok(repo.save(n))
        ok(repo.move(n.id, "Ziel"))
        assertFalse(File(root, "${n.id}.pnote").exists())
        assertTrue(File(root, "Ziel/${n.id}.pnote").isFile)
        assertEquals("Ziel", repo.load(n.id).valueOrNull()!!.folderPath)
        assertEquals(1, repo.list("Ziel").valueOrNull()!!.size)
        ok(repo.move(n.id, ""))
        assertTrue(File(root, "${n.id}.pnote").isFile)
        assertFalse(File(root, "Ziel/${n.id}.pnote").exists())
    }

    @Test
    fun moveToNewNestedFolderCreatesIt() {
        val n = TestData.simpleNote("", "m")
        ok(repo.save(n))
        ok(repo.move(n.id, "A/B"))
        assertTrue(File(root, "A/B/${n.id}.pnote").isFile)
    }

    @Test
    fun renameKeepsFileName() {
        val n = TestData.simpleNote("", "alt").copy(modified = 10L)
        ok(repo.save(n))
        ok(repo.rename(n.id, "Neuer Titel äöü"))
        val back = repo.load(n.id).valueOrNull()!!
        assertEquals("Neuer Titel äöü", back.title)
        assertTrue(back.modified > n.modified - 1)
        assertEquals(listOf("${n.id}.pnote"), root.list()!!.toList())
    }

    @Test
    fun deleteMovesToTrash() {
        val n = TestData.simpleNote("", "weg")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        assertEquals(PnoteError.NotFound, repo.load(n.id).errorOrNull())
        assertTrue(File(root, ".trash/${n.id}.pnote").isFile)
        assertEquals(0, repo.list("").valueOrNull()!!.size)
        assertEquals(PnoteError.NotFound, repo.delete(n.id).errorOrNull())
    }

    @Test
    fun deleteTwiceSameIdKeepsBothInTrash() {
        val n = TestData.simpleNote("", "weg")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        ok(repo.save(n))
        ok(repo.delete(n.id))
        assertEquals(2, File(root, ".trash").list()!!.size)
    }

    @Test
    fun renameFolderUpdatesManifestsOfNotesInside() {
        ok(repo.createFolder("Alt"))
        val n = TestData.simpleNote("Alt", "x")
        ok(repo.save(n))
        val n2 = TestData.simpleNote("Alt/Tief", "y")
        ok(repo.save(n2))
        ok(repo.renameFolder("Alt", "Neu"))
        assertFalse(File(root, "Alt").exists())
        assertEquals("Neu", repo.load(n.id).valueOrNull()!!.folderPath)
        assertEquals("Neu/Tief", repo.load(n2.id).valueOrNull()!!.folderPath)
        assertEquals(PnoteError.NotFound, repo.renameFolder("Gibt es nicht", "X").errorOrNull())
        ok(repo.createFolder("Anderer"))
        assertEquals(PnoteError.AlreadyExists, repo.renameFolder("Neu", "Anderer").errorOrNull())
    }

    @Test
    fun rejectsEvilFolderNames() {
        val evil = listOf(
            "..", "../x", "a/../b", "/abs", "a//b", ".hidden", ".trash", "a\\b", "x/", " lead", "trail ", "a:b", "a*b",
            "a".repeat(81), "a\u0000b", "~", "a/.", "%2e%2e",
        )
        for (name in evil) {
            assertEquals("createFolder $name", PnoteError.InvalidFolder, repo.createFolder(name).errorOrNull())
            assertEquals("list $name", PnoteError.InvalidFolder, repo.list(name).errorOrNull())
            assertEquals("save $name", PnoteError.InvalidFolder, repo.save(TestData.simpleNote(name)).errorOrNull())
        }
        val n = TestData.simpleNote("", "ok")
        ok(repo.save(n))
        assertEquals(PnoteError.InvalidFolder, repo.move(n.id, "../out").errorOrNull())
        ok(repo.createFolder("Gut"))
        assertEquals(PnoteError.InvalidFolder, repo.renameFolder("Gut", "../out").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.renameFolder("Gut", "a/b").errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.load("../../etc/passwd").errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.delete("..").errorOrNull())
        // Nichts außerhalb von root angelegt
        assertEquals(listOf("notes"), tmp.root.list()!!.toList())
    }

    @Test
    fun acceptsUmlautsAndSpacesInFolderNames() {
        ok(repo.createFolder("Übung März-2026_v1.0"))
        assertTrue(File(root, "Übung März-2026_v1.0").isDirectory)
        assertTrue(FolderPath.isValid("A/B"))
        assertTrue(FolderPath.isValid(""))
        assertTrue(FolderPath.isValidName("a".repeat(80)))
    }

    @Test
    fun symlinkOutOfRootIsNotFollowed() {
        val outside = tmp.newFolder("outside")
        TestData.simpleNote().let { PnoteCodec.write(it, File(outside, "${it.id}.pnote")) }
        val link = File(root, "Link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // Plattform ohne Symlinks
        }
        assertEquals(PnoteError.InvalidFolder, repo.list("Link").errorOrNull())
        assertEquals(0, repo.listFolders("").valueOrNull()!!.size)
        assertEquals(PnoteError.InvalidFolder, repo.save(TestData.simpleNote("Link")).errorOrNull())
    }

    @Test
    fun foreignFilesAreIgnoredAndNeverOverwritten() {
        val foreign = File(root, "fremd.pnote").apply { writeText("meins") }
        val other = File(root, "readme.txt").apply { writeText("hallo") }
        val n = TestData.simpleNote("", "n")
        ok(repo.save(n))
        assertEquals(1, repo.list("").valueOrNull()!!.size)
        assertEquals("meins", foreign.readText())
        assertEquals("hallo", other.readText())
    }

    @Test
    fun corruptNoteIsSkippedInList() {
        val id = TestData.uuid()
        File(root, "$id.pnote").writeBytes(ByteArray(50) { 3 })
        ok(repo.save(TestData.simpleNote("", "gut")))
        assertEquals(1, repo.list("").valueOrNull()!!.size)
        assertTrue(repo.load(id).errorOrNull() != null)
    }

    @Test
    fun saveOverwritesSameNoteInPlace() {
        val n = TestData.simpleNote("", "v1")
        ok(repo.save(n))
        ok(repo.save(n.copy(title = "v2")))
        assertEquals("v2", repo.load(n.id).valueOrNull()!!.title)
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun favoriteAndPinnedKeepModified() {
        val n = TestData.simpleNote("", "fav").copy(modified = 4_242L)
        ok(repo.save(n))
        ok(repo.setFavorite(n.id, true))
        ok(repo.setPinned(n.id, true))
        val back = repo.load(n.id).valueOrNull()!!
        assertTrue(back.favorite)
        assertTrue(back.pinned)
        assertEquals(4_242L, back.modified)
        ok(repo.setFavorite(n.id, false))
        ok(repo.setPinned(n.id, false))
        val off = repo.load(n.id).valueOrNull()!!
        assertFalse(off.favorite)
        assertFalse(off.pinned)
        assertEquals(4_242L, off.modified)
        assertEquals(PnoteError.NotFound, repo.setFavorite(TestData.uuid(), true).errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.setPinned("../x", true).errorOrNull())
    }

    @Test
    fun embeddedPreviewSurvivesRenameFavoriteMoveAndIsLoadable() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7)
        val n = TestData.simpleNote("", "mit Vorschau").copy(preview = jpeg)
        ok(repo.save(n))
        assertArrayEquals(jpeg, repo.loadPreview(n.id))
        ok(repo.rename(n.id, "neu"))
        ok(repo.setFavorite(n.id, true))
        ok(repo.move(n.id, "Ordner"))
        assertArrayEquals(jpeg, repo.loadPreview(n.id))
        assertArrayEquals(jpeg, repo.load(n.id).valueOrNull()!!.preview)
        assertEquals(null, repo.loadPreview(TestData.uuid()))
        assertEquals(null, repo.loadPreview("../x"))
    }

    @Test
    fun favoriteInFolderStaysInFolder() {
        val n = TestData.simpleNote("Ordner", "x")
        ok(repo.save(n))
        ok(repo.setFavorite(n.id, true))
        assertTrue(File(root, "Ordner/${n.id}.pnote").isFile)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun copyCreatesNewIdsAndTimes() {
        val src = TestData.bigNote(pages = 2, strokesPerPage = 2, points = 5, folder = "Ordner")
            .copy(pinned = true)
        ok(repo.save(src))
        val newId = repo.copy(src.id, "Kopie-Titel").valueOrNull()!!
        assertTrue(newId != src.id)
        val copy = repo.load(newId).valueOrNull()!!
        assertEquals("Kopie-Titel", copy.title)
        assertEquals("Ordner", copy.folderPath)
        assertEquals(copy.created, copy.modified)
        assertTrue(copy.created > 1_000L) // von der Testuhr, nicht vom Original
        assertEquals(src.pageIds.size, copy.pageIds.size)
        assertTrue(copy.pageIds.none { it in src.pageIds })
        copy.pageIds.forEach { assertEquals(it, copy.pages.getValue(it).id) }
        assertEquals(
            src.pageIds.map { src.pages.getValue(it).strokes },
            copy.pageIds.map { copy.pages.getValue(it).strokes },
        )
        // Original unverändert
        assertEquals(src, repo.load(src.id).valueOrNull())
        assertEquals(2, repo.list("Ordner").valueOrNull()!!.size)
    }

    @Test
    fun copyAndRenameRejectInvalidTitles() {
        val n = TestData.simpleNote("", "x")
        ok(repo.save(n))
        assertEquals(PnoteError.InvalidNote, repo.copy(n.id, "  ").errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "").errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "a".repeat(NoteTitles.MAX_LENGTH + 1)).errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "a\nb").errorOrNull())
        assertEquals("x", repo.load(n.id).valueOrNull()!!.title)
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun deleteFolderOnlyWhenEmpty() {
        ok(repo.createFolder("Leer"))
        ok(repo.createFolder("Voll"))
        ok(repo.createFolder("Eltern/Kind"))
        ok(repo.save(TestData.simpleNote("Voll", "n")))
        ok(repo.deleteFolder("Leer"))
        assertFalse(File(root, "Leer").exists())
        assertEquals(PnoteError.NotEmpty, repo.deleteFolder("Voll").errorOrNull())
        assertEquals(PnoteError.NotEmpty, repo.deleteFolder("Eltern").errorOrNull())
        assertTrue(File(root, "Voll").isDirectory)
        assertEquals(PnoteError.NotFound, repo.deleteFolder("Gibt es nicht").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("..").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("a/../..").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder(".trash").errorOrNull())
        assertTrue(root.isDirectory)
    }

    @Test
    fun searchIsCaseInsensitiveAcrossFoldersWithoutTrash() {
        ok(repo.save(TestData.simpleNote("", "Pipeline Alpha")))
        ok(repo.save(TestData.simpleNote("BIT", "alle PIPELINE")))
        ok(repo.save(TestData.simpleNote("BIT/Tief", "Nichts")))
        val gone = TestData.simpleNote("", "pipeline weg")
        ok(repo.save(gone))
        ok(repo.delete(gone.id))
        val hits = repo.search("pipeLINE").valueOrNull()!!
        assertEquals(setOf("Pipeline Alpha", "alle PIPELINE"), hits.map { it.title }.toSet())
        assertEquals(0, repo.search("tief").valueOrNull()!!.size) // nur Titel, nicht Ordnernamen
        assertEquals(1, repo.search("nichts").valueOrNull()!!.size)
    }

    @Test
    fun searchRejectsEmptyAndTooLongQueries() {
        ok(repo.save(TestData.simpleNote("", "abc")))
        assertEquals(0, repo.search("").valueOrNull()!!.size)
        assertEquals(0, repo.search("   ").valueOrNull()!!.size)
        assertEquals(0, repo.search("a".repeat(NoteRepository.MAX_QUERY_LENGTH + 1)).valueOrNull()!!.size)
        assertEquals(1, repo.search("ab").valueOrNull()!!.size)
    }

    @Test
    fun searchSortsPinnedFirstThenModified() {
        val a = TestData.simpleNote("", "x alt").copy(modified = 10L)
        val b = TestData.simpleNote("", "x neu").copy(modified = 30L)
        val c = TestData.simpleNote("A", "x fix").copy(modified = 5L, pinned = true)
        listOf(a, b, c).forEach { ok(repo.save(it)) }
        assertEquals(listOf("x fix", "x neu", "x alt"), repo.search("x").valueOrNull()!!.map { it.title })
    }

    @Test
    fun listAllIsRecursiveAndSkipsTrash() {
        ok(repo.save(TestData.simpleNote("", "a").copy(modified = 1)))
        ok(repo.save(TestData.simpleNote("X", "b").copy(modified = 3)))
        ok(repo.save(TestData.simpleNote("X/Y", "c").copy(modified = 2)))
        val gone = TestData.simpleNote("X", "weg")
        ok(repo.save(gone))
        ok(repo.delete(gone.id))
        assertEquals(listOf("b", "c"), repo.listAll("X").valueOrNull()!!.map { it.title })
        assertEquals(3, repo.listAll("").valueOrNull()!!.size)
        assertEquals(0, repo.listAll("Gibt es nicht").valueOrNull()!!.size)
        assertEquals(PnoteError.InvalidFolder, repo.listAll("../x").errorOrNull())
    }

    @Test
    fun listAllFoldersIsRecursiveWithoutTrash() {
        ok(repo.createFolder("B"))
        ok(repo.createFolder("a/Sub"))
        val n = TestData.simpleNote("", "n")
        ok(repo.save(n))
        ok(repo.delete(n.id)) // legt .trash an, das nicht erscheinen darf
        assertEquals(listOf("a", "a/Sub", "B"), repo.listAllFolders().valueOrNull())
    }

    // ---------------------------------------------------------------- Papierkorb

    private fun trashed(note: local.papernotes.model.Note): TrashedNote {
        ok(repo.save(note))
        ok(repo.delete(note.id))
        return repo.listTrash().valueOrNull()!!.first { it.id == note.id }
    }

    @Test
    fun listTrashEmptyWithoutTrashDir() {
        assertEquals(emptyList<TrashedNote>(), repo.listTrash().valueOrNull())
        assertEquals(0, repo.trashCount())
    }

    @Test
    fun listTrashShowsMetadataNewestDeletedFirst() {
        val a = TestData.simpleNote("Ordner/Tief", "Alt").copy(modified = 77L)
        val b = TestData.simpleNote("", "Neu")
        val ta = trashed(a)
        val tb = trashed(b)
        val list = repo.listTrash().valueOrNull()!!
        assertEquals(listOf(tb.id, ta.id), list.map { it.id }) // zuletzt gelöscht zuerst
        assertEquals("Alt", ta.title)
        assertEquals("Ordner/Tief", ta.folderPath)
        assertEquals(77L, ta.modified)
        assertEquals("${a.id}.pnote", ta.entryName)
        assertEquals(1, ta.pageCount)
        assertTrue(ta.deleted > 1_000L && tb.deleted > ta.deleted)
        assertEquals(2, repo.trashCount())
    }

    @Test
    fun listTrashSkipsCorruptForeignAndSymlinkedEntries() {
        trashed(TestData.simpleNote("", "gut"))
        val trash = File(root, ".trash")
        File(trash, "${TestData.uuid()}.pnote").writeBytes(ByteArray(50) { 3 })
        File(trash, "fremd.pnote").writeText("meins")
        File(trash, "readme.txt").writeText("hallo")
        // Manifest-ID passt nicht zum Dateinamen
        val other = TestData.simpleNote("", "falsch")
        PnoteCodec.write(other, File(trash, "${TestData.uuid()}.pnote"))
        assertEquals(listOf("gut"), repo.listTrash().valueOrNull()!!.map { it.title })
        assertEquals(1, repo.trashCount())
    }

    @Test
    fun listTrashKeepsDuplicateIdsWithDistinctEntryNames() {
        val n = TestData.simpleNote("", "doppelt")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        ok(repo.save(n.copy(title = "doppelt v2")))
        ok(repo.delete(n.id))
        val list = repo.listTrash().valueOrNull()!!
        assertEquals(2, list.size)
        assertEquals(2, list.map { it.entryName }.toSet().size)
        assertEquals(listOf("doppelt v2", "doppelt"), list.map { it.title })
    }

    @Test
    fun restoreGoesBackToOriginalFolder() {
        val n = TestData.simpleNote("Ordner", "zurück").copy(favorite = true)
        val t = trashed(n)
        assertEquals(n.id, repo.restoreFromTrash(t.entryName).valueOrNull())
        assertTrue(File(root, "Ordner/${n.id}.pnote").isFile)
        assertFalse(File(root, ".trash/${t.entryName}").exists())
        assertEquals(n, repo.load(n.id).valueOrNull())
        assertEquals(0, repo.trashCount())
        assertEquals(PnoteError.NotFound, repo.restoreFromTrash(t.entryName).errorOrNull())
    }

    @Test
    fun restoreRecreatesMissingNestedFolder() {
        val n = TestData.simpleNote("Weg/Tief", "x")
        val t = trashed(n)
        assertTrue(File(root, "Weg").deleteRecursively()) // Ordner existiert nach dem Löschen nicht mehr
        assertFalse(File(root, "Weg").exists())
        ok(repo.restoreFromTrash(t.entryName))
        assertTrue(File(root, "Weg/Tief/${n.id}.pnote").isFile)
        assertEquals("Weg/Tief", repo.load(n.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun restoreFallsBackToRootWhenFolderPathIsUnusable() {
        // Der ursprüngliche Ordnerpfad ist inzwischen eine Datei: Wiederherstellung fällt auf die Wurzel zurück
        val n = TestData.simpleNote("Block", "x")
        val t = trashed(n)
        assertTrue(File(root, "Block").delete())
        File(root, "Block").writeText("ich bin eine Datei")
        assertEquals(n.id, repo.restoreFromTrash(t.entryName).valueOrNull())
        assertEquals("", repo.load(n.id).valueOrNull()!!.folderPath)
        assertTrue(File(root, "${n.id}.pnote").isFile)
        assertEquals("ich bin eine Datei", File(root, "Block").readText())
    }

    @Test
    fun restoreWithIdConflictKeepsBothUnderNewIds() {
        val n = TestData.simpleNote("A", "alt")
        val t = trashed(n)
        val active = n.copy(title = "aktiv")
        ok(repo.save(active))
        val newId = repo.restoreFromTrash(t.entryName).valueOrNull()!!
        assertTrue(newId != n.id)
        assertEquals("aktiv", repo.load(n.id).valueOrNull()!!.title) // nicht überschrieben
        val restored = repo.load(newId).valueOrNull()!!
        assertEquals("alt", restored.title)
        assertEquals("A", restored.folderPath)
        assertTrue(restored.pageIds.none { it in n.pageIds })
        assertEquals(2, repo.list("A").valueOrNull()!!.size)
        assertEquals(0, repo.trashCount())
    }

    @Test
    fun restoreOfDuplicateTrashEntriesRestoresBoth() {
        val n = TestData.simpleNote("", "d")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        ok(repo.save(n.copy(title = "d2")))
        ok(repo.delete(n.id))
        repo.listTrash().valueOrNull()!!.forEach { ok(repo.restoreFromTrash(it.entryName)) }
        assertEquals(setOf("d", "d2"), repo.list("").valueOrNull()!!.map { it.title }.toSet())
        assertEquals(0, repo.trashCount())
    }

    @Test
    fun restoreRejectsCorruptEntryAndKeepsFile() {
        trashed(TestData.simpleNote())
        val name = "${TestData.uuid()}.pnote"
        File(root, ".trash/$name").writeBytes(ByteArray(50) { 3 })
        assertTrue(repo.restoreFromTrash(name).errorOrNull() != null)
        assertTrue(File(root, ".trash/$name").isFile)
        assertEquals(0, repo.list("").valueOrNull()!!.size)
    }

    @Test
    fun deleteFromTrashRemovesOnlyThatEntry() {
        val a = trashed(TestData.simpleNote("", "a"))
        val b = trashed(TestData.simpleNote("", "b"))
        ok(repo.deleteFromTrash(a.entryName))
        assertFalse(File(root, ".trash/${a.entryName}").exists())
        assertTrue(File(root, ".trash/${b.entryName}").isFile)
        assertEquals(listOf("b"), repo.listTrash().valueOrNull()!!.map { it.title })
        assertEquals(PnoteError.NotFound, repo.deleteFromTrash(a.entryName).errorOrNull())
    }

    @Test
    fun emptyTrashRemovesNotesButNotForeignFiles() {
        trashed(TestData.simpleNote("", "a"))
        trashed(TestData.simpleNote("X", "b"))
        val corrupt = File(root, ".trash/${TestData.uuid()}.pnote").apply { writeBytes(ByteArray(50) { 3 }) }
        val foreign = File(root, ".trash/readme.txt").apply { writeText("hallo") }
        val active = TestData.simpleNote("", "aktiv")
        ok(repo.save(active))
        assertEquals(3, repo.emptyTrash().valueOrNull())
        assertEquals(0, repo.trashCount())
        assertFalse(corrupt.exists())
        assertEquals("hallo", foreign.readText())
        assertEquals(active, repo.load(active.id).valueOrNull())
        assertEquals(0, repo.emptyTrash().valueOrNull())
    }

    @Test
    fun emptyTrashWithoutTrashDirIsOk() {
        assertEquals(0, repo.emptyTrash().valueOrNull())
    }

    @Test
    fun trashOperationsRejectPathAttacks() {
        val t = trashed(TestData.simpleNote("", "sicher"))
        val outside = File(tmp.root, "outside.pnote").apply { writeText("draußen") }
        val sibling = File(root, "${TestData.uuid()}.pnote").also {
            PnoteCodec.write(TestData.simpleNote("", "aktiv"), it)
        }
        val evil = listOf(
            "../outside.pnote", "../../outside.pnote", "..", ".", "", "/abs.pnote", "a/b.pnote",
            "..\\${t.entryName}", "../${sibling.name}", ".trash/${t.entryName}", "${t.id}/../${t.entryName}",
            t.entryName + "/", t.entryName + "\u0000", t.id, "${t.id}.pnote.tmp", "%2e%2e/${t.entryName}",
            t.entryName.uppercase(),
        )
        for (name in evil) {
            assertEquals("restore $name", PnoteError.InvalidId, repo.restoreFromTrash(name).errorOrNull())
            assertEquals("delete $name", PnoteError.InvalidId, repo.deleteFromTrash(name).errorOrNull())
        }
        assertEquals("draußen", outside.readText())
        assertTrue(sibling.isFile)
        assertEquals(1, repo.trashCount())
        assertEquals(listOf("notes", "outside.pnote"), tmp.root.list()!!.sorted())
    }

    @Test
    fun trashSymlinkIsNotFollowed() {
        val n = TestData.simpleNote("", "x")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        val real = File(root, ".trash")
        val moved = File(tmp.root, "fremdtrash")
        assertTrue(real.renameTo(moved))
        try {
            java.nio.file.Files.createSymbolicLink(real.toPath(), moved.toPath())
        } catch (e: Exception) {
            return // Plattform ohne Symlinks
        }
        assertEquals(0, repo.listTrash().valueOrNull()!!.size)
        assertEquals(0, repo.trashCount())
        assertEquals(PnoteError.InvalidId, repo.restoreFromTrash("${n.id}.pnote").errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.deleteFromTrash("${n.id}.pnote").errorOrNull())
        assertEquals(0, repo.emptyTrash().valueOrNull())
        assertTrue(File(moved, "${n.id}.pnote").isFile)
    }

    // ---------------------------------------------------------------- Ordner samt Inhalt löschen / verschieben

    @Test
    fun deleteFolderWithContentsTrashesAllNotesAndRemovesTree() {
        val a = TestData.simpleNote("F", "a")
        val b = TestData.simpleNote("F/Tief/Tiefer", "b")
        val c = TestData.simpleNote("Anderer", "c")
        ok(repo.save(a))
        ok(repo.save(b))
        ok(repo.save(c))
        ok(repo.createFolder("F/Leer"))
        ok(repo.deleteFolderWithContents("F"))
        assertFalse(File(root, "F").exists())
        assertEquals(setOf(a.id, b.id), repo.listTrash().valueOrNull()!!.map { it.id }.toSet())
        assertEquals("F/Tief/Tiefer", repo.listTrash().valueOrNull()!!.first { it.id == b.id }.folderPath)
        assertEquals(listOf("c"), repo.list("Anderer").valueOrNull()!!.map { it.title })
        // aus dem Papierkorb wiederherstellbar
        ok(repo.restoreFromTrash("${b.id}.pnote"))
        assertEquals("F/Tief/Tiefer", repo.load(b.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun deleteFolderWithContentsOnEmptyFolderAndErrors() {
        ok(repo.createFolder("Leer"))
        ok(repo.deleteFolderWithContents("Leer"))
        assertFalse(File(root, "Leer").exists())
        assertEquals(PnoteError.NotFound, repo.deleteFolderWithContents("Leer").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolderWithContents("").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolderWithContents("../x").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolderWithContents(".trash").errorOrNull())
    }

    @Test
    fun deleteFolderWithContentsKeepsForeignFilesAndReportsNotEmpty() {
        val n = TestData.simpleNote("F", "n")
        ok(repo.save(n))
        File(root, "F/readme.txt").writeText("meins")
        assertEquals(PnoteError.NotEmpty, repo.deleteFolderWithContents("F").errorOrNull())
        assertEquals("meins", File(root, "F/readme.txt").readText())
        assertEquals(1, repo.trashCount()) // Notiz ist trotzdem im Papierkorb
    }

    @Test
    fun deleteFolderWithContentsDoesNotFollowSymlinks() {
        val outside = tmp.newFolder("draussen")
        val foreign = File(outside, "${TestData.uuid()}.pnote")
        foreign.writeText("fremd")
        ok(repo.createFolder("F"))
        try {
            java.nio.file.Files.createSymbolicLink(File(root, "F/Link").toPath(), outside.toPath())
        } catch (e: Exception) {
            return // Plattform ohne Symlinks
        }
        assertEquals(PnoteError.NotEmpty, repo.deleteFolderWithContents("F").errorOrNull())
        assertTrue(foreign.isFile)
        assertEquals("fremd", foreign.readText())
    }

    @Test
    fun moveFolderMovesContentAndUpdatesManifests() {
        val a = TestData.simpleNote("Quelle", "a")
        val b = TestData.simpleNote("Quelle/Sub", "b")
        ok(repo.save(a))
        ok(repo.save(b))
        ok(repo.createFolder("Ziel"))
        ok(repo.moveFolder("Quelle", "Ziel"))
        assertFalse(File(root, "Quelle").exists())
        assertTrue(File(root, "Ziel/Quelle/${a.id}.pnote").isFile)
        assertEquals("Ziel/Quelle", repo.load(a.id).valueOrNull()!!.folderPath)
        assertEquals("Ziel/Quelle/Sub", repo.load(b.id).valueOrNull()!!.folderPath)
    }

    @Test
    fun moveFolderToRootAndSameParent() {
        val n = TestData.simpleNote("A/B", "n")
        ok(repo.save(n))
        ok(repo.moveFolder("A/B", "A")) // liegt schon dort: nichts passiert
        assertEquals("A/B", repo.load(n.id).valueOrNull()!!.folderPath)
        ok(repo.moveFolder("A/B", ""))
        assertEquals("B", repo.load(n.id).valueOrNull()!!.folderPath)
        assertTrue(File(root, "A").isDirectory)
    }

    @Test
    fun moveFolderRejectsSelfDescendantsAndInvalid() {
        ok(repo.save(TestData.simpleNote("A/B/C", "n")))
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("A", "A").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("A", "A/B").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("A", "A/B/C").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("", "A").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("A", "../x").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder("../A", "").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.moveFolder(".trash", "A").errorOrNull())
        assertEquals(PnoteError.NotFound, repo.moveFolder("Gibt es nicht", "A").errorOrNull())
        assertTrue(File(root, "A/B/C").isDirectory)
    }

    @Test
    fun moveFolderPrefixSiblingIsNotADescendant() {
        ok(repo.save(TestData.simpleNote("A", "n")))
        ok(repo.createFolder("AB"))
        ok(repo.moveFolder("A", "AB")) // "AB" beginnt mit "A", ist aber kein Unterordner
        assertTrue(File(root, "AB/A").isDirectory)
    }

    @Test
    fun moveFolderRejectsExistingNameAndDepthLimit() {
        ok(repo.createFolder("X/Gleich"))
        ok(repo.createFolder("Gleich"))
        assertEquals(PnoteError.AlreadyExists, repo.moveFolder("Gleich", "X").errorOrNull())
        assertTrue(File(root, "Gleich").isDirectory)
        val deep = (1..16).joinToString("/") { "e$it" }
        ok(repo.createFolder(deep))
        ok(repo.createFolder("Quelle/Unter"))
        assertEquals(PnoteError.LimitExceeded, repo.moveFolder("Quelle", deep).errorOrNull())
        assertTrue(File(root, "Quelle/Unter").isDirectory)
    }
}
