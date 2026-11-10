package local.papernotes.widget

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Wird vom System gestartet, wenn das Netzteil angesteckt wird ([StatusScheduler] plant den Job mit `setRequiresCharging`,
 * ohne Netz, nicht persistent, nur solange nicht geladen wird; siehe [ChargeJobLogic]). Aktualisiert sofort die Statusgruppe
 * des Widgets (Blitz); der Tracker zeichnet nur bei geänderter Anzeige neu. Plant sich bewusst NICHT selbst neu: das
 * erledigt der nächste 15-Minuten-Tick nach dem Abstecken. Kein Dienst, kein Wake-Lock; es wird nichts geloggt.
 */
class ChargeStatusJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                NotesWidgetUpdater.updateStatusNow(app)
            } catch (e: Exception) {
                // Widget bleibt im letzten Zustand.
            } finally {
                jobFinished(params, false)
            }
        }.apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false // Lauf ist kurz; kein Wiederholen nötig

    companion object {
        /** Eigene Job-Id (Sync nutzt 4711 und 4712). */
        const val JOB_CHARGE = 4713
    }
}
