package local.papernotes.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import local.papernotes.widget.NotesWidgetUpdater
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Einstiegspunkt der App für den Nextcloud-Sync. Stromsparend: kein Dienst im Vordergrund, kein Polling, kein
 * Dateibeobachter. Bei einer Änderung ([onNotesChanged]) wird nur lokal gerechnet (Dateien vergleichen, kein Netz) und,
 * falls etwas aussteht, EIN [JobScheduler]-Job eingestellt: er verlangt Netz (bei "nur WLAN" ein ungemessenes), wartet
 * [DEBOUNCE_MS] zum Bündeln und überlebt Neustart ([JobInfo.Builder.setPersisted]). Kommt das Netz später zurück, startet
 * Android die App von selbst. Ein Tagesjob (nur mit Netz und nicht schwachem Akku) rechnet erneut nach, falls Dateien
 * außerhalb der App geändert wurden. Ohne ausstehende Änderungen gibt es keinen Sync-Job.
 */
internal object SyncCoordinator {
    const val JOB_SYNC = 4711
    const val JOB_DAILY = 4712
    private const val DEBOUNCE_MS = 2 * 60 * 1000L
    private const val BACKOFF_MS = 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sync-refresh").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private const val REFRESH_DELAY_MS = 3_000L

    @Volatile
    private var appContext: Context? = null

    /** Solange der Editor offen ist, wird nicht hochgeladen (schont Stiftlatenz und Akku); der Job wartet dann. */
    @Volatile
    var editing: Boolean = false

    // ---------------------------------------------------------------- Status

    fun addListener(l: Runnable) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    fun status(context: Context): SyncStatus {
        val app = context.applicationContext
        val s = SyncSettings(app)
        return SyncStatusLogic.compute(s.facts(SyncEngine.running, NetworkCheck.online(app, s.wifiOnly)))
    }

    /** Sagt Startseite und Widget, dass sich der Sync-Status geändert hat (von jedem Thread). */
    fun notifyStatus(context: Context) {
        val app = context.applicationContext
        handler.post { listeners.forEach { it.run() } }
        try {
            NotesWidgetUpdater.requestStatusUpdate(app)
        } catch (e: RuntimeException) {
            // Das Widget bleibt im letzten Zustand.
        }
    }

    // ---------------------------------------------------------------- Auslöser

    /**
     * Die App hat Notizen geändert (oder startet): ausstehende Änderungen neu zählen und bei Bedarf den Job einstellen.
     * Kehrt sofort zurück; die Arbeit läuft im Hintergrund und wird gebündelt.
     */
    fun onNotesChanged(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!SyncSettings(app).let { it.configured && it.enabled }) {
            cancelAll(app)
            return
        }
        // Viele Speichervorgänge kurz nacheinander (Editor) ergeben nur eine Rechnung.
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, REFRESH_DELAY_MS)
    }

    private val refreshTask = Runnable {
        val app = appContext ?: return@Runnable
        executor.execute {
            try {
                SyncEngine.refreshPending(app)
                scheduleIfNeeded(app)
                ensureDaily(app)
            } catch (e: Exception) {
                // Nur lokale Rechnung; beim nächsten Anlass erneut.
            }
            notifyStatus(app)
        }
    }

    /** Einstellungen (Konto, Netzbedingung, Ein/Aus) haben sich geändert: Jobs neu aufbauen. */
    fun onSettingsChanged(context: Context) {
        val app = context.applicationContext
        cancelAll(app)
        notifyStatus(app)
        onNotesChanged(app)
    }

    /** Läuft ein manueller Sync ("Jetzt synchronisieren"); [onDone] kommt im UI-Thread. Ignoriert "nur WLAN", weil der Nutzer es ausdrücklich will. */
    fun syncNow(context: Context, confirmed: Boolean = false, force: Boolean = false, onDone: (SyncResult) -> Unit) {
        val app = context.applicationContext
        Thread {
            val result = if (!NetworkCheck.online(app, wifiOnly = false)) {
                SyncResult.Retry(RemoteException.Kind.NETWORK, SyncSettings(app).pending)
            } else {
                SyncEngine.run(app, confirmed, force)
            }
            if (result is SyncResult.Retry && result.kind == RemoteException.Kind.NETWORK) scheduleIfNeeded(app)
            handler.post { onDone(result) }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- Jobs

    /** Stellt den Sync-Job ein, wenn etwas aussteht und kein Nutzereingriff nötig ist; sonst wird er entfernt. */
    fun scheduleIfNeeded(context: Context) {
        val app = context.applicationContext
        val s = SyncSettings(app)
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val wanted = s.configured && s.enabled && s.pending > 0 && !s.needsConfirmation && !SyncStatusLogic.blocksAutoSync(s.lastError)
        if (!wanted) {
            scheduler.cancel(JOB_SYNC)
            return
        }
        if (scheduler.getPendingJob(JOB_SYNC) != null) return // läuft schon in der Warteschlange: nicht verschieben
        val job = JobInfo.Builder(JOB_SYNC, ComponentName(app, SyncJobService::class.java))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(DEBOUNCE_MS)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** Tagesjob: rechnet nur lokal nach (falls Dateien außerhalb der App geändert wurden). Nur mit Netz und nicht schwachem Akku. */
    fun ensureDaily(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_DAILY) != null) return
        val job = JobInfo.Builder(JOB_DAILY, ComponentName(app, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPeriodic(DAY_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancelAll(context: Context) {
        val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_SYNC)
        scheduler.cancel(JOB_DAILY)
    }
}
