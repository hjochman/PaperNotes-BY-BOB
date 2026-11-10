package local.papernotes.model

/**
 * Eine Seite. [id] ist eine UUID (wird als Dateiname im .pnote verwendet).
 * [template] z. B. "blank", "lines_wide", "lines_narrow", "dotted", "millimeter", "grid", "todo".
 */
data class Page(
    val id: String,
    val template: String,
    val width: Int,
    val height: Int,
    val strokes: List<Stroke>,
    val bookmarked: Boolean,
    val textboxes: List<Textbox> = emptyList(),
    val images: List<ImageAttachment> = emptyList(),
    val audios: List<AudioAttachment> = emptyList(),
)
