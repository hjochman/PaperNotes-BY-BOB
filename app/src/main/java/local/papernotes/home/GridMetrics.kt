package local.papernotes.home

/**
 * Maße der Startseite in Pixeln, berechnet aus Breite des Inhaltsbereichs, Bildschirmdichte und Ausrichtung.
 * Reine Rechnung (JVM-getestet). Die Vorschau zeigt oben den Teil der Seite im Seitenverhältnis [PREVIEW_ASPECT]
 * (Höhe/Breite); der untere Rand der Seite wird abgeschnitten, damit pro Bildschirmseite drei Zeilen passen.
 */
class GridMetrics private constructor(
    val columns: Int,
    val sidePadding: Int,
    val gap: Int,
    val tileWidth: Int,
    val previewHeight: Int,
    val textHeight: Int,
    val noteRowHeight: Int,
    val folderWidth: Int,
    val folderBoxHeight: Int,
    val folderTabHeight: Int,
    val folderInnerPadding: Int,
    val miniGap: Int,
    val miniWidth: Int,
    val miniHeight: Int,
    val miniTitleHeight: Int,
    val folderRowHeight: Int,
    val listRowHeight: Int,
    val listPreviewWidth: Int,
    val listPreviewHeight: Int,
    val topPadding: Int,
) {
    /** Zeilenhöhe: Zeilen mit Ordnerkachel sind höher als reine Notizzeilen. */
    fun rowHeight(containsFolder: Boolean): Int =
        if (columns == 1) listRowHeight else if (containsFolder) folderRowHeight else noteRowHeight

    /** Breite einer Kachel, die [span] Spalten belegt (0 in der Listenansicht = volle Breite). */
    fun spanWidth(span: Int): Int = if (columns == 1) 0 else tileWidth * span + gap * (span - 1)

    companion object {
        const val PREVIEW_ASPECT = 1.2f
        const val FOLDER_SPAN = 2
        const val PORTRAIT_COLUMNS = 4
        const val LANDSCAPE_COLUMNS = 6

        fun compute(contentWidth: Int, density: Float, listMode: Boolean, landscape: Boolean): GridMetrics {
            fun dp(v: Float) = Math.round(v * density)
            val side = dp(40f)
            val gap = if (landscape) dp(24f) else dp(38f)
            val columns = if (listMode) 1 else if (landscape) LANDSCAPE_COLUMNS else PORTRAIT_COLUMNS
            val usable = (contentWidth - 2 * side).coerceAtLeast(1)
            val tw = if (columns == 1) usable else ((usable - (columns - 1) * gap) / columns).coerceAtLeast(1)
            val ph = Math.round(tw * PREVIEW_ASPECT)
            val text = dp(64f)
            val vgap = dp(12f)
            val fw = tw * FOLDER_SPAN + gap
            val inner = dp(12f)
            val miniGap = dp(8f)
            val mw = ((fw - 2 * inner - 3 * miniGap) / 4).coerceAtLeast(1)
            val mh = Math.round(mw * PREVIEW_ASPECT)
            val miniTitle = dp(22f)
            val tab = dp(10f)
            val fh = tab + inner + mh + dp(4f) + miniTitle + inner
            val previewW = dp(48f)
            return GridMetrics(
                columns = columns,
                sidePadding = side,
                gap = gap,
                tileWidth = tw,
                previewHeight = ph,
                textHeight = text,
                noteRowHeight = ph + text + vgap,
                folderWidth = fw,
                folderBoxHeight = fh,
                folderTabHeight = tab,
                folderInnerPadding = inner,
                miniGap = miniGap,
                miniWidth = mw,
                miniHeight = mh,
                miniTitleHeight = miniTitle,
                folderRowHeight = fh + text + vgap,
                listRowHeight = dp(84f),
                listPreviewWidth = previewW,
                listPreviewHeight = Math.round(previewW * PREVIEW_ASPECT),
                topPadding = dp(8f),
            )
        }
    }
}
