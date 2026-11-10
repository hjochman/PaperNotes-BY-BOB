package local.papernotes.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import local.papernotes.MainActivity
import local.papernotes.R
import local.papernotes.editor.EditorActivity
import java.util.Locale

/** Was das Widget statt der Notizzeilen zeigt, wenn es keine gibt. */
internal enum class WidgetProblem { NO_ACCESS, FAILED }

/**
 * Baut die [RemoteViews] des Widgets aus Einstellungen und fertig ausgewählten Zeilen. Nur feste Zeilen 1..8 (kein
 * RemoteViewsService), alle Tipp-Ziele sind explizite, unveränderliche PendingIntents auf eigene, nicht exportierte Activities.
 */
internal object WidgetRenderer {
    private val ROW = intArrayOf(R.id.widgetRow1, R.id.widgetRow2, R.id.widgetRow3, R.id.widgetRow4, R.id.widgetRow5, R.id.widgetRow6, R.id.widgetRow7, R.id.widgetRow8)
    private val TITLE = intArrayOf(R.id.widgetTitle1, R.id.widgetTitle2, R.id.widgetTitle3, R.id.widgetTitle4, R.id.widgetTitle5, R.id.widgetTitle6, R.id.widgetTitle7, R.id.widgetTitle8)
    private val DATE = intArrayOf(R.id.widgetDate1, R.id.widgetDate2, R.id.widgetDate3, R.id.widgetDate4, R.id.widgetDate5, R.id.widgetDate6, R.id.widgetDate7, R.id.widgetDate8)
    private val PIN = intArrayOf(R.id.widgetPin1, R.id.widgetPin2, R.id.widgetPin3, R.id.widgetPin4, R.id.widgetPin5, R.id.widgetPin6, R.id.widgetPin7, R.id.widgetPin8)
    private val FAV = intArrayOf(R.id.widgetFav1, R.id.widgetFav2, R.id.widgetFav3, R.id.widgetFav4, R.id.widgetFav5, R.id.widgetFav6, R.id.widgetFav7, R.id.widgetFav8)

    fun render(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig,
        rows: List<WidgetRow>,
        problem: WidgetProblem?,
        status: StatusDisplay = StatusDisplay.NONE,
        layout: HeaderLayout = HeaderLayout(config.showTitle, config.showClock && config.showDate),
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_notes)
        applyStatus(views, status)
        val header = WidgetLogic.showHeader(config)
        views.setViewVisibility(R.id.widgetHeader, vis(header))
        views.setViewVisibility(R.id.widgetHeaderLine, vis(header))
        views.setViewVisibility(R.id.widgetClockBox, vis(config.showClock))
        views.setViewVisibility(R.id.widgetClockDate, vis(config.showClock && layout.date))
        views.setViewVisibility(R.id.widgetHeaderTitle, vis(layout.title))
        views.setViewVisibility(R.id.widgetSpacer, vis(!layout.title))
        views.setViewVisibility(R.id.widgetNew, vis(config.showNewButton))
        if (config.showClock && layout.date) {
            // Datumsmuster nach Gerätesprache; die Uhr selbst folgt der Systemeinstellung 12/24 Stunden (Standardformat ohne Sekunden).
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), WidgetLogic.CLOCK_DATE_SKELETON)
            views.setCharSequence(R.id.widgetClockDate, "setFormat12Hour", pattern)
            views.setCharSequence(R.id.widgetClockDate, "setFormat24Hour", pattern)
        }

        val home = homeIntent(context, appWidgetId)
        views.setOnClickPendingIntent(R.id.widgetHeaderTitle, home)
        views.setOnClickPendingIntent(R.id.widgetClockBox, home)
        views.setOnClickPendingIntent(R.id.widgetStatusHdr, home) // Startseite bleibt erreichbar, auch wenn der Titel entfällt
        views.setOnClickPendingIntent(R.id.widgetSyncHdr, syncIntent(context, appWidgetId))
        views.setOnClickPendingIntent(R.id.widgetEmpty, home)
        views.setOnClickPendingIntent(R.id.widgetFooter, home)
        views.setOnClickPendingIntent(R.id.widgetNew, newNoteIntent(context, appWidgetId))

        val footer = WidgetLogic.showFooter(config)
        views.setViewVisibility(R.id.widgetFooter, vis(footer))
        views.setViewVisibility(R.id.widgetFooterLine, vis(footer))

        val shown = if (problem == null) rows.take(ROW.size) else emptyList()
        for (i in ROW.indices) {
            val row = shown.getOrNull(i)
            if (row == null) {
                views.setViewVisibility(ROW[i], View.GONE)
                continue
            }
            views.setViewVisibility(ROW[i], View.VISIBLE)
            views.setTextViewText(TITLE[i], row.title)
            views.setTextViewText(DATE[i], row.dateText)
            views.setViewVisibility(PIN[i], vis(row.pinned))
            views.setViewVisibility(FAV[i], vis(row.favorite))
            views.setOnClickPendingIntent(ROW[i], noteIntent(context, appWidgetId, i, row.noteId))
        }

        val message = when {
            problem == WidgetProblem.NO_ACCESS -> R.string.widget_no_access
            problem == WidgetProblem.FAILED -> R.string.widget_error
            shown.isEmpty() -> emptyMessage(config)
            else -> 0
        }
        if (message != 0) {
            views.setTextViewText(R.id.widgetEmpty, context.getString(message))
            views.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widgetEmpty, View.GONE)
        }
        return views
    }

    /**
     * Setzt nur die Statusgruppe (Sync/Akku/Glocke) in der Kopfzeile; es gibt keine zusätzliche Zeile. Wird auch allein für
     * partielle Updates verwendet.
     */
    fun applyStatus(views: RemoteViews, status: StatusDisplay) {
        views.setViewVisibility(R.id.widgetStatusHdr, vis(status.visible))
        views.setViewVisibility(R.id.widgetSyncHdr, vis(status.syncText != null))
        status.syncText?.let { views.setTextViewText(R.id.widgetSyncTextHdr, it) }
        // RemoteViews erlaubt kein setAlpha auf Layouts: Symbol über setImageAlpha, Zahl über die Textfarbe abblenden.
        views.setInt(R.id.widgetSyncIconHdr, "setImageAlpha", if (status.syncDimmed) SYNC_DIM_ALPHA else OPAQUE)
        views.setTextColor(R.id.widgetSyncTextHdr, if (status.syncDimmed) SYNC_DIM_TEXT else Color.BLACK)
        views.setViewVisibility(R.id.widgetBatteryHdr, vis(status.batteryText != null))
        views.setViewVisibility(R.id.widgetBellHdr, vis(status.bellText != null))
        status.batteryText?.let { views.setTextViewText(R.id.widgetBatteryTextHdr, it) }
        views.setViewVisibility(R.id.widgetBoltHdr, vis(status.charging))
        status.bellText?.let { views.setTextViewText(R.id.widgetBellTextHdr, it) }
    }

    private fun emptyMessage(config: WidgetConfig): Int = when (config.source) {
        WidgetSource.FAVORITES -> R.string.widget_empty_favorites
        WidgetSource.PINNED -> R.string.widget_empty_pinned
        else -> R.string.widget_empty
    }

    private fun vis(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun noteIntent(context: Context, appWidgetId: Int, slot: Int, noteId: String): PendingIntent {
        val intent = EditorActivity.intent(context, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode(appWidgetId, slot), intent, FLAGS)
    }

    private fun homeIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode(appWidgetId, SLOT_HOME), intent, FLAGS)
    }

    /** Öffnet die Startseite mit dem Sync-Dialog. */
    private fun syncIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(local.papernotes.sync.SyncDialog.EXTRA_OPEN, true)
        return PendingIntent.getActivity(context, requestCode(appWidgetId, SLOT_SYNC), intent, FLAGS)
    }

    private fun newNoteIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, NewNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode(appWidgetId, SLOT_NEW), intent, FLAGS)
    }

    private fun requestCode(appWidgetId: Int, slot: Int) = appWidgetId * SLOTS + slot

    private const val SLOT_HOME = 8
    private const val SLOT_NEW = 9
    private const val SLOT_SYNC = 10
    private const val SYNC_DIM_ALPHA = 115 // 45 % von 255, wie auf der Startseite
    private const val OPAQUE = 255
    private const val SYNC_DIM_TEXT = 0xFF8C8C8C.toInt() // schwarz mit 45 % Deckkraft auf Weiß
    private const val SLOTS = 16
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
