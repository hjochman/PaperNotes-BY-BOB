package local.papernotes.sync

import android.content.Context
import local.papernotes.R
import java.text.DateFormat
import java.util.Date

/** Texte zum Sync-Status (Startseite, Dialog, Einstellungen). Reine Zuordnung, kein Zugriff auf das Netz. */
internal object SyncTexts {
    fun status(context: Context, s: SyncStatus): String = when (s.ui) {
        SyncUi.OFF -> context.getString(R.string.sync_status_off)
        SyncUi.RUNNING -> context.getString(R.string.sync_status_running)
        SyncUi.AUTH -> context.getString(R.string.sync_status_auth)
        SyncUi.ERROR -> context.getString(
            when (SyncSettings(context).lastError) {
                RemoteException.Kind.QUOTA.name -> R.string.sync_status_error_quota
                RemoteException.Kind.PROTOCOL.name -> R.string.sync_status_error_protocol
                else -> R.string.sync_status_error_storage
            },
        )
        SyncUi.CONFIRM -> context.getString(R.string.sync_status_confirm)
        SyncUi.OFFLINE -> context.getString(R.string.sync_status_offline)
        SyncUi.PENDING -> context.getString(R.string.sync_status_pending)
        SyncUi.SYNCED -> context.getString(R.string.sync_status_synced)
    }

    fun lastSuccess(context: Context, millis: Long): String {
        val text = if (millis <= 0L) {
            context.getString(R.string.sync_never)
        } else {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
        }
        return context.getString(R.string.sync_last_success, text)
    }

    /** Aufschlüsselung der ausstehenden Änderungen, eine Zeile je Art (nur Arten mit Anzahl > 0). */
    fun details(context: Context, plan: SyncPlan): List<String> = buildList {
        if (plan.uploads > 0) add(context.getString(R.string.sync_detail_new, plan.uploads))
        if (plan.moves > 0) add(context.getString(R.string.sync_detail_moved, plan.moves))
        if (plan.newFolders > 0) add(context.getString(R.string.sync_detail_folders, plan.newFolders))
        if (plan.deletes > 0) add(context.getString(R.string.sync_detail_deleted, plan.deletes))
    }

    /** Kurzmeldung nach "Jetzt synchronisieren"; null, wenn der Status die Lage schon zeigt. */
    fun resultMessage(result: SyncResult): Int? = when (result) {
        SyncResult.NothingToDo -> R.string.sync_done_ok
        is SyncResult.Done -> if (result.again) R.string.sync_done_retry else R.string.sync_done_ok
        is SyncResult.Retry -> if (result.kind == RemoteException.Kind.NETWORK) R.string.sync_done_offline else R.string.sync_done_retry
        else -> null
    }
}

/** Die Zusammenfassung für die Zeile "Nextcloud" in den Einstellungen. */
object SyncSummary {
    fun text(context: Context): String {
        val status = SyncCoordinator.status(context)
        return if (status.ui == SyncUi.OFF && !SyncSettings(context).configured) {
            context.getString(R.string.sync_off_summary)
        } else {
            SyncTexts.status(context, status)
        }
    }
}
