package local.papernotes.sync

import android.content.Context
import local.papernotes.NotesLocation
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Führt Sync-Läufe auf Android aus (blockierend, nur in Hintergrund-Threads aufrufen) und schreibt das Ergebnis in
 * [SyncSettings]. Es läuft nie mehr als ein Lauf gleichzeitig. Der HTTP-Client lebt nur für einen Lauf; danach werden
 * die Verbindungen geschlossen, damit nichts im Hintergrund wach bleibt. Es wird nichts geloggt.
 */
internal object SyncEngine {
    private val lock = ReentrantLock()

    @Volatile
    var running: Boolean = false
        private set

    fun stateFile(context: Context) = SyncStateFile(File(context.applicationContext.filesDir, "sync_state.json"))

    /**
     * Ein Lauf. [confirmed]: Löschungen über der Schutzgrenze sind vom Nutzer bestätigt. [force]: Gedächtnis verwerfen und neu
     * mit dem Server abgleichen. [shouldStop] wird zwischen den Operationen geprüft (Abbruch durch das System).
     */
    fun run(context: Context, confirmed: Boolean = false, force: Boolean = false, shouldStop: () -> Boolean = { false }): SyncResult {
        val app = context.applicationContext
        if (!lock.tryLock()) return SyncResult.Done(0, true) // läuft schon; der laufende Lauf meldet Änderungen danach selbst
        try {
            running = true
            SyncCoordinator.notifyStatus(app)
            val result = execute(app, confirmed, force, shouldStop)
            apply(app, result)
            return result
        } finally {
            running = false
            lock.unlock()
            SyncCoordinator.notifyStatus(app)
        }
    }

    private fun execute(app: Context, confirmed: Boolean, force: Boolean, shouldStop: () -> Boolean): SyncResult {
        val s = SyncSettings(app)
        if (!s.configured || !s.enabled) return SyncResult.NotConfigured
        val base = SyncPaths.normalizeBaseUrl(s.baseUrl) ?: return SyncResult.Failed(RemoteException.Kind.PROTOCOL)
        val target = SyncPaths.normalizeTarget(s.target) ?: return SyncResult.Failed(RemoteException.Kind.PROTOCOL)
        val secret = CredentialStore(app).load() ?: return SyncResult.AuthFailed
        if (!NotesLocation.hasAccess(app)) return SyncResult.NoStorage
        val client = WebDavRemote.newClient()
        try {
            val remote = WebDavRemote(client, SyncPaths.davRoot(base, s.user), target, WebDavRemote.basicAuth(s.user, secret))
            return SyncRunner(NotesLocation.root(app), remote, stateFile(app), s.scope, shouldStop).run(confirmed, force)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun apply(app: Context, result: SyncResult) {
        val s = SyncSettings(app)
        when (result) {
            SyncResult.NotConfigured -> Unit
            SyncResult.NothingToDo -> ok(s, true)
            is SyncResult.Done -> ok(s, !result.again)
            is SyncResult.NeedsConfirmation -> s.lastError = null
            SyncResult.NoStorage -> s.lastError = SyncStatusLogic.ERROR_STORAGE
            is SyncResult.Retry -> s.lastError = result.kind.name
            SyncResult.AuthFailed -> s.lastError = RemoteException.Kind.AUTH.name
            is SyncResult.Failed -> s.lastError = result.kind.name
        }
        refreshPending(app)
    }

    private fun ok(s: SyncSettings, complete: Boolean) {
        s.lastError = null
        if (complete) s.lastSuccess = System.currentTimeMillis()
    }

    /**
     * Rechnet die ausstehenden Änderungen aus (nur lokal: Scan und Vergleich mit dem Gedächtnis, kein Netz) und legt die
     * Zahl und die Bestätigungspflicht in [SyncSettings] ab. Liefert die Zahl.
     */
    fun refreshPending(context: Context): Int {
        val app = context.applicationContext
        val s = SyncSettings(app)
        val plan = plan(app, s) ?: return s.pending
        s.pending = plan.first.count
        s.needsConfirmation = plan.second
        return plan.first.count
    }

    /** Der Plan für Anzeige und Dialog; null, wenn nicht eingerichtet oder der Speicher nicht lesbar ist. */
    fun preview(context: Context): SyncPlan? = plan(context.applicationContext, SyncSettings(context))?.first

    private fun plan(app: Context, s: SyncSettings): Pair<SyncPlan, Boolean>? {
        if (!s.configured) return null
        if (!NotesLocation.hasAccess(app)) return null
        val local = LocalScanner.scan(NotesLocation.root(app)) ?: return null
        val state = stateFile(app).load()?.takeIf { it.scope == s.scope } ?: SyncState(s.scope)
        val plan = SyncPlanner.plan(local, state)
        return plan to SyncPolicy.needsConfirmation(plan, state, local)
    }
}
