package local.papernotes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNamesTest {
    @Test
    fun plainTitleIsKept() {
        assertEquals("Meeting Notes 2026-01-02", ExportFileNames.base("Meeting Notes 2026-01-02"))
        assertEquals("Größe & Übung", ExportFileNames.base("Größe & Übung"))
    }

    @Test
    fun pathSeparatorsAndForbiddenCharactersAreReplaced() {
        val name = ExportFileNames.base("a/b\\c:d*e?f\"g<h>i|j")
        assertEquals("a_b_c_d_e_f_g_h_i_j", name)
    }

    @Test
    fun dotDotNeverSurvives() {
        assertFalse(ExportFileNames.base("../../etc/passwd").contains(".."))
        assertFalse(ExportFileNames.base("a..b").contains(".."))
        assertEquals("_._etc", ExportFileNames.base("/../etc"))
        assertFalse(ExportFileNames.base("....").contains("/"))
    }

    @Test
    fun leadingAndTrailingDotsAndSpacesAreRemoved() {
        assertEquals("hidden", ExportFileNames.base(".hidden."))
        assertEquals("x", ExportFileNames.base("  x  "))
    }

    @Test
    fun controlCharactersAreReplaced() {
        val name = ExportFileNames.base("a\u0000b\nc\u001Fd\u007F")
        assertTrue(name.none { it.isISOControl() })
        assertEquals("a_b_c_d_", name)
    }

    @Test
    fun emptyOrOnlyUnsafeBecomesFallback() {
        assertEquals(ExportFileNames.FALLBACK, ExportFileNames.base(""))
        assertEquals(ExportFileNames.FALLBACK, ExportFileNames.base("   "))
        assertEquals(ExportFileNames.FALLBACK, ExportFileNames.base("..."))
    }

    @Test
    fun lengthIsLimited() {
        val name = ExportFileNames.base("x".repeat(500))
        assertEquals(ExportFileNames.MAX_BASE_LENGTH, name.length)
    }

    @Test
    fun surrogatePairIsNotSplit() {
        val title = "a".repeat(ExportFileNames.MAX_BASE_LENGTH - 1) + "\uD83D\uDE00" // Emoji beginnt an der Grenze
        val name = ExportFileNames.base(title)
        assertTrue(name.length <= ExportFileNames.MAX_BASE_LENGTH)
        assertFalse(Character.isHighSurrogate(name.last()))
    }

    @Test
    fun uniqueAddsCounterForDuplicatesCaseInsensitive() {
        val names = ExportFileNames.unique(listOf("Plan", "plan", "Plan", "Other"))
        assertEquals(listOf("Plan.pdf", "plan (2).pdf", "Plan (3).pdf", "Other.pdf"), names)
    }

    @Test
    fun uniqueAvoidsExistingFiles() {
        val names = ExportFileNames.unique(listOf("Plan", "Plan"), existing = listOf("plan.pdf", "Plan (2).pdf"))
        assertEquals(listOf("Plan (3).pdf", "Plan (4).pdf"), names)
    }

    @Test
    fun nextMarksNameAsUsed() {
        val used = HashSet<String>()
        assertEquals("a.pdf", ExportFileNames.next("a", used))
        assertEquals("a (2).pdf", ExportFileNames.next("a", used))
        assertTrue("a.pdf" in used)
    }

    @Test
    fun customExtension() {
        assertEquals(listOf("x.txt", "x (2).txt"), ExportFileNames.unique(listOf("x", "x"), extension = ".txt"))
    }
}
