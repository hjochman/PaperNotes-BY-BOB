package local.papernotes.widget

import android.content.Context
import androidx.core.content.edit

/**
 * Speichert die [WidgetConfig] je Widget-Instanz in den SharedPreferences `widget_config`; Schlüssel = `<appWidgetId>.<name>`.
 * Beim Löschen des Widgets räumt [delete] auf. Es werden nur Einstellungen gespeichert, keine Notizdaten.
 */
internal class NotesWidgetPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(appWidgetId: Int): WidgetConfig = WidgetConfig.fromEntries { prefs.getString(key(appWidgetId, it), null) }

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit { config.toEntries().forEach { (name, value) -> putString(key(appWidgetId, name), value) } }
    }

    fun delete(appWidgetId: Int) {
        prefs.edit {
            WidgetConfig.ALL_KEYS.forEach { remove(key(appWidgetId, it)) }
            remove(key(appWidgetId, KEY_LAST_STATUS))
        }
    }

    /** Zuletzt gezeichneter Zustand der Statusgruppe (nur Text/Ort, siehe [StatusChangeTracker]). */
    val statusStore: StatusStateStore = object : StatusStateStore {
        override fun get(id: Int): String? = prefs.getString(key(id, KEY_LAST_STATUS), null)

        override fun set(id: Int, value: String?) {
            if (get(id) == value) return
            prefs.edit { if (value == null) remove(key(id, KEY_LAST_STATUS)) else putString(key(id, KEY_LAST_STATUS), value) }
        }
    }

    private fun key(appWidgetId: Int, name: String) = "$appWidgetId.$name"

    private companion object {
        const val PREFS_NAME = "widget_config"
        const val KEY_LAST_STATUS = "laststatus"
    }
}
