package local.papernotes.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Empfängt den 15-Minuten-Timer ([StatusScheduler]). Aktualisiert nur die Statusgruppe der Widgets, und nur wenn sich der
 * angezeigte Text geändert hat. Nicht exportiert. Netzteil an/ab (`ACTION_POWER_CONNECTED/DISCONNECTED`) ist mit
 * targetSdk 29 NICHT von den Implicit-Broadcast-Beschränkungen ausgenommen (Android-Doku "Broadcast exceptions") und
 * wird deshalb nicht im Manifest registriert; bei aktivem Benachrichtigungszugriff lauscht [NotificationCountService] dynamisch.
 * Es wird nichts geloggt.
 */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                NotesWidgetUpdater.updateStatusNow(app)
            } catch (e: Exception) {
                // Widget bleibt im letzten Zustand.
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TICK = "local.papernotes.widget.STATUS_TICK"
    }
}
