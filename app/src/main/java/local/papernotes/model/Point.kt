package local.papernotes.model

/**
 * Ein Stützpunkt eines Strichs (belegt in Spike 2: x, y, Zeit, Druck).
 * Reine Kotlin-Klasse ohne Android-Bezug, damit sie in JVM-Tests läuft.
 */
data class Point(
    val x: Float,
    val y: Float,
    val time: Long,
    val pressure: Float,
)
