package local.papernotes.trash

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashEntryTextTest {
    @Test
    fun rootFolderUsesLabel() {
        assertEquals("Alle Notizen", TrashEntryText.folderName("", "Alle Notizen"))
        assertEquals("Schule/Mathe", TrashEntryText.folderName("Schule/Mathe", "Alle Notizen"))
    }

    @Test
    fun detailsJoinsPartsWithSeparator() {
        assertEquals("Schule · 6. Oktober · 3 Seiten", TrashEntryText.details("Schule", "6. Oktober", "3 Seiten"))
    }

    @Test
    fun detailsSkipsBlankParts() {
        assertEquals("Schule · 3 Seiten", TrashEntryText.details("Schule", "", "3 Seiten"))
        assertEquals("", TrashEntryText.details("", " ", ""))
    }
}
