package local.papernotes.widget

/**
 * Drosselung der Widget-Aktualisierung (E-Ink schonen): höchstens ein Lauf je [intervalMs]. Ein zu früher Wunsch wird
 * nicht verworfen, sondern einmal für das Ende des Intervalls vorgemerkt (Rückgabe > 0); weitere Wünsche bis dahin
 * entfallen (Rückgabe -1). [force] (z. B. nach dem Anlegen einer Notiz) läuft sofort. Ohne Android-Bezug; die Zeit wird übergeben.
 */
class UpdateThrottle(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastRun = NEVER
    private var pending = false

    /** 0 = jetzt ausführen, > 0 = so viele ms warten und dann [scheduledRunStarted] aufrufen, -1 = schon vorgemerkt. */
    @Synchronized
    fun request(now: Long, force: Boolean = false): Long {
        if (force) {
            lastRun = now
            return 0
        }
        if (pending) return -1
        val wait = if (lastRun == NEVER) 0 else (lastRun + intervalMs - now).coerceIn(0, intervalMs)
        if (wait == 0L) {
            lastRun = now
        } else {
            pending = true
        }
        return wait
    }

    /** Meldet, dass der vorgemerkte Lauf jetzt beginnt. */
    @Synchronized
    fun scheduledRunStarted(now: Long) {
        lastRun = now
        pending = false
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
