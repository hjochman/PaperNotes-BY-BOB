package local.papernotes.storage

import org.junit.Assert.assertEquals
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOrderingTest {
    private fun s(title: String, modified: Long, pinned: Boolean = false, id: String = title) =
        NoteSummary(id, title, "", 0, modified, favorite = false, pinned = pinned, pageCount = 1)

    @Test
    fun pinnedFirstThenModifiedDescending() {
        val sorted = NoteOrdering.sort(
            listOf(s("alt", 10), s("neu", 50), s("fix-alt", 5, pinned = true), s("fix-neu", 7, pinned = true), s("mitte", 30)),
        )
        assertEquals(listOf("fix-neu", "fix-alt", "neu", "mitte", "alt"), sorted.map { it.title })
    }

    @Test
    fun tieBreaksByIdForStableOrder() {
        val sorted = NoteOrdering.sort(listOf(s("b", 1, id = "b"), s("a", 1, id = "a")))
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    private fun c(title: String, created: Long, pinned: Boolean = false) =
        NoteSummary(title, title, "", created, 0, favorite = false, pinned = pinned, pageCount = 1)

    private val plainTitles = String.CASE_INSENSITIVE_ORDER

    private fun order(order: SortOrder, notes: List<NoteSummary>) =
        notes.sortedWith(NoteOrdering.comparator(order, plainTitles)).map { it.title }

    @Test
    fun modifiedAscendingKeepsPinnedFirst() {
        val notes = listOf(s("alt", 10), s("neu", 50), s("fix", 99, pinned = true), s("mitte", 30))
        assertEquals(listOf("fix", "alt", "mitte", "neu"), order(SortOrder.MODIFIED_ASC, notes))
    }

    @Test
    fun createdDescending() {
        val notes = listOf(c("a", 1), c("b", 3), c("c", 2), c("fix", 0, pinned = true))
        assertEquals(listOf("fix", "b", "c", "a"), order(SortOrder.CREATED_DESC, notes))
    }

    @Test
    fun titleAscendingAndDescendingIgnoreCase() {
        val notes = listOf(s("birne", 1), s("Apfel", 2), s("Zitrone", 3), s("fix", 4, pinned = true))
        assertEquals(listOf("fix", "Apfel", "birne", "Zitrone"), order(SortOrder.TITLE_ASC, notes))
        assertEquals(listOf("fix", "Zitrone", "birne", "Apfel"), order(SortOrder.TITLE_DESC, notes))
    }

    @Test
    fun germanCollatorSortsUmlautsNextToBaseLetter() {
        val german = Collator.getInstance(Locale.GERMAN)
        val notes = listOf(s("Zebra", 1), s("Ärger", 2), s("Apfel", 3), s("Aal", 4))
        val sorted = notes.sortedWith(NoteOrdering.comparator(SortOrder.TITLE_ASC, Comparator { a, b -> german.compare(a, b) }))
        assertEquals(listOf("Aal", "Apfel", "Ärger", "Zebra"), sorted.map { it.title })
    }

    @Test
    fun parseFallsBackToDefault() {
        assertEquals(SortOrder.TITLE_DESC, SortOrder.parse("TITLE_DESC"))
        assertEquals(SortOrder.DEFAULT, SortOrder.parse(null))
        assertEquals(SortOrder.DEFAULT, SortOrder.parse("quatsch"))
    }
}

class NoteTitlesTest {
    @Test
    fun validTitles() {
        assertTrue(NoteTitles.isValid("Übung – Äpfel"))
        assertTrue(NoteTitles.isValid("a".repeat(NoteTitles.MAX_LENGTH)))
    }

    @Test
    fun invalidTitles() {
        assertFalse(NoteTitles.isValid(""))
        assertFalse(NoteTitles.isValid("   "))
        assertFalse(NoteTitles.isValid("a".repeat(NoteTitles.MAX_LENGTH + 1)))
        assertFalse(NoteTitles.isValid("a\tb"))
        assertFalse(NoteTitles.isValid("a\u0000b"))
    }

    @Test
    fun sanitizeTrimsFiltersAndLimits() {
        assertEquals("ab", NoteTitles.sanitize("  a\nb \u0007 "))
        assertEquals(NoteTitles.MAX_LENGTH, NoteTitles.sanitize("x".repeat(500)).length)
        assertEquals("", NoteTitles.sanitize(" \n "))
    }

    @Test
    fun copyTitleAppendsAndFitsLimit() {
        assertEquals("Plan (Kopie)", NoteTitles.copyTitle("Plan") { "$it (Kopie)" })
        val long = NoteTitles.copyTitle("x".repeat(NoteTitles.MAX_LENGTH)) { "$it (Kopie)" }
        assertEquals(NoteTitles.MAX_LENGTH, long.length)
        assertTrue(long.endsWith(" (Kopie)"))
        assertTrue(NoteTitles.isValid(long))
    }
}
