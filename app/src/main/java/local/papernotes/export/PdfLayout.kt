package local.papernotes.export

/** Größe einer PDF-Seite in Punkten (1/72 Zoll) und Maßstab Seitenkoordinaten -> Punkte. */
data class PdfPageSize(val widthPt: Int, val heightPt: Int, val scale: Float)

/**
 * Reine Berechnung der PDF-Seitengröße (ohne Android-Bezug, JVM-testbar). Die Breite ist [PAGE_WIDTH_PT] (A4-Breite),
 * die Höhe folgt dem Seitenverhältnis der Notizseite (1404x1872 ergibt 595x793). Extrem hohe Seiten werden auf
 * [MAX_DIMENSION_PT] begrenzt (üblicher PDF-Grenzwert 14400 pt), dabei schrumpft die Breite mit.
 */
object PdfLayout {
    const val PAGE_WIDTH_PT = 595
    const val MAX_DIMENSION_PT = 14_400

    fun pageSize(pageWidth: Int, pageHeight: Int): PdfPageSize {
        require(pageWidth > 0 && pageHeight > 0) { "Seitengröße muss positiv sein" }
        val scale = PAGE_WIDTH_PT.toFloat() / pageWidth
        val height = Math.round(pageHeight * scale)
        if (height <= MAX_DIMENSION_PT) return PdfPageSize(PAGE_WIDTH_PT, height.coerceAtLeast(1), scale)
        val limited = MAX_DIMENSION_PT.toFloat() / pageHeight
        return PdfPageSize(Math.round(pageWidth * limited).coerceAtLeast(1), MAX_DIMENSION_PT, limited)
    }
}
