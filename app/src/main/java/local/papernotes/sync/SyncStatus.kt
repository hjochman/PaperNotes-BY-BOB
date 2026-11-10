package local.papernotes.sync

/** Was das Sync-Symbol zeigt. Reihenfolge der Prüfung steht in [SyncStatusLogic.compute]. */
enum class SyncUi {
    /** Nicht eingerichtet oder abgeschaltet. */
    OFF,

    /** Läuft gerade. */
    RUNNING,

    /** Anmeldung ungültig: der Nutzer muss sich neu anmelden. */
    AUTH,

    /** Dauerhafter Fehler (z. B. Speicher voll, Speicher nicht lesbar). */
    ERROR,

    /** Zu viele Löschungen: wartet auf Bestätigung. */
    CONFIRM,

    /** Änderungen warten, es gibt aber kein passendes Netz. */
    OFFLINE,

    /** Änderungen warten auf den nächsten Lauf (oder gerade gescheitert, wird wiederholt). */
    PENDING,

    /** Alles abgeglichen. */
    SYNCED,
}

data class SyncStatus(val ui: SyncUi, val pending: Int)

/** Eingangsdaten für [SyncStatusLogic.compute]; `error` ist der Name einer [RemoteException.Kind] oder `STORAGE`. */
data class SyncFacts(
    val configured: Boolean,
    val enabled: Boolean,
    val running: Boolean,
    val pending: Int,
    val error: String?,
    val needsConfirmation: Boolean,
    val online: Boolean,
)

object SyncStatusLogic {
    const val MAX_BADGE = 99
    const val ERROR_STORAGE = "STORAGE"

    /** Fehlerarten, die nicht von selbst verschwinden. Netz- und Serverfehler gelten als vorübergehend (wird wiederholt). */
    private val LASTING = setOf(RemoteException.Kind.QUOTA.name, RemoteException.Kind.PROTOCOL.name, ERROR_STORAGE)

    /** Soll nach diesem Fehler NICHT von selbst weiter synchronisiert werden (der Nutzer muss eingreifen)? */
    fun blocksAutoSync(error: String?): Boolean = error == RemoteException.Kind.AUTH.name || error in LASTING

    fun compute(f: SyncFacts): SyncStatus {
        val pending = f.pending.coerceAtLeast(0)
        val ui = when {
            !f.configured || !f.enabled -> SyncUi.OFF
            f.running -> SyncUi.RUNNING
            f.error == RemoteException.Kind.AUTH.name -> SyncUi.AUTH
            f.error in LASTING -> SyncUi.ERROR
            f.needsConfirmation -> SyncUi.CONFIRM
            pending == 0 -> SyncUi.SYNCED
            !f.online -> SyncUi.OFFLINE
            else -> SyncUi.PENDING
        }
        return SyncStatus(ui, pending)
    }

    /**
     * Kurztext neben dem Logo: Zahl der ausstehenden Änderungen, Haken, Auslassung oder Strich. Die drei Zustände, die den
     * Nutzer brauchen, sind unterscheidbar: "?" = Bestätigung nötig, "!" = neu anmelden, "×" = Fehler.
     */
    fun badge(s: SyncStatus): String = when (s.ui) {
        SyncUi.OFF -> "–"
        SyncUi.RUNNING -> "…"
        SyncUi.CONFIRM -> "?"
        SyncUi.AUTH -> "!"
        SyncUi.ERROR -> "×"
        SyncUi.SYNCED -> "✓"
        SyncUi.OFFLINE, SyncUi.PENDING -> if (s.pending > MAX_BADGE) "$MAX_BADGE+" else s.pending.toString()
    }

    /** Das Logo wird bei fehlendem Netz und im ausgeschalteten Zustand abgeblendet. */
    fun dimmed(s: SyncStatus): Boolean = s.ui == SyncUi.OFF || s.ui == SyncUi.OFFLINE

    /** Wahr, wenn ein Tipp auf das Symbol statt des Dialogs direkt die Einstellungen öffnen soll. */
    fun opensSettings(s: SyncStatus): Boolean = s.ui == SyncUi.OFF || s.ui == SyncUi.AUTH
}
