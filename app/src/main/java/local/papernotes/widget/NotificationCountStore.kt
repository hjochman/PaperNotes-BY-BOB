package local.papernotes.widget

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/**
 * Hält nur die ZAHL der sichtbaren Benachrichtigungen (im Speicher und als Int in den SharedPreferences `widget_status`),
 * sonst nichts. Geschrieben wird sie von [NotificationCountService]; gelesen vom Widget.
 */
internal object NotificationCountStore {
    private const val PREFS = "widget_status"
    private const val KEY = "count"

    @Volatile
    private var cached: Int? = null

    /** Letzte bekannte Zahl; null = noch nie gezählt. */
    fun get(context: Context): Int? {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY)) prefs.getInt(KEY, 0).also { cached = it } else null
    }

    /** Speichert [count]; Rückgabe true, wenn sich der Wert geändert hat. */
    fun set(context: Context, count: Int): Boolean {
        if (get(context) == count) return false
        cached = count
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY, count) }
        return true
    }

    /** Hat der Nutzer dieser App den Benachrichtigungszugriff gewährt? (Einstellungen → Benachrichtigungszugriff) */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
