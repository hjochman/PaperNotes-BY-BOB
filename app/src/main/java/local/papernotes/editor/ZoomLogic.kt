package local.papernotes.editor

import kotlin.math.roundToInt

/**
 * Reine Logik von Zoom und Verschieben. 100 % ist die Ansicht "Seite füllt die Breite" ([PageTransform.fitWidth]).
 * Gezoomt wird in festen Stufen ([STEPS], Knöpfe in der Kopfzeile); das Schreiben bleibt dabei immer frei.
 */
object ZoomLogic {
    const val STEP = 5
    const val MAX_PERCENT = 500

    /** Zoomstufen der Knöpfe "+" und "-". */
    val STEPS = intArrayOf(100, 150, 200, 300, 400, MAX_PERCENT)

    /** Zoomfaktor in Prozent (auf [STEP] gerundet, 100..[MAX_PERCENT]) für den Maßstab [scale] bei Grundmaßstab [baseScale]. */
    fun percent(scale: Float, baseScale: Float): Int {
        if (baseScale <= 0f || scale.isNaN()) return 100
        val raw = scale / baseScale * 100f
        val stepped = (raw / STEP).roundToInt() * STEP
        return stepped.coerceIn(100, MAX_PERCENT)
    }

    /** Nächsthöhere Stufe über [percent]; bleibt bei der höchsten Stufe unverändert. */
    fun stepUp(percent: Int): Int = STEPS.firstOrNull { it > percent } ?: percent.coerceAtMost(MAX_PERCENT)

    /** Nächstniedrigere Stufe unter [percent]; bleibt bei der niedrigsten Stufe unverändert. */
    fun stepDown(percent: Int): Int = STEPS.lastOrNull { it < percent } ?: percent.coerceAtLeast(100)

    /**
     * Versatz einer Achse: Ist der Inhalt ([contentSize]) nicht größer als die Ansicht ([viewSize]), liegt er mittig,
     * sonst bleibt die Ansicht innerhalb des Inhalts.
     */
    fun clampOffset(offset: Float, viewSize: Float, contentSize: Float): Float =
        if (contentSize <= viewSize) (viewSize - contentSize) / 2f else offset.coerceIn(viewSize - contentSize, 0f)

    /**
     * Abbildung für die Zoomstufe [percent]: 100 % = Seite füllt die Breite (Versatz 0, wie [PageTransform.fitWidth]);
     * darüber bleibt der Seitenpunkt in der Mitte der Ansicht stehen. Größen in Pixeln (Ansicht) bzw. Seitenpunkten.
     */
    fun zoomed(current: PageTransform, viewW: Float, viewH: Float, pageW: Float, pageH: Float, percent: Int): PageTransform {
        val base = viewW / pageW
        if (percent <= 100) return PageTransform(base)
        val scale = base * percent / 100f
        val cx = viewW / 2f
        val cy = viewH / 2f
        val offsetX = cx - current.viewToPageX(cx) * scale
        val offsetY = cy - current.viewToPageY(cy) * scale
        return PageTransform(
            scale,
            clampOffset(offsetX, viewW, pageW * scale),
            clampOffset(offsetY, viewH, pageH * scale),
        )
    }

    /** True, wenn die Seite in [current] in mindestens einer Richtung größer als die Ansicht ist (Verschieben sinnvoll). */
    fun canPan(current: PageTransform, viewW: Float, viewH: Float, pageW: Float, pageH: Float): Boolean =
        pageW * current.scale > viewW + 0.5f || pageH * current.scale > viewH + 0.5f

    /** Verschiebt die Seite um ([dx], [dy]) Ansichtspixel und hält sie in der Ansicht. Der Maßstab bleibt. */
    fun panned(current: PageTransform, dx: Float, dy: Float, viewW: Float, viewH: Float, pageW: Float, pageH: Float): PageTransform {
        val s = current.scale
        return PageTransform(
            s,
            clampOffset(current.offsetX + dx, viewW, pageW * s),
            clampOffset(current.offsetY + dy, viewH, pageH * s),
        )
    }
}
