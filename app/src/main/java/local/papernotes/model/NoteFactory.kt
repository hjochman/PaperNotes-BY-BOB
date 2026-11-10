package local.papernotes.model

import java.util.UUID

object NoteFactory {
    // Annahme: HMW-W09 im Hochformat, Screenshots sind 1404 Pixel breit (Höhe 1872 ist abgeleitet
    // und auf dem Gerät noch zu prüfen). Daher ist die Seitengröße ein Parameter.
    const val DEFAULT_PAGE_WIDTH = 1404
    const val DEFAULT_PAGE_HEIGHT = 1872
    const val TEMPLATE_BLANK = "blank"

    /** Erzeugt eine Notiz mit genau einer leeren Seite (Vorlage "blank"). */
    fun newNote(
        title: String,
        folderPath: String = "",
        pageWidth: Int = DEFAULT_PAGE_WIDTH,
        pageHeight: Int = DEFAULT_PAGE_HEIGHT,
        now: Long = System.currentTimeMillis(),
    ): Note {
        val page = Page(
            id = UUID.randomUUID().toString(),
            template = TEMPLATE_BLANK,
            width = pageWidth,
            height = pageHeight,
            strokes = emptyList(),
            bookmarked = false,
        )
        return Note(
            id = UUID.randomUUID().toString(),
            title = title,
            folderPath = folderPath,
            created = now,
            modified = now,
            favorite = false,
            pinned = false,
            pageIds = listOf(page.id),
            pages = mapOf(page.id to page),
        )
    }
}
