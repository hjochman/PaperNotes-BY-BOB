package local.papernotes.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Einstellungen einer Widget-Instanz im Stil der Einstellungsseite. Wird vom Widget-Host (Launcher) gestartet und ist
 * deshalb exportiert (`APPWIDGET_CONFIGURE`); darum wird die `appWidgetId` geprüft: sie muss gültig sein und zu unserem
 * Provider gehören, sonst endet die Seite sofort. Das Ergebnis ist standardmäßig `RESULT_CANCELED`; erst "Fertig"
 * speichert und meldet `RESULT_OK`. Dateizugriffe (Ordnerliste) laufen nie im UI-Thread.
 */
class NotesWidgetConfigActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var prefs: NotesWidgetPrefs
    private var config = WidgetConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (!isOurWidget(appWidgetId)) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        prefs = NotesWidgetPrefs(this)
        config = savedInstanceState?.let { restore(it) } ?: prefs.load(appWidgetId)
        setContentView(R.layout.activity_widget_config)

        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.buttonDone).setOnClickListener { done() }
        findViewById<View>(R.id.rowClock).setOnClickListener { update(config.copy(showClock = !config.showClock)) }
        findViewById<View>(R.id.rowDate).setOnClickListener { if (config.showClock) update(config.copy(showDate = !config.showDate)) }
        findViewById<View>(R.id.rowSource).setOnClickListener { chooseSource() }
        findViewById<View>(R.id.rowFolder).setOnClickListener { chooseFolder() }
        findViewById<View>(R.id.rowCount).setOnClickListener { chooseCount() }
        findViewById<View>(R.id.rowFavoritesFirst).setOnClickListener {
            if (config.source != WidgetSource.FAVORITES) update(config.copy(favoritesFirst = !config.favoritesFirst))
        }
        findViewById<View>(R.id.rowNewButton).setOnClickListener { update(config.copy(showNewButton = !config.showNewButton)) }
        findViewById<View>(R.id.rowTitle).setOnClickListener { update(config.copy(showTitle = !config.showTitle)) }
        findViewById<View>(R.id.rowSync).setOnClickListener { update(config.copy(showSync = !config.showSync)) }
        findViewById<View>(R.id.rowBattery).setOnClickListener { update(config.copy(showBattery = !config.showBattery)) }
        findViewById<View>(R.id.rowNotifications).setOnClickListener { toggleNotifications() }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        config.toEntries().forEach { (k, v) -> outState.putString(k, v) }
    }

    private fun restore(state: Bundle): WidgetConfig? =
        if (state.containsKey(WidgetConfig.KEY_COUNT)) WidgetConfig.fromEntries { state.getString(it) } else null

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun isOurWidget(id: Int): Boolean {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return false
        return info.provider == ComponentName(this, NotesWidgetProvider::class.java)
    }

    private fun update(new: WidgetConfig) {
        config = new
        refresh()
    }

    private fun refresh() {
        text(R.id.rowClockValue, yesNo(config.showClock))
        text(R.id.rowDateValue, yesNo(config.showClock && config.showDate))
        findViewById<View>(R.id.rowDate).isEnabled = config.showClock
        findViewById<TextView>(R.id.rowDateValue).isEnabled = config.showClock
        text(R.id.rowSourceValue, sourceLabel(config.source))
        findViewById<View>(R.id.rowFolder).visibility = if (config.source == WidgetSource.FOLDER) View.VISIBLE else View.GONE
        text(R.id.rowFolderValue, config.folder.ifEmpty { getString(R.string.home_all_notes) })
        text(R.id.rowCountValue, config.count.toString())
        val favoritesFirstActive = config.source != WidgetSource.FAVORITES
        text(R.id.rowFavoritesFirstValue, yesNo(favoritesFirstActive && config.favoritesFirst))
        findViewById<View>(R.id.rowFavoritesFirst).isEnabled = favoritesFirstActive
        findViewById<TextView>(R.id.rowFavoritesFirstValue).isEnabled = favoritesFirstActive
        text(R.id.rowNewButtonValue, yesNo(config.showNewButton))
        text(R.id.rowTitleValue, yesNo(config.showTitle))
        text(R.id.rowSyncValue, yesNo(config.showSync))
        text(R.id.rowBatteryValue, yesNo(config.showBattery))
        text(R.id.rowNotificationsValue, yesNo(config.showNotifications))
    }

    /**
     * Zählen einschalten: Fehlt der Benachrichtigungszugriff, erklärt ein Dialog, dass nur die Anzahl gezählt wird, und
     * öffnet auf Wunsch die Systemeinstellung. Die Option bleibt eingeschaltet (das Widget zeigt dann "–", bis der Zugriff erteilt ist).
     * Der Zugriff selbst kann nur der Nutzer in den Android-Einstellungen gewähren.
     */
    private fun toggleNotifications() {
        val enable = !config.showNotifications
        update(config.copy(showNotifications = enable))
        if (!enable || NotificationCountStore.hasAccess(this)) return
        EinkDialog.confirm(
            this,
            getString(R.string.widget_notif_access_title),
            getString(R.string.widget_notif_access_message),
            getString(R.string.widget_notif_open_settings),
        ) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.widget_notif_settings_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.widget_config_yes else R.string.widget_config_no)

    private fun sourceLabel(source: WidgetSource) = getString(
        when (source) {
            WidgetSource.RECENT -> R.string.widget_source_recent
            WidgetSource.FAVORITES -> R.string.widget_source_favorites
            WidgetSource.PINNED -> R.string.widget_source_pinned
            WidgetSource.FOLDER -> R.string.widget_source_folder
        },
    )

    private fun chooseSource() {
        val sources = WidgetSource.values().toList()
        EinkDialog.singleChoice(
            this,
            getString(R.string.widget_config_source),
            sources.map { sourceLabel(it) },
            sources.indexOf(config.source),
        ) { index ->
            val chosen = sources.getOrNull(index) ?: return@singleChoice
            update(config.copy(source = chosen))
            if (chosen == WidgetSource.FOLDER) chooseFolder()
        }
    }

    private fun chooseCount() {
        val options = WidgetConfig.COUNT_OPTIONS
        EinkDialog.singleChoice(
            this,
            getString(R.string.widget_config_count),
            options.map { it.toString() },
            options.indexOf(config.count),
        ) { index -> options.getOrNull(index)?.let { update(config.copy(count = it)) } }
    }

    private fun chooseFolder() {
        if (!NotesLocation.hasAccess(this)) {
            Toast.makeText(this, R.string.error_no_storage, Toast.LENGTH_LONG).show()
            return
        }
        val root = NotesLocation.root(this)
        io.execute {
            val folders = try {
                (NoteRepository(root).listAllFolders() as? PnoteResult.Ok)?.value.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val paths = listOf("") + folders
                EinkDialog.singleChoice(
                    this,
                    getString(R.string.widget_config_folder),
                    paths.map { it.ifEmpty { getString(R.string.home_all_notes) } },
                    paths.indexOf(config.folder),
                ) { index -> paths.getOrNull(index)?.let { update(config.copy(folder = it)) } }
            }
        }
    }

    private fun done() {
        prefs.save(appWidgetId, config)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        val app = applicationContext
        val id = appWidgetId
        Thread {
            try {
                NotesWidgetUpdater.updateNow(app, intArrayOf(id))
            } catch (e: Exception) {
                // Das Widget zeigt dann den Ausgangszustand; keine Details loggen.
            }
        }.start()
        finish()
    }
}
