package local.papernotes.sync

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Vom System gestartete Sync-Läufe. [SyncCoordinator.JOB_SYNC] lädt hoch, [SyncCoordinator.JOB_DAILY] rechnet nur lokal nach.
 * Die Arbeit läuft in einem eigenen Thread; verliert das System die Bedingungen (kein Netz mehr), ruft es [onStopJob] auf,
 * der Lauf bricht zwischen zwei Operationen ab und das Gedächtnis bleibt erhalten. Es wird nichts geloggt.
 */
class SyncJobService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            var again = false
            try {
                again = if (params.jobId == SyncCoordinator.JOB_DAILY) daily() else upload()
            } catch (e: Exception) {
                again = params.jobId == SyncCoordinator.JOB_SYNC
            } finally {
                jobFinished(params, again)
            }
        }.apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return params.jobId == SyncCoordinator.JOB_SYNC // wiederholen, sobald die Bedingungen wieder passen
    }

    private fun daily(): Boolean {
        val app = applicationContext
        SyncEngine.refreshPending(app)
        SyncCoordinator.scheduleIfNeeded(app)
        SyncCoordinator.notifyStatus(app)
        return false
    }

    /** Wahr, wenn der Job mit Backoff wiederholt werden soll. */
    private fun upload(): Boolean {
        val app = applicationContext
        if (SyncCoordinator.editing) return true // Editor offen: später
        return when (val r = SyncEngine.run(app, shouldStop = { stopped || SyncCoordinator.editing })) {
            is SyncResult.Retry -> true
            is SyncResult.Done -> r.again
            SyncResult.NothingToDo, SyncResult.NotConfigured, SyncResult.AuthFailed, SyncResult.NoStorage,
            is SyncResult.NeedsConfirmation, is SyncResult.Failed,
            -> false
        }
    }
}
