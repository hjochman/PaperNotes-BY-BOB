package local.papernotes.model

/**
 * Eine Textbox auf einer Seite (Phase 4).
 * Alle Koordinaten (x, y, width, height) und die Schriftgröße (fontSize) liegen in Seitenkoordinaten.
 *
 * [text] ist der reine Text. Die Auszeichnung (A3) liegt getrennt daneben: [spans] (Fett, Kursiv, Unterstrichen,
 * Farbe, relative Größe) und [paragraphs] (Ausrichtung, Liste, Einzug; nur abweichende Absätze). Beides in
 * kanonischer Form, siehe [RichTextOps].
 */
data class Textbox(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val bgColor: Int = 0,
    val spans: List<TextSpan> = emptyList(),
    val paragraphs: List<ParaFormat> = emptyList(),
) {
    /** `true`, wenn die Textbox keine Auszeichnung hat (dann genügt [text]). */
    val isPlain: Boolean get() = spans.isEmpty() && paragraphs.isEmpty()
}
