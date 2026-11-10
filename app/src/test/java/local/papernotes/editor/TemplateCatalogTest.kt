package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCatalogTest {
    @Test
    fun hasTwentyOneUniqueValidIds() {
        val ids = TemplateCatalog.all.map { it.id }
        assertEquals(21, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        val pattern = Regex("[a-z0-9_]{1,32}")
        assertTrue(ids.all { pattern.matches(it) })
    }

    @Test
    fun groupSizesAreAsExpected() {
        val expected = mapOf(TemplateGroup.BASIC to 4, TemplateGroup.WRITING to 6, TemplateGroup.PLANS to 5, TemplateGroup.WORK to 6)
        for (g in TemplateCatalog.groups) assertEquals(g.name, expected.getValue(g), TemplateCatalog.inGroup(g).size)
    }

    @Test
    fun unknownIdFallsBackToBlank() {
        assertEquals("blank", TemplateCatalog.lookup("from_the_future").id)
        assertEquals("blank", TemplateCatalog.lookup("").id)
        assertEquals("cornell", TemplateCatalog.lookup("cornell").id)
        assertFalse(TemplateCatalog.isKnown("from_the_future"))
        assertTrue(TemplateCatalog.isKnown(TemplateCatalog.DEFAULT_ID))
    }
}
