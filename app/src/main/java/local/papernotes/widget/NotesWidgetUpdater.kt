package local.papernotes.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.home.DateLabels
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import local.papernotes.storage.PnoteResult
import local.papernotes.sync.SyncCoordinator
import local.papernotes.sync.SyncStatus
import local.papernotes.sync.SyncStatusLogic
import local.papernotes.sync.SyncUi
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Aktualisiert alle Widgets von [NotesWidgetProvider]. [requestUpdate] ist der Einstieg für die App (nach dem Speichern,
 * Anlegen, Löschen, Umbenennen ...): gedrosselt auf höchstens einen Lauf je 30 s ([UpdateThrottle]), außer mit `force`.
 * Das Lesen der Notizliste (nur Manifeste, keine Striche) und das Setzen der RemoteViews laufen in einem Hintergrund-Thread.
 * Es gibt kein periodisches Update der Notizen; ohne platzierte Widgets endet jeder Lauf sofort. Akku/Benachrichtigungszähler
 * werden getrennt behandelt ([requestStatusUpdate], [updateStatusNow]) und nur bei geänderter Anzeige neu gezeichnet. Es wird nichts geloggt.
 */
internal object NotesWidgetUpdater {
    private val throttle = UpdateThrottle()
    private val statusThrottle = UpdateThrottle()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-update").apply { isDaemon = true } }

    /** Von jedem Thread aufrufbar. */
    fun requestUpdate(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        SyncCoordinator.onNotesChanged(app) // jede Notizänderung der App läuft hier vorbei; der Sync bündelt selbst
        val wait = throttle.request(SystemClock.elapsedRealtime(), force)
        when {
            wait == 0L -> runAsync(app)
            wait > 0L -> handler.postDelayed({
                throttle.scheduledRunStarted(SystemClock.elapsedRealtime())
                runAsync(app)
            }, wait)
            else -> Unit // bereits vorgemerkt
        }
    }

    private fun runAsync(app: Context) {
        executor.execute {
            try {
                updateNow(app, null)
            } catch (e: Exception) {
                // Das Widget bleibt im letzten Zustand; keine Details loggen.
            }
        }
    }

    /** Blockierend (nicht im UI-Thread aufrufen). [ids] = null: alle Widgets dieser App. */
    fun updateNow(context: Context, ids: IntArray?) {
        val manager = AppWidgetManager.getInstance(context)
        val targets = ids ?: manager.getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java))
        if (targets.isEmpty()) return

        var problem: WidgetProblem? = null
        var all: List<NoteSummary> = emptyList()
        if (!NotesLocation.hasAccess(context)) {
            problem = WidgetProblem.NO_ACCESS
        } else {
            val result = try {
                NoteRepository(NotesLocation.root(context)).listAll("")
            } catch (e: Exception) {
                null
            }
            if (result is PnoteResult.Ok) all = result.value else problem = WidgetProblem.FAILED
        }

        val labels = DateLabels(Locale.getDefault(), TimeZone.getDefault(), System::currentTimeMillis) { loc, skeleton ->
            android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton)
        }
        val untitled = context.getString(R.string.widget_untitled)
        val prefs = NotesWidgetPrefs(context)
        val tracker = StatusChangeTracker(prefs.statusStore)
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val battery = readBattery(context)
        val hasAccess = NotificationCountStore.hasAccess(context)
        val count = NotificationCountStore.get(context)
        val sync = SyncCoordinator.status(context)
        for (id in targets) {
            val config = prefs.load(id)
            val status = WidgetStatusLogic.display(config, battery, count, hasAccess, syncBadge(sync), SyncStatusLogic.dimmed(sync))
            val layout = WidgetStatusLogic.headerLayout(config, status, widthDp(context, manager, id, portrait))
            val limit = WidgetLogic.visibleRows(heightDp(manager, id, portrait), config)
            val rows = WidgetLogic.rows(WidgetLogic.select(all, config, limit), labels, untitled)
            manager.updateAppWidget(id, WidgetRenderer.render(context, id, config, rows, problem, status, layout))
            tracker.record(id, status, layout)
        }
        StatusScheduler.sync(context)
    }

    /** Gedrosselt (höchstens ein Lauf je 30 s) die Statusgruppe aktualisieren; nur wenn ein Widget die Optionen nutzt. */
    fun requestStatusUpdate(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val wait = statusThrottle.request(SystemClock.elapsedRealtime(), force)
        when {
            wait == 0L -> runStatusAsync(app)
            wait > 0L -> handler.postDelayed({
                statusThrottle.scheduledRunStarted(SystemClock.elapsedRealtime())
                runStatusAsync(app)
            }, wait)
            else -> Unit
        }
    }

    private fun runStatusAsync(app: Context) {
        executor.execute {
            try {
                updateStatusNow(app)
            } catch (e: Exception) {
                // Widget bleibt im letzten Zustand.
            }
        }
    }

    /**
     * Blockierend. Liest Akku und Zähler und aktualisiert nur Widgets, die eine der Optionen nutzen: bei unverändertem Text
     * gar nicht, bei geändertem Text nur die betroffenen Views ([AppWidgetManager.partiallyUpdateAppWidget]), bei geändertem
     * Kopfzeilen-Layout (Titel/Datum ein- oder ausgeblendet) vollständig.
     */
    fun updateStatusNow(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java))
        val prefs = NotesWidgetPrefs(context)
        val tracker = StatusChangeTracker(prefs.statusStore)
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val battery = readBattery(context)
        val hasAccess = NotificationCountStore.hasAccess(context)
        val count = NotificationCountStore.get(context)
        val sync = SyncCoordinator.status(context)
        var full = false
        for (id in ids) {
            val config = prefs.load(id)
            if (!config.usesStatus && !config.showSync) continue
            val status = WidgetStatusLogic.display(config, battery, count, hasAccess, syncBadge(sync), SyncStatusLogic.dimmed(sync))
            val layout = WidgetStatusLogic.headerLayout(config, status, widthDp(context, manager, id, portrait))
            when (tracker.decide(id, status, layout)) {
                StatusUpdate.NONE -> Unit
                StatusUpdate.PARTIAL -> {
                    val views = RemoteViews(context.packageName, R.layout.widget_notes)
                    WidgetRenderer.applyStatus(views, status)
                    manager.partiallyUpdateAppWidget(id, views)
                }
                StatusUpdate.FULL -> full = true
            }
        }
        if (full) updateNow(context, null)
        StatusScheduler.sync(context)
    }

    /** Kurztext des Sync-Status fürs Widget; null, wenn der Sync aus oder nicht eingerichtet ist (dann bleibt der Platz frei). */
    private fun syncBadge(status: SyncStatus): String? =
        if (status.ui == SyncUi.OFF) null else SyncStatusLogic.badge(status)

    /** Sticky-Broadcast `ACTION_BATTERY_CHANGED` (kein Receiver nötig); null, wenn nicht lesbar. */
    fun readBattery(context: Context): BatteryReading? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return BatteryMath.reading(
            i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            i.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            i.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
    }

    /**
     * Verfügbare Breite in dp: hochkant = kleinste, quer = größte Breite der Optionen. Meldet der Launcher keine Größe
     * (inkOS tut das nicht), gilt die Bildschirmbreite abzüglich [SCREEN_MARGIN_DP] als Schätzung, damit der Titel
     * nicht grundlos entfällt.
     */
    private fun widthDp(context: Context, manager: AppWidgetManager, id: Int, portrait: Boolean): Int {
        val o = manager.getAppWidgetOptions(id)
        val max = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val min = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val reported = if (portrait) min.takeIf { it > 0 } ?: max else max.takeIf { it > 0 } ?: min
        if (reported > 0) return reported
        return (context.resources.configuration.screenWidthDp - SCREEN_MARGIN_DP).coerceAtLeast(0)
    }

    private const val SCREEN_MARGIN_DP = 40

    /** Verfügbare Höhe in dp: hochkant = größte, quer = kleinste Höhe der Optionen; 0 = unbekannt. */
    private fun heightDp(manager: AppWidgetManager, id: Int, portrait: Boolean): Int {
        val o = manager.getAppWidgetOptions(id)
        val max = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val min = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return if (portrait) max.takeIf { it > 0 } ?: min else min.takeIf { it > 0 } ?: max
    }
}
