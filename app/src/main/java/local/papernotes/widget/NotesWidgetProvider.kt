package local.papernotes.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle

/**
 * Widget "Notizen" für den Launcher. Das Lesen der Notizen geschieht nie im UI-Thread: `goAsync()` und ein kurzer
 * Worker-Thread, danach `finish()`. Neu gezeichnet wird bei Hinzufügen/Größenänderung und wenn die App Notizen ändert
 * ([NotesWidgetUpdater.requestUpdate]); die Uhr läuft über `TextClock` im Launcher (minütlich, ohne Sekunden).
 * Akku/Benachrichtigungszähler: sparsamer 15-Minuten-Timer nur bei Bedarf ([StatusScheduler]); Anstecken zeitnah per Lade-Job.
 */
class NotesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAsync(context, goAsync(), appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateAsync(context, goAsync(), intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        StatusScheduler.sync(context)
    }

    override fun onDisabled(context: Context) {
        StatusScheduler.cancel(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = NotesWidgetPrefs(context)
        appWidgetIds.forEach { prefs.delete(it) }
        StatusScheduler.sync(context, appWidgetIds)
    }

    private fun updateAsync(context: Context, pending: BroadcastReceiver.PendingResult, ids: IntArray) {
        val app = context.applicationContext
        Thread {
            try {
                NotesWidgetUpdater.updateNow(app, ids)
            } catch (e: Exception) {
                // Widget bleibt im letzten Zustand; keine Details loggen.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
