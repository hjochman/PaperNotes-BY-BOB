package local.papernotes.editor

import kotlin.math.hypot

/** Sichtbarer Anteil einer Achse als Bruchteile 0..1 der Seite ([start] bis [end]). */
data class Thumb(val start: Float, val end: Float)

/**
 * Reine Logik der Verschiebe-Anzeige (Balken am Rand, Gummiband-Pfeil). Ohne Android-Bezug (JVM-testbar).
 */
object PanIndicatorLogic {
    /**
     * Sichtbarer Ausschnitt einer Achse: [offset] ist der Versatz der Seite (<= 0), [viewSize] die Größe der Ansicht,
     * [contentSize] die der gezoomten Seite. Passt die Seite in die Ansicht, gibt es nichts zu verschieben (null).
     */
    fun thumb(offset: Float, viewSize: Float, contentSize: Float): Thumb? {
        if (viewSize <= 0f || contentSize <= viewSize + 0.5f) return null
        val start = (-offset / contentSize).coerceIn(0f, 1f)
        val end = (start + viewSize / contentSize).coerceIn(start, 1f)
        return Thumb(start, end)
    }

    /**
     * Der Pfeil wird nur neu gezeichnet, wenn seine Spitze sich seit dem letzten Zeichnen um mindestens [threshold]
     * bewegt hat (E-Ink: wenige Teilaktualisierungen statt eine je Pixel). Ohne bisherige Spitze ([lastX] NaN) immer.
     */
    fun shouldRedrawArrow(lastX: Float, lastY: Float, x: Float, y: Float, threshold: Float): Boolean =
        lastX.isNaN() || lastY.isNaN() || hypot(x - lastX, y - lastY) >= threshold
}
