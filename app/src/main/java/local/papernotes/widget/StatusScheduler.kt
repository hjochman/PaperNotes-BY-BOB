package local.papernotes.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Sparsamer Timer für Akku- und Benachrichtigungsanzeige: ein inexakter, NICHT aufweckender Wiederholungsalarm alle
 * 15 Minuten ([WidgetStatusLogic.TIMER_INTERVAL_MS]) an [StatusReceiver]. Er läuft nur, solange mindestens ein Widget
 * eine der beiden Optionen nutzt ([sync]); keine Wake-Locks, kein Dienst.
 *
 * Zusätzlich (nur mit Batterie-Option): ein JobScheduler-Job für "Netzteil angesteckt" ([ChargeStatusJobService]), damit
 * der Blitz zeitnah erscheint, obwohl ACTION_POWER_CONNECTED mit targetSdk 29 nicht im Manifest zustellbar ist. Er wird hier
 * ein- und abgeplant; Regeln und der Zustandsablauf (Einplanen erst nach dem Abstecken, erkannt beim nächsten Tick) stehen
 * in [ChargeJobLogic]. Kosten im Leerlauf: keine (der Job ist nur eine Bedingung im System; kein Prozess, kein Alarm).
 */
internal object StatusScheduler {
    private fun intent(context: Context) =
        Intent(context, StatusReceiver::class.java).setAction(StatusReceiver.ACTION_TICK)

    private fun existing(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    /** Plant oder bricht den Timer ab, je nachdem ob noch ein Widget (außer [exclude]) die Optionen nutzt. */
    fun sync(context: Context, exclude: IntArray = IntArray(0)) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java)).filter { it !in exclude }
        val prefs = NotesWidgetPrefs(context)
        val configs = ids.map { prefs.load(it) }
        if (WidgetStatusLogic.timerNeeded(configs)) schedule(context) else cancel(context)
        syncChargeJob(context, configs)
    }

    private fun syncChargeJob(context: Context, configs: List<WidgetConfig>) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        val pending = jobs.getPendingJob(ChargeStatusJobService.JOB_CHARGE) != null
        val charging = if (configs.any { it.showBattery }) NotesWidgetUpdater.readBattery(context)?.charging else null
        when (ChargeJobLogic.decide(configs, charging, pending)) {
            ChargeJobAction.CANCEL -> if (pending) jobs.cancel(ChargeStatusJobService.JOB_CHARGE)
            ChargeJobAction.KEEP -> Unit
            ChargeJobAction.SCHEDULE -> jobs.schedule(
                JobInfo.Builder(ChargeStatusJobService.JOB_CHARGE, ComponentName(context, ChargeStatusJobService::class.java))
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setPersisted(false)
                    .build(),
            )
        }
    }

    private fun schedule(context: Context) {
        if (existing(context) != null) return // läuft schon: Phase nicht verschieben
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val interval = WidgetStatusLogic.TIMER_INTERVAL_MS
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + interval, interval, pending)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(ChargeStatusJobService.JOB_CHARGE)
        val pending = existing(context) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
    }
}
