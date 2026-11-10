package local.papernotes.home

import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun summary(id: String, modified: Long) = NoteSummary(id, id, "", modified, modified, false, false, 1)

    private fun metrics(list: Boolean = false) = GridMetrics.compute(1404, 2f, list, landscape = false)

    @Test
    fun lastSegmentAndParent() {
        assertEquals("B", HomeModel.lastSegment("A/B"))
        assertEquals("A", HomeModel.lastSegment("A"))
        assertEquals("A", HomeModel.parent("A/B"))
        assertEquals("", HomeModel.parent("A"))
    }

    @Test
    fun entriesOrderNewTileFoldersThenNotes() {
        val folder = FolderItem("F", "F", 0, 0, emptyList())
        val content = HomeContent(listOf(folder), listOf(summary("n1", 1)), 1)
        val withTile = HomeModel.entries(content, true)
        assertTrue(withTile[0] is Entry.NewNote)
        assertTrue(withTile[1] is Entry.Folder)
        assertTrue(withTile[2] is Entry.Note)
        assertEquals(2, HomeModel.entries(content, false).size)
    }

    @Test
    fun foldersSpanTwoColumnsAndFillRows() {
        val f = { Entry.Folder(FolderItem("F", "F", 0, 0, emptyList())) }
        val entries = listOf(f(), f(), f(), Entry.Note(summary("n", 1)))
        val m = metrics()
        val pages = HomeModel.pageLayout(entries, m, 100_000)
        assertEquals(1, pages.size)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), pages[0])
    }

    @Test
    fun noteGridHasFourColumnsAndPagesBreakByHeight() {
        val m = metrics()
        assertEquals(4, m.columns)
        val entries = (1..20).map { Entry.Note(summary("n$it", it.toLong())) }
        val pages = HomeModel.pageLayout(entries, m, m.topPadding + m.noteRowHeight * 3 + 1)
        assertEquals(2, pages.size)
        assertEquals(3, pages[0].size)
        assertEquals(2, pages[1].size)
        assertEquals(20, pages.sumOf { p -> p.sumOf { it.size } })
    }

    @Test
    fun listModeHasOneEntryPerRow() {
        val m = metrics(list = true)
        assertEquals(1, m.columns)
        val entries = (1..5).map { Entry.Note(summary("n$it", it.toLong())) }
        val pages = HomeModel.pageLayout(entries, m, m.topPadding + m.listRowHeight * 2)
        assertEquals(3, pages.size)
        assertTrue(pages.all { p -> p.all { it.size == 1 } })
    }

    @Test
    fun landscapeUsesSixColumns() {
        assertEquals(6, GridMetrics.compute(1872, 2f, false, landscape = true).columns)
    }

    @Test
    fun emptyEntriesGiveNoPages() {
        assertTrue(HomeModel.pageLayout(emptyList(), metrics(), 1000).isEmpty())
    }

    @Test
    fun loadCountsNotesInSubfoldersAndTakesFourNewestPreviews() {
        var now = 1000L
        val repo = NoteRepository(tmp.newFolder("root"), clock = { now++ })
        repo.createFolder("F")
        repo.createFolder("F/G")
        (1..5).forEach { repo.save(NoteFactory.newNote("n$it", "F", now = 1000L + it)) }
        repo.save(NoteFactory.newNote("deep", "F/G", now = 2000L))
        repo.save(NoteFactory.newNote("top", "", now = 3000L))
        val content = HomeModel.load(repo, "").valueOrNull()!!
        assertEquals(7, content.totalCount)
        assertEquals(1, content.notes.size)
        val f = content.folders.single()
        assertEquals(6, f.noteCount)
        assertEquals(HomeModel.MAX_FOLDER_PREVIEWS, f.previews.size)
        assertEquals("deep", f.previews.first().title)
        assertEquals(2000L, f.modified)
    }

    @Test
    fun loadUncategorizedReturnsOnlyRootNotesWithoutFolders() {
        var now = 1000L
        val repo = NoteRepository(tmp.newFolder("root"), clock = { now++ })
        repo.createFolder("Work")
        repo.save(NoteFactory.newNote("in_folder", "Work", now = 1000L))
        repo.save(NoteFactory.newNote("root_1", "", now = 2000L))
        repo.save(NoteFactory.newNote("root_2", "", now = 3000L))

        val content = HomeModel.loadUncategorized(repo).valueOrNull()!!
        assertTrue(content.folders.isEmpty())
        assertEquals(2, content.totalCount)
        assertEquals(listOf("root_2", "root_1"), content.notes.map { it.title })
    }

    @Test
    fun loadFavoritesReturnsOnlyFavoriteNotesAcrossAllFolders() {
        var now = 1000L
        val repo = NoteRepository(tmp.newFolder("root"), clock = { now++ })
        repo.createFolder("Work")
        val n1 = NoteFactory.newNote("fav_in_folder", "Work", now = 1000L)
        val n2 = NoteFactory.newNote("non_fav_in_folder", "Work", now = 2000L)
        val n3 = NoteFactory.newNote("fav_in_root", "", now = 3000L)
        val n4 = NoteFactory.newNote("non_fav_in_root", "", now = 4000L)

        repo.save(n1)
        repo.save(n2)
        repo.save(n3)
        repo.save(n4)

        repo.setFavorite(n1.id, true)
        repo.setFavorite(n3.id, true)

        val content = HomeModel.loadFavorites(repo).valueOrNull()!!
        assertTrue(content.folders.isEmpty())
        assertEquals(2, content.totalCount)
        assertEquals(listOf("fav_in_root", "fav_in_folder"), content.notes.map { it.title })
    }

    @Test
    fun categoryCountsComputesAllCategoriesCorrectly() {
        var now = 1000L
        val repo = NoteRepository(tmp.newFolder("root"), clock = { now++ })
        repo.createFolder("FolderA")
        val n1 = NoteFactory.newNote("n1", "FolderA", now = 1000L)
        val n2 = NoteFactory.newNote("n2", "FolderA", now = 2000L)
        val n3 = NoteFactory.newNote("n3", "", now = 3000L)
        val n4 = NoteFactory.newNote("n4", "", now = 4000L)

        repo.save(n1)
        repo.save(n2)
        repo.save(n3)
        repo.save(n4)

        repo.setFavorite(n1.id, true)
        repo.setFavorite(n4.id, true)

        repo.delete(n2.id) // moves n2 to trash

        val counts = HomeModel.categoryCounts(repo)
        assertEquals(3, counts.all)
        assertEquals(2, counts.uncategorized)
        assertEquals(2, counts.favorites)
        assertEquals(1, counts.trash)
    }
}
