package local.papernotes.sync

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.R

/**
 * Der Dialog hinter dem Sync-Symbol der Startseite (und des Widgets): Status, Zahl und Art der ausstehenden Änderungen,
 * letzter Abgleich, "Jetzt synchronisieren" und "Einstellungen". Bei "Aus" und "Anmeldung ungültig" geht es direkt in die
 * Einstellungen. Die Rechnung läuft im Hintergrund; der Dialog erscheint danach.
 */
internal object SyncDialog {
    /** Intent-Extra: die Startseite öffnet beim Start diesen Dialog (Tipp auf das Sync-Symbol im Widget). */
    const val EXTRA_OPEN = "open_sync"

    fun open(activity: Activity) {
        val app = activity.applicationContext
        val status = SyncCoordinator.status(app)
        if (SyncStatusLogic.opensSettings(status)) {
            openSettings(activity)
            return
        }
        Thread {
            val plan = SyncEngine.preview(app)
            val last = SyncSettings(app).lastSuccess
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) show(activity, status, plan, last) }
        }.apply { isDaemon = true }.start()
    }

    private fun show(activity: Activity, status: SyncStatus, plan: SyncPlan?, lastSuccess: Long) {
        val text = buildString {
            append(SyncTexts.status(activity, status))
            append("\n\n").append(activity.getString(R.string.sync_pending_count, status.pending))
            if (plan != null) SyncTexts.details(activity, plan).forEach { append("\n").append(it) }
            append("\n\n").append(SyncTexts.lastSuccess(activity, lastSuccess))
        }
        val confirm = status.ui == SyncUi.CONFIRM
        val items = listOf(
            activity.getString(if (confirm) R.string.sync_confirm_action else R.string.sync_now),
            activity.getString(R.string.sync_open_settings),
        )
        EinkDialog.actions(activity, activity.getString(R.string.sync_title), text, items) { index ->
            when {
                index == 1 -> openSettings(activity)
                confirm -> askConfirm(activity, plan?.fileDeletes ?: 0)
                else -> syncNow(activity, confirmed = false)
            }
        }
    }

    private fun askConfirm(activity: Activity, deletes: Int) {
        EinkDialog.confirm(
            activity,
            activity.getString(R.string.sync_confirm_title),
            activity.getString(R.string.sync_confirm_message, deletes),
            activity.getString(R.string.sync_confirm_action),
        ) { syncNow(activity, confirmed = true) }
    }

    private fun syncNow(activity: Activity, confirmed: Boolean) {
        val app = activity.applicationContext
        SyncCoordinator.syncNow(app, confirmed = confirmed) { result ->
            SyncTexts.resultMessage(result)?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openSettings(activity: Activity) {
        activity.startActivity(Intent(activity, SyncSettingsActivity::class.java))
    }
}
