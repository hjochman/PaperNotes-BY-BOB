package local.papernotes.editor

/** Reine Rechenhilfen der Audioleiste (kein Android-Bezug, JVM-testbar). */
object AudioLogic {
    /** Sprungweite von Vor-/Zurückspulen. */
    const val SKIP_MS = 10_000

    /** Formatiert Millisekunden als `m:ss` bzw. `h:mm:ss`; negative Werte zählen als 0. */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Neue Abspielposition nach einem Sprung um [deltaMs], begrenzt auf `0..duration`. */
    fun seekTarget(position: Int, deltaMs: Int, duration: Int): Int =
        (position.toLong() + deltaMs).coerceIn(0L, duration.coerceAtLeast(0).toLong()).toInt()

    /** Nächste Tonspur (reihum); bei leerer Liste 0. */
    fun nextIndex(current: Int, count: Int): Int = if (count <= 0) 0 else (current + 1) % count

    /** Begrenzt einen Spurindex auf die vorhandenen Spuren; bei leerer Liste 0. */
    fun clampIndex(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)
}
