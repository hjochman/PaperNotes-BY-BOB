package local.papernotes.widget

import local.papernotes.storage.FolderPath

/** Quelle der Notizen im Widget. */
enum class WidgetSource {
    /** Zuletzt geänderte Notizen aus allen Ordnern. */
    RECENT,

    /** Nur Favoriten, zuletzt geänderte zuerst. */
    FAVORITES,

    /** Nur fixierte Notizen, zuletzt geänderte zuerst. */
    PINNED,

    /** Notizen eines Ordners (samt Unterordnern; "" = alle) in der Reihenfolge der Startseite. */
    FOLDER,
    ;

    companion object {
        fun parse(name: String?): WidgetSource = values().firstOrNull { it.name == name } ?: RECENT
    }
}

/**
 * Einstellungen einer Widget-Instanz. Reine Datenklasse ohne Android-Bezug; [toEntries] und [fromEntries] bilden sie
 * auf Text-Schlüssel/Werte ab (die Android-Seite stellt dem Schlüssel die appWidgetId voran). Ungültige oder fehlende
 * Werte werden beim Lesen durch die Standardwerte ersetzt.
 */
data class WidgetConfig(
    val showClock: Boolean = true,
    val showDate: Boolean = false,
    val source: WidgetSource = WidgetSource.RECENT,
    val folder: String = "",
    val count: Int = DEFAULT_COUNT,
    val showNewButton: Boolean = true,
    val showTitle: Boolean = true,
    val showBattery: Boolean = false,
    val showNotifications: Boolean = false,
    val showSync: Boolean = true,
    /** Favoriten stehen oben, auch bei den Quellen Zuletzt geändert/Fixierte/Ordner; bei der Quelle Favoriten ohne Wirkung. */
    val favoritesFirst: Boolean = false,
) {
    /** Braucht das Widget Akku- oder Benachrichtigungswerte (dann läuft der sparsame Aktualisierungs-Timer)? */
    val usesStatus: Boolean get() = showBattery || showNotifications

    fun toEntries(): Map<String, String> = mapOf(
        KEY_CLOCK to showClock.toString(),
        KEY_DATE to showDate.toString(),
        KEY_SOURCE to source.name,
        KEY_FOLDER to folder,
        KEY_COUNT to count.toString(),
        KEY_NEW to showNewButton.toString(),
        KEY_TITLE to showTitle.toString(),
        KEY_BATTERY to showBattery.toString(),
        KEY_NOTIFICATIONS to showNotifications.toString(),
        KEY_SYNC to showSync.toString(),
        KEY_FAVORITES_FIRST to favoritesFirst.toString(),
    )

    companion object {
        const val DEFAULT_COUNT = 5
        val COUNT_OPTIONS: List<Int> = listOf(3, 4, 5, 6, 8)
        const val MAX_ROWS = 8

        const val KEY_CLOCK = "clock"
        const val KEY_DATE = "date"
        const val KEY_SOURCE = "source"
        const val KEY_FOLDER = "folder"
        const val KEY_COUNT = "count"
        const val KEY_NEW = "new"
        const val KEY_TITLE = "title"
        const val KEY_BATTERY = "battery"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_SYNC = "sync"
        const val KEY_FAVORITES_FIRST = "favorites_first"

        val ALL_KEYS: List<String> = listOf(KEY_CLOCK, KEY_DATE, KEY_SOURCE, KEY_FOLDER, KEY_COUNT, KEY_NEW, KEY_TITLE, KEY_BATTERY, KEY_NOTIFICATIONS, KEY_SYNC, KEY_FAVORITES_FIRST)

        fun fromEntries(get: (String) -> String?): WidgetConfig {
            val d = WidgetConfig()
            val count = get(KEY_COUNT)?.toIntOrNull()?.takeIf { it in COUNT_OPTIONS } ?: d.count
            val folder = get(KEY_FOLDER)?.takeIf { FolderPath.isValid(it) } ?: d.folder
            return WidgetConfig(
                showClock = bool(get(KEY_CLOCK), d.showClock),
                showDate = bool(get(KEY_DATE), d.showDate),
                source = WidgetSource.parse(get(KEY_SOURCE)),
                folder = folder,
                count = count,
                showNewButton = bool(get(KEY_NEW), d.showNewButton),
                showTitle = bool(get(KEY_TITLE), d.showTitle),
                showBattery = bool(get(KEY_BATTERY), d.showBattery),
                showNotifications = bool(get(KEY_NOTIFICATIONS), d.showNotifications),
                showSync = bool(get(KEY_SYNC), d.showSync),
                favoritesFirst = bool(get(KEY_FAVORITES_FIRST), d.favoritesFirst),
            )
        }

        private fun bool(value: String?, default: Boolean): Boolean = when (value) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }
}
