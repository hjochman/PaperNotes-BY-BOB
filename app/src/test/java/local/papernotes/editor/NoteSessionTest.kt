package local.papernotes.editor

import local.papernotes.model.AudioAttachment
import local.papernotes.model.ImageAttachment
import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import local.papernotes.model.Point
import local.papernotes.model.Stroke
import local.papernotes.storage.PnoteError
import local.papernotes.storage.PnoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

class NoteSessionTest {
    private class FakeScheduler : SaveScheduler {
        class Job(val at: Long, val task: Runnable, var cancelled: Boolean = false)

        var now = 0L
        val jobs = mutableListOf<Job>()

        override fun postDelayed(delayMs: Long, task: Runnable): Any = Job(now + delayMs, task).also { jobs.add(it) }

        override fun cancel(token: Any) {
            (token as Job).cancelled = true
        }

        fun advance(ms: Long) {
            now += ms
            jobs.filter { !it.cancelled && it.at <= now }.forEach {
                it.cancelled = true
                it.task.run()
            }
        }
    }

    private class RecordingStore : NoteStore {
        val saved = mutableListOf<Note>()
        var result: PnoteResult<Unit> = PnoteResult.Ok(Unit)

        override fun save(note: Note): PnoteResult<Unit> {
            saved.add(note)
            return result
        }
    }

    private val direct = Executor { it.run() }
    private lateinit var store: RecordingStore
    private lateinit var scheduler: FakeScheduler
    private lateinit var session: NoteSession
    private var time = 1_000L

    private fun stroke(seed: Int) = Stroke(2, 4f, 0xFF000000.toInt(), listOf(Point(seed.toFloat(), 1f, 1L, 0.5f)))

    @Before
    fun setUp() {
        store = RecordingStore()
        scheduler = FakeScheduler()
        session = NoteSession(NoteFactory.newNote("T", now = 1L), store, direct, scheduler, clock = { time++ })
    }

    @Test
    fun imagesAreStoredAndUnusedMediaIsPruned() {
        val a = ImageAttachment("i1", "a.png", 0f, 0f, 10f, 10f)
        session.onImagesChanged(listOf(a), mapOf("a.png" to byteArrayOf(1)))
        assertEquals(listOf(a), session.currentPage.images)
        assertEquals(setOf("a.png"), session.note.media.keys)
        assertTrue(session.hasUnsavedChanges)
        session.onImagesChanged(emptyList())
        assertTrue(session.note.media.isEmpty())
    }

    @Test
    fun audioIsAttachedToPageAndMediaSurvivesPruning() {
        val pageId = session.currentPage.id
        val audio = AudioAttachment("a1", "a.m4a", "Aufnahme 1", 1_000L)
        assertTrue(session.onAudioAdded(pageId, audio, byteArrayOf(1, 2)))
        assertEquals(listOf(audio), session.currentPage.audios)
        assertEquals(setOf("a.m4a"), session.note.media.keys)
        assertTrue(session.hasUnsavedChanges)
        session.onImagesChanged(emptyList()) // Bilder entfernen darf die Tonspur nicht verwerfen
        assertEquals(setOf("a.m4a"), session.note.media.keys)
    }

    @Test
    fun audioForUnknownPageIsRejected() {
        val audio = AudioAttachment("a1", "a.m4a", "Aufnahme 1", 1_000L)
        assertFalse(session.onAudioAdded("missing", audio, byteArrayOf(1)))
        assertTrue(session.note.media.isEmpty())
    }

    @Test
    fun startsOnFirstPage() {
        assertEquals("1/1", session.currentPageNumberText())
        assertEquals(1, session.pageCount)
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun changeIsStoredInModelAndSavedAfterDebounce() {
        session.onStrokesChanged(listOf(stroke(1)))
        assertEquals(listOf(stroke(1)), session.currentPage.strokes)
        assertTrue(session.hasUnsavedChanges)
        scheduler.advance(NoteSession.DEBOUNCE_MS - 1)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(1)
        assertEquals(1, store.saved.size)
        assertEquals(listOf(stroke(1)), store.saved[0].pages.values.single().strokes)
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun furtherChangesRestartDebounce() {
        session.onStrokesChanged(listOf(stroke(1)))
        scheduler.advance(4_000)
        session.onStrokesChanged(listOf(stroke(1), stroke(2)))
        scheduler.advance(4_000)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(1_000)
        assertEquals(1, store.saved.size)
        assertEquals(2, store.saved[0].pages.values.single().strokes.size)
    }

    @Test
    fun saveNowCancelsDebounceAndSkipsWhenClean() {
        session.save()
        assertTrue(store.saved.isEmpty())
        session.onStrokesChanged(listOf(stroke(1)))
        session.save()
        assertEquals(1, store.saved.size)
        scheduler.advance(10_000)
        assertEquals(1, store.saved.size)
        session.save()
        assertEquals(1, store.saved.size)
    }

    @Test
    fun identicalStrokesAreNoChange() {
        session.onStrokesChanged(emptyList())
        assertFalse(session.hasUnsavedChanges)
        assertTrue(scheduler.jobs.isEmpty())
    }

    @Test
    fun modifiedTimeIsUpdated() {
        val before = session.note.modified
        session.onStrokesChanged(listOf(stroke(1)))
        assertNotEquals(before, session.note.modified)
    }

    @Test
    fun addPageInsertsAfterCurrentSavesAndSwitches() {
        val first = session.currentPage
        session.onStrokesChanged(listOf(stroke(1)))
        val added = session.addPage()
        assertEquals(2, session.pageCount)
        assertEquals("2/2", session.currentPageNumberText())
        assertEquals(added.id, session.currentPage.id)
        assertEquals(first.width, added.width)
        assertEquals(first.template, added.template)
        assertTrue(added.strokes.isEmpty())
        assertEquals(1, store.saved.size)
        assertEquals(listOf(first.id, added.id), store.saved[0].pageIds)
        assertEquals(listOf(stroke(1)), store.saved[0].pages.getValue(first.id).strokes)
    }

    @Test
    fun addPageInTheMiddleKeepsOrder() {
        val p1 = session.currentPage
        val p2 = session.addPage()
        session.goToPage(0)
        val p3 = session.addPage()
        assertEquals(listOf(p1.id, p3.id, p2.id), session.note.pageIds)
        assertEquals(1, session.pageIndex)
    }

    @Test
    fun goToPageSavesPendingChangesFirst() {
        session.addPage()
        store.saved.clear()
        session.onStrokesChanged(listOf(stroke(5)))
        assertTrue(session.goToPage(0))
        assertEquals(1, store.saved.size)
        assertEquals("1/2", session.currentPageNumberText())
        assertTrue(session.currentPage.strokes.isEmpty())
        assertTrue(session.goToPage(1))
        assertEquals(listOf(stroke(5)), session.currentPage.strokes)
    }

    @Test
    fun goToPageRejectsInvalidIndexAndSamePage() {
        session.addPage()
        assertFalse(session.goToPage(-1))
        assertFalse(session.goToPage(2))
        assertFalse(session.goToPage(1))
        assertEquals(1, session.pageIndex)
    }

    @Test
    fun historyIsKeptPerPage() {
        session.currentHistory.record(listOf(stroke(1)))
        session.addPage()
        assertFalse(session.currentHistory.canUndo)
        session.goToPage(0)
        assertTrue(session.currentHistory.canUndo)
    }

    @Test
    fun deletePageRemovesAndClampsIndex() {
        val p1 = session.currentPage
        val p2 = session.addPage()
        store.saved.clear()
        assertTrue(session.deletePage())
        assertEquals(1, session.pageCount)
        assertEquals(0, session.pageIndex)
        assertEquals(p1.id, session.currentPage.id)
        assertFalse(session.note.pages.containsKey(p2.id))
        assertEquals(1, store.saved.size)
        assertEquals(listOf(p1.id), store.saved[0].pageIds)
    }

    @Test
    fun deleteFirstOfTwoStaysOnIndexZero() {
        val p1 = session.currentPage
        val p2 = session.addPage()
        session.goToPage(0)
        assertTrue(session.deletePage())
        assertEquals(p2.id, session.currentPage.id)
        assertFalse(session.note.pages.containsKey(p1.id))
    }

    @Test
    fun setBookmarkSetsRemovesAndSaves() {
        val p2 = session.addPage()
        store.saved.clear()
        assertTrue(session.setBookmark(0, true))
        assertTrue(session.note.pages.getValue(session.note.pageIds[0]).bookmarked)
        assertFalse(session.note.pages.getValue(p2.id).bookmarked)
        assertEquals(1, session.pageIndex)
        assertTrue(session.hasUnsavedChanges)
        scheduler.advance(NoteSession.DEBOUNCE_MS)
        assertEquals(1, store.saved.size)
        assertTrue(store.saved[0].pages.getValue(store.saved[0].pageIds[0]).bookmarked)
        assertTrue(session.setBookmark(0, false))
        assertFalse(session.note.pages.getValue(session.note.pageIds[0]).bookmarked)
    }

    @Test
    fun setBookmarkWithoutChangeOrInvalidIndexIsNoop() {
        assertFalse(session.setBookmark(0, false))
        assertFalse(session.hasUnsavedChanges)
        assertFalse(session.setBookmark(5, true))
        assertFalse(session.setBookmark(-1, true))
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun bookmarkSurvivesStrokeChangesAndPageSwitch() {
        session.setBookmark(0, true)
        session.onStrokesChanged(listOf(stroke(1)))
        session.addPage()
        session.goToPage(0)
        assertTrue(session.currentPage.bookmarked)
        assertEquals(listOf(stroke(1)), session.currentPage.strokes)
    }

    @Test
    fun addPageAtEndAppendsAndSwitches() {
        val p1 = session.currentPage
        val p2 = session.addPage()
        session.goToPage(0)
        val p3 = session.addPage(atEnd = true)
        assertEquals(listOf(p1.id, p2.id, p3.id), session.note.pageIds)
        assertEquals(2, session.pageIndex)
        assertEquals(p3.id, session.currentPage.id)
    }

    @Test
    fun deletePageAtKeepsCurrentPageSelected() {
        val p1 = session.currentPage
        session.addPage()
        val p3 = session.addPage()
        assertEquals(2, session.pageIndex)
        assertTrue(session.deletePageAt(0))
        assertEquals(1, session.pageIndex)
        assertEquals(p3.id, session.currentPage.id)
        assertFalse(session.note.pages.containsKey(p1.id))
        assertFalse(session.deletePageAt(7))
        assertTrue(session.deletePageAt(1))
        assertEquals(0, session.pageIndex)
        assertFalse(session.deletePageAt(0))
    }

    @Test
    fun lastPageCannotBeDeleted() {
        assertFalse(session.deletePage())
        assertEquals(1, session.pageCount)
    }

    @Test
    fun saveFailureIsReportedAndDoesNotThrow() {
        store.result = PnoteResult.Err(PnoteError.Io)
        var ok: Boolean? = null
        session.onSaveResult = { ok = it }
        session.onStrokesChanged(listOf(stroke(1)))
        session.save()
        assertEquals(false, ok)
    }

    @Test
    fun saveRunsOnWriterExecutorNotInline() {
        val queue = ArrayDeque<Runnable>()
        val s = NoteSession(NoteFactory.newNote("T"), store, { queue.add(it) }, scheduler)
        s.onStrokesChanged(listOf(stroke(1)))
        s.save()
        assertTrue(store.saved.isEmpty())
        queue.removeFirst().run()
        assertEquals(1, store.saved.size)
    }

    @Test
    fun closeSavesPendingChanges() {
        session.onStrokesChanged(listOf(stroke(1)))
        session.close()
        assertEquals(1, store.saved.size)
    }

    @Test
    fun setTemplateChangesOnlyCurrentPageAndKeepsStrokes() {
        session.onStrokesChanged(listOf(stroke(1)))
        val first = session.currentPage.id
        session.addPage()
        session.goToPage(0)
        assertTrue(session.setTemplate("grid", allPages = false))
        assertEquals("grid", session.currentPage.template)
        assertEquals(listOf(stroke(1)), session.currentPage.strokes)
        assertEquals(first, session.currentPage.id)
        assertEquals("blank", session.note.pages.getValue(session.note.pageIds[1]).template)
        assertTrue(session.hasUnsavedChanges)
    }

    @Test
    fun setTemplateForAllPages() {
        session.addPage()
        session.addPage()
        assertTrue(session.setTemplate("cornell", allPages = true))
        assertTrue(session.note.pages.values.all { it.template == "cornell" })
    }

    @Test
    fun setTemplateIsSavedAfterDebounceAndRejectsUnknownOrSame() {
        assertFalse(session.setTemplate("nope", allPages = false))
        assertFalse(session.setTemplate("blank", allPages = false))
        assertFalse(session.hasUnsavedChanges)
        assertTrue(session.setTemplate("todo", allPages = false))
        scheduler.advance(NoteSession.DEBOUNCE_MS)
        assertEquals("todo", store.saved.single().pages.values.single().template)
    }

    @Test
    fun newPageInheritsCurrentTemplate() {
        session.setTemplate("dotted", allPages = false)
        val page = session.addPage()
        assertEquals("dotted", page.template)
        assertEquals("dotted", session.currentPage.template)
    }

    @Test
    fun renameChangesTitleAndSavesAfterDebounce() {
        assertTrue(session.rename("Neu"))
        assertEquals("Neu", session.note.title)
        assertTrue(session.hasUnsavedChanges)
        scheduler.advance(NoteSession.DEBOUNCE_MS)
        assertEquals("Neu", store.saved.single().title)
    }

    @Test
    fun debounceWithoutMediaIs5Seconds() {
        assertEquals(5_000L, NoteSession.debounceMs(false))
        assertEquals(30_000L, NoteSession.debounceMs(true))
    }

    @Test
    fun noteWithMediaIsSavedOnlyAfterLongDebounce() {
        val pageId = session.currentPage.id
        session.onAudioAdded(pageId, AudioAttachment("a1", "a.m4a", "Aufnahme 1", 1_000L), byteArrayOf(1, 2))
        scheduler.advance(NoteSession.DEBOUNCE_MS)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(NoteSession.DEBOUNCE_MEDIA_MS - NoteSession.DEBOUNCE_MS - 1)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(1)
        assertEquals(1, store.saved.size)
        assertTrue(store.saved[0].media.containsKey("a.m4a"))
        assertFalse(session.hasUnsavedChanges)
    }

    @Test
    fun changesInNoteWithMediaRestartLongDebounce() {
        session.onAudioAdded(session.currentPage.id, AudioAttachment("a1", "a.m4a", "Aufnahme 1", 1_000L), byteArrayOf(1))
        scheduler.advance(20_000)
        session.onStrokesChanged(listOf(stroke(1)))
        scheduler.advance(29_999)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(1)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun firstMediaSwitchesPendingDebounceToLongOne() {
        session.onStrokesChanged(listOf(stroke(1)))
        scheduler.advance(3_000)
        // Das erste Medium ersetzt den ausstehenden 5-s-Lauf durch einen 30-s-Lauf.
        session.onImagesChanged(
            listOf(ImageAttachment("i1", "a.png", 0f, 0f, 10f, 10f)),
            mapOf("a.png" to byteArrayOf(1)),
        )
        scheduler.advance(NoteSession.DEBOUNCE_MS)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(NoteSession.DEBOUNCE_MEDIA_MS - NoteSession.DEBOUNCE_MS)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun removingLastMediaSwitchesBackToShortDebounce() {
        session.onImagesChanged(
            listOf(ImageAttachment("i1", "a.png", 0f, 0f, 10f, 10f)),
            mapOf("a.png" to byteArrayOf(1)),
        )
        session.onImagesChanged(emptyList())
        assertTrue(session.note.media.isEmpty())
        scheduler.advance(NoteSession.DEBOUNCE_MS - 1)
        assertTrue(store.saved.isEmpty())
        scheduler.advance(1)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun safetySavePointsStaySynchronousForNoteWithMedia() {
        session.onAudioAdded(session.currentPage.id, AudioAttachment("a1", "a.m4a", "Aufnahme 1", 1_000L), byteArrayOf(1))
        session.save() // onPause/onStop
        assertEquals(1, store.saved.size)
        session.onStrokesChanged(listOf(stroke(1)))
        session.addPage() // Seite hinzufügen speichert sofort
        assertEquals(2, store.saved.size)
        session.onStrokesChanged(listOf(stroke(2)))
        session.goToPage(0) // Seitenwechsel speichert sofort
        assertEquals(3, store.saved.size)
        session.onStrokesChanged(listOf(stroke(3)))
        session.close() // onDestroy
        assertEquals(4, store.saved.size)
    }

    @Test
    fun renameRejectsInvalidOrSameTitle() {
        assertFalse(session.rename("  "))
        assertFalse(session.rename("a".repeat(101)))
        assertFalse(session.rename("T"))
        assertFalse(session.hasUnsavedChanges)
        assertEquals("T", session.note.title)
    }
}
