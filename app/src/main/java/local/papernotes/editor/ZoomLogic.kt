package local.papernotes.editor

import kotlin.math.roundToInt

/**
 * Reine Logik der Zoom-Anzeige. 100 % ist die Ansicht "Seite füllt die Breite" ([PageTransform.fitWidth]).
 * Die Anzeige wird auf [STEP] Prozent gerundet, damit das E-Ink-Display bei einer Geste nicht bei jedem Pixel neu zeichnet.
 */
object ZoomLogic {
    const val STEP = 5
    const val MAX_PERCENT = 500

    /** Zoomfaktor in Prozent (auf [STEP] gerundet, 100..[MAX_PERCENT]) für den Maßstab [scale] bei Grundmaßstab [baseScale]. */
    fun percent(scale: Float, baseScale: Float): Int {
        if (baseScale <= 0f || scale.isNaN()) return 100
        val raw = scale / baseScale * 100f
        val stepped = (raw / STEP).roundToInt() * STEP
        return stepped.coerceIn(100, MAX_PERCENT)
    }

    /** Die Anzeige ist nur sichtbar, wenn der Zoom von 100 % abweicht. */
    fun isVisible(percent: Int): Boolean = percent != 100
}
