package local.papernotes.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Zählt die sichtbaren Benachrichtigungen für das Widget (Glocke in der Kopfzeile). Das System bindet den Dienst nur,
 * wenn der Nutzer unter Einstellungen → Benachrichtigungszugriff zugestimmt hat.
 *
 * DATENSCHUTZ: Es wird ausschließlich die ANZAHL ermittelt ([NotificationFilter] sieht nur das Flag-Feld). Titel, Texte,
 * Extras und Paketnamen werden nie gelesen, nichts wird geloggt, gesendet oder gespeichert außer der Zahl ([NotificationCountStore]).
 *
 * Solange der Dienst gebunden ist, lebt der Prozess; dann wird zusätzlich dynamisch auf Netzteil an/ab gehört (Manifest-
 * Receiver bekommen diese Broadcasts ab API 26 nicht), um die Akku-Anzeige sofort zu aktualisieren.
 */
class NotificationCountService : NotificationListenerService() {
    private var powerReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        registerPower()
        refresh()
        // Auch bei gleicher Zahl: ohne Zugriff zeigte das Widget "–"; der Tracker zeichnet nur bei geänderter Anzeige neu.
        NotesWidgetUpdater.requestStatusUpdate(this, force = true)
    }

    override fun onListenerDisconnected() {
        unregisterPower()
        NotesWidgetUpdater.requestStatusUpdate(this, force = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onDestroy() {
        unregisterPower()
        super.onDestroy()
    }

    private fun refresh() {
        val count = try {
            activeNotifications?.count { NotificationFilter.counts(it.notification.flags) }
        } catch (e: Exception) {
            null
        } ?: return
        if (NotificationCountStore.set(this, count)) NotesWidgetUpdater.requestStatusUpdate(this)
    }

    private fun registerPower() {
        if (powerReceiver != null) return
        val app = applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                NotesWidgetUpdater.requestStatusUpdate(app, force = true)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(receiver, filter)
        powerReceiver = receiver
    }

    private fun unregisterPower() {
        powerReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // war schon abgemeldet
            }
        }
        powerReceiver = null
    }
}
