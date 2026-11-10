package local.papernotes.widget

/** Messwert des Akkus: Prozent (0..100) und ob geladen wird (Netzteil/USB angeschlossen). */
data class BatteryReading(val percent: Int, val charging: Boolean)

/** Reine Umrechnung der Werte aus `ACTION_BATTERY_CHANGED` (ohne Android-Bezug; die Konstanten stehen hier). */
object BatteryMath {
    /** `BatteryManager.BATTERY_STATUS_CHARGING`. */
    const val STATUS_CHARGING = 2

    /** Prozent = level * 100 / scale; ungültige Werte (scale <= 0, level < 0) ergeben null. */
    fun reading(level: Int, scale: Int, status: Int, plugged: Int): BatteryReading? {
        if (scale <= 0 || level < 0) return null
        val percent = (level.toLong() * 100 / scale).toInt().coerceIn(0, 100)
        // Blitz bei Ladung und bei angeschlossenem Netzteil (auch "voll"): so zeigt es auch das Original (100% mit Blitz).
        return BatteryReading(percent, plugged != 0 || status == STATUS_CHARGING)
    }
}

/**
 * Filterregel für den Benachrichtigungszähler. Es wird nur das Flag-Feld einer Benachrichtigung betrachtet, nie Titel,
 * Text, Extras oder Paketname.
 *
 * Gezählt wird, was in der Benachrichtigungsliste als eigener Eintrag sichtbar ist: ausgeschlossen sind Gruppen-
 * Zusammenfassungen (`FLAG_GROUP_SUMMARY`, sonst würde die Gruppe doppelt zählen; das tut auch inkOS) und Dauer-
 * benachrichtigungen (`FLAG_ONGOING_EVENT`, z. B. Medienwiedergabe, laufende Dienste; inkOS zählt diese teils mit,
 * für das Widget zählen sie bewusst nicht). Alle Apps (auch PaperNotes) zählen, wie im Original.
 */
object NotificationFilter {
    /** `Notification.FLAG_ONGOING_EVENT`. */
    const val FLAG_ONGOING_EVENT = 0x00000002

    /** `Notification.FLAG_GROUP_SUMMARY`. */
    const val FLAG_GROUP_SUMMARY = 0x00000200

    fun counts(flags: Int): Boolean = flags and (FLAG_ONGOING_EVENT or FLAG_GROUP_SUMMARY) == 0
}

/**
 * Was in der Statusgruppe des Widgets steht; null = Teil ist aus. [syncText] = Kurztext des Nextcloud-Sync (Zahl, Haken ...),
 * [syncDimmed] = Sync-Symbol abgeblendet (kein passendes Netz), wie auf der Startseite.
 */
data class StatusDisplay(
    val batteryText: String?,
    val charging: Boolean,
    val bellText: String?,
    val syncText: String? = null,
    val syncDimmed: Boolean = false,
) {
    val visible: Boolean get() = batteryText != null || bellText != null || syncText != null

    companion object {
        val NONE = StatusDisplay(null, false, null)
    }
}

/**
 * Welche Teile der Kopfzeile bei der verfügbaren Breite sichtbar sind. Der Status (Sync/Akku/Glocke) steht immer in der
 * Kopfzeile und wird nie ausgeblendet; Platz entsteht nur durch Weglassen von [title] (zuerst) und [date] (danach).
 */
data class HeaderLayout(val title: Boolean, val date: Boolean)

object WidgetStatusLogic {
    /** Der Aktualisierungs-Timer läuft nur, solange mindestens ein Widget Akku oder Benachrichtigungen zeigt. */
    fun timerNeeded(configs: List<WidgetConfig>): Boolean = configs.any { it.usesStatus }

    /** Abstand des sparsamen Timers (inexakt, nicht aufweckend). */
    const val TIMER_INTERVAL_MS = 15 * 60 * 1000L

    const val NO_VALUE = "–"
    const val MAX_COUNT = 99

    fun batteryText(percent: Int): String = "${percent.coerceIn(0, 100)}%"

    /** Zähler als Text; ohne Zugriff oder ohne bekannten Wert "–", ab 100 "99+". */
    fun bellText(count: Int?, hasAccess: Boolean): String =
        if (!hasAccess || count == null || count < 0) NO_VALUE else if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()

    /**
     * Anzeige der Statusgruppe. Ist die Option aus, fehlt der Teil. Ist der Akku unlesbar ([battery] = null), erscheint nur "–".
     * [syncBadge] = Kurztext des Sync-Status oder null (Sync aus/nicht eingerichtet); er erscheint nur mit [WidgetConfig.showSync].
     */
    fun display(
        config: WidgetConfig,
        battery: BatteryReading?,
        count: Int?,
        hasAccess: Boolean,
        syncBadge: String? = null,
        syncDimmed: Boolean = false,
    ): StatusDisplay = StatusDisplay(
        batteryText = if (config.showBattery) battery?.let { batteryText(it.percent) } ?: NO_VALUE else null,
        charging = config.showBattery && battery?.charging == true,
        bellText = if (config.showNotifications) bellText(count, hasAccess) else null,
        syncText = if (config.showSync) syncBadge else null,
        syncDimmed = config.showSync && syncBadge != null && syncDimmed,
    )

    // Geschätzte Breiten in dp (Text: Zeichen * sp * 0,6; bewusst großzügig, damit nichts abgeschnitten wird).
    private const val FRAME_START = WidgetLogic.FRAME_DP + 12
    private const val CLOCK = 100
    private const val CLOCK_DATE = 110
    private const val TITLE = 130
    private const val NEW_BUTTON = 48
    private const val GROUP_PADDING = 8
    private const val BATTERY_TEXT = 36
    private const val BOLT = 18
    private const val BELL_GAP_AND_ICON = 12 + 18
    private const val BELL_TEXT = 28
    private const val SYNC_GROUP = 16 + 4 + 28 + 12

    /** Geschätzte Breite der Kopfzeile in dp mit dem Status und den angegebenen Teilen. */
    private fun headerWidthDp(config: WidgetConfig, display: StatusDisplay, title: Boolean, date: Boolean): Int {
        var w = FRAME_START
        if (config.showClock) w += CLOCK + if (date) CLOCK_DATE else 0
        if (title) w += TITLE
        if (config.showNewButton) w += NEW_BUTTON
        if (display.visible) w += GROUP_PADDING
        if (display.batteryText != null) w += BATTERY_TEXT + if (display.charging) BOLT else 0
        if (display.bellText != null) w += BELL_GAP_AND_ICON + BELL_TEXT
        if (display.syncText != null) w += SYNC_GROUP
        return w
    }

    /**
     * Prioritäten statt Zusatzzeile: von links Uhr (Uhrzeit, optional Datum), Titel (weight=1, wird zuerst weggelassen),
     * Status, "+". Reicht die geschätzte Breite nicht, entfällt zuerst der Titel, dann das Datum; der Status bleibt immer.
     * Ohne sichtbaren Status gilt unverändert die Konfiguration. Bei unbekannter Breite ([widthDp] <= 0) und sichtbarem
     * Status entfällt der Titel.
     */
    fun headerLayout(config: WidgetConfig, display: StatusDisplay, widthDp: Int): HeaderLayout {
        val clockDate = config.showClock && config.showDate
        if (!display.visible) return HeaderLayout(config.showTitle, clockDate)
        if (widthDp <= 0) return HeaderLayout(false, clockDate)
        val withDate = headerWidthDp(config, display, title = false, date = clockDate)
        val date = clockDate && widthDp >= withDate
        val title = config.showTitle && widthDp >= withDate + TITLE
        return HeaderLayout(title, date)
    }
}

/** Wie das Widget nach einer Änderung der Statusgruppe aktualisiert werden muss. */
enum class StatusUpdate { NONE, PARTIAL, FULL }

/** Ablage des zuletzt gezeichneten Zustands je Widget (Android: SharedPreferences, Tests: Map). */
interface StatusStateStore {
    fun get(id: Int): String?
    fun set(id: Int, value: String?)
}

/**
 * Entscheidet "nur neu zeichnen bei Änderung" (E-Ink schonen). Der zuletzt gezeichnete Zustand (Text, Blitz, Kopfzeilen-
 * Layout) steht im [store]; so bleibt die Entscheidung auch nach Prozessende richtig. Ohne Android-Bezug.
 * - kein bekannter Zustand oder anderes Kopfzeilen-Layout (Titel/Datum ein- oder ausgeblendet): [StatusUpdate.FULL]
 * - gleicher Zustand: [StatusUpdate.NONE]; sonst nur andere Texte/Symbole: [StatusUpdate.PARTIAL]
 */
class StatusChangeTracker(private val store: StatusStateStore) {
    @Synchronized
    fun decide(id: Int, display: StatusDisplay, layout: HeaderLayout): StatusUpdate {
        val previous = store.get(id)
        val now = encode(display, layout)
        store.set(id, now)
        return when {
            previous == null -> StatusUpdate.FULL
            previous.substringBefore(SEP, "") != layoutKey(layout) -> StatusUpdate.FULL
            previous == now -> StatusUpdate.NONE
            else -> StatusUpdate.PARTIAL
        }
    }

    /** Merkt den Zustand nach einem vollständigen Neuzeichnen. */
    @Synchronized
    fun record(id: Int, display: StatusDisplay, layout: HeaderLayout) {
        store.set(id, encode(display, layout))
    }

    @Synchronized
    fun forget(id: Int) {
        store.set(id, null)
    }

    private fun layoutKey(l: HeaderLayout) = "${l.title},${l.date}"

    private fun encode(d: StatusDisplay, layout: HeaderLayout): String =
        listOf(layoutKey(layout), d.batteryText ?: "", d.charging.toString(), d.bellText ?: "", d.syncText ?: "", d.syncDimmed.toString())
            .joinToString(SEP.toString())

    private companion object {
        const val SEP = '|'
    }
}
