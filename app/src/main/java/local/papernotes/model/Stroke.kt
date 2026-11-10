package local.papernotes.model

/** Ein Strich (Track): Stifttyp, Breite, Farbe (ARGB) und Punkte. */
data class Stroke(
    val penType: Int,
    val width: Float,
    val color: Int,
    val points: List<Point>,
)
