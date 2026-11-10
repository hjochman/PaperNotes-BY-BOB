package local.papernotes.editor

/**
 * Beschreibung eines Stifttyps der Stift-Leiste.
 *
 * @property id stabiler Kurzname (Schlüssel für Ressourcen/Tests, nicht anzeigen)
 * @property frameworkType Wert für `setAhwPenType` bzw. den Track-Typ (`IHWTrack.getPenType`)
 * @property defaultWidth Standardbreite
 * @property minWidth kleinste wählbare Breite
 * @property maxWidth größte wählbare Breite
 * @property isMarker true für Marker (HiNote lässt Marker bei der Texterkennung aus; Alpha-/Textmarker-Verhalten)
 */
data class PenSpec(
    val id: String,
    val frameworkType: Int,
    val defaultWidth: Int,
    val minWidth: Int,
    val maxWidth: Int,
    val isMarker: Boolean = false,
) {
    fun clampWidth(width: Int): Int = width.coerceIn(minWidth, maxWidth)

    /**
     * Farbe, die an das Framework geht. Der Marker ist ein Textmarker: HiNote setzt seine Farbe mit Alpha 0x80
     * (`marker_recent_color_default_*` = `#80rrggbb`), die Transparenz steckt also in der Stiftfarbe. Andere Stifte
     * bleiben undurchsichtig.
     */
    fun colorFor(argb: Int): Int = if (isMarker) (argb and 0x00FFFFFF) or (MARKER_ALPHA shl 24) else argb

    companion object {
        const val MARKER_ALPHA = 0x80
    }
}

/**
 * Die EINZIGE Tabelle der Stifttypen. Reihenfolge = Reihenfolge in der Leiste.
 *
 * Herkunft (aus Beobachtungen abgeleitet, auf dem Gerät zu prüfen):
 * - HiNote 12.4.3: Pinseltypen PEN=1, BALLPOINT=2, PENCIL=3, MARKER=4 in den exportierten Dateien. Zuordnung zum
 *   Framework-Stifttyp: 2→2, 3→3, 4→6, alles andere (also PEN=1)→4.
 * - Framework `HandPenConfig`: 2 = YUANZHUBI (Kugelschreiber), 3 = QIANBI (Bleistift), 4 = GANGBI (Füllfeder/Stahlfeder),
 *   6 = HUABI (Marker).
 * - Unsicher: Die Namen der Framework-Konstanten sind chinesisch (Gangbi = Füllfeder, Yuanzhubi = Kugelschreiber,
 *   Qianbi = Bleistift, Huabi = Pinsel/Marker). Ob jeder Typ wirklich so aussieht wie in HiNote, ist am Gerät zu prüfen.
 * - Breiten: Annahme, an HiNote angelehnt (Standard 4, Marker 6). Hier gewählt: 1–20 für Stifte, 2–48 für Marker.
 *   Die Einheit ist Ansichts-Pixel und die Spanne ist am Gerät zu prüfen.
 */
object PenCatalog {
    val pens: List<PenSpec> = listOf(
        PenSpec(id = "fountain", frameworkType = 4, defaultWidth = 4, minWidth = 1, maxWidth = 20),
        PenSpec(id = "ballpoint", frameworkType = 2, defaultWidth = 4, minWidth = 1, maxWidth = 20),
        PenSpec(id = "pencil", frameworkType = 3, defaultWidth = 4, minWidth = 1, maxWidth = 20),
        PenSpec(id = "marker", frameworkType = 6, defaultWidth = 6, minWidth = 2, maxWidth = 48, isMarker = true),
    )

    /** Standardstift: Kugelschreiber, wie in 3a (Typ 2). */
    val default: PenSpec = pens[1]

    fun byFrameworkType(type: Int): PenSpec? = pens.firstOrNull { it.frameworkType == type }
}
