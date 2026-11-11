package local.papernotes

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import local.papernotes.about.AboutActivity
import local.papernotes.backup.BackupsActivity
import local.papernotes.storage.BackupPolicy
import local.papernotes.storage.BackupStore
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import local.papernotes.storage.TrashPolicy
import local.papernotes.storage.TrashService
import local.papernotes.sync.SyncSettingsActivity
import local.papernotes.sync.SyncSummary
import local.papernotes.trash.TrashActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Einstellungen: Speicherort (nur Anzeige), Automatische Sicherung (Intervall, Übersicht, Löschen),
 * Papierkorb (Anzahl, Ansicht, leeren, Löschfrist) und Version. Die Sprache folgt dem System.
 * Dateizugriffe laufen nie im UI-Thread.
 */
class SettingsActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var textBackupInterval: TextView
    private lateinit var textBackupsSummary: TextView
    private lateinit var rowBackupInterval: View
    private lateinit var rowBackups: View
    private lateinit var rowDeleteBackups: View

    private lateinit var textTrashCount: TextView
    private lateinit var rowTrash: View
    private lateinit var rowEmptyTrash: View
    private lateinit var rowTrashRetention: View
    private lateinit var textTrashRetention: TextView

    private var backupCount = 0
    private var backupSizeBytes = 0L
    private var trashCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        textBackupInterval = findViewById(R.id.textBackupInterval)
        textBackupsSummary = findViewById(R.id.textBackupsSummary)
        rowBackupInterval = findViewById(R.id.rowBackupInterval)
        rowBackups = findViewById(R.id.rowBackups)
        rowDeleteBackups = findViewById(R.id.rowDeleteBackups)

        textTrashCount = findViewById(R.id.textTrashCount)
        rowTrash = findViewById(R.id.rowTrash)
        rowEmptyTrash = findViewById(R.id.rowEmptyTrash)
        rowTrashRetention = findViewById(R.id.rowTrashRetention)
        textTrashRetention = findViewById(R.id.textTrashRetention)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.textLocation).text = NotesLocation.location().absolutePath
        findViewById<TextView>(R.id.textVersion).text = versionName()

        rowBackupInterval.setOnClickListener { showBackupIntervalDialog() }
        rowBackups.setOnClickListener { startActivity(Intent(this, BackupsActivity::class.java)) }
        rowDeleteBackups.setOnClickListener { if (backupCount > 0) askDeleteAllBackups() }

        findViewById<View>(R.id.rowSync).setOnClickListener { startActivity(Intent(this, SyncSettingsActivity::class.java)) }
        rowTrash.setOnClickListener { startActivity(Intent(this, TrashActivity::class.java)) }
        rowEmptyTrash.setOnClickListener { if (trashCount > 0) askEmptyTrash() }
        rowTrashRetention.setOnClickListener { showTrashRetentionDialog() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        updateBackupIntervalUi()
        updateBackupStatsUi()
        updateTrashUi()
        updateTrashRetentionUi()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.textSyncSummary).text = SyncSummary.text(this)
        updateBackupIntervalUi()
        refreshBackupStats()
        refreshTrashCount()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    // ---------------------------------------------------------------- Sicherung

    private fun getBackupIntervalMinutes(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(BackupPolicy.PREF_KEY_INTERVAL_MINUTES, BackupPolicy.DEFAULT_INTERVAL_MINUTES)
    }

    private fun setBackupIntervalMinutes(minutes: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(BackupPolicy.PREF_KEY_INTERVAL_MINUTES, minutes) }
        updateBackupIntervalUi()
    }

    private fun updateBackupIntervalUi() {
        val minutes = getBackupIntervalMinutes()
        textBackupInterval.text = formatIntervalLabel(minutes)
    }

    private fun formatIntervalLabel(minutes: Int): String = when (minutes) {
        BackupPolicy.INTERVAL_OFF -> getString(R.string.backup_interval_off)
        1 -> getString(R.string.backup_interval_1)
        2 -> getString(R.string.backup_interval_2)
        5 -> getString(R.string.backup_interval_5_default)
        10 -> getString(R.string.backup_interval_10)
        15 -> getString(R.string.backup_interval_15)
        30 -> getString(R.string.backup_interval_30)
        else -> resources.getQuantityString(R.plurals.backup_interval_minutes, minutes, minutes)
    }

    private fun showBackupIntervalDialog() {
        val options = BackupPolicy.INTERVAL_OPTIONS
        val labels = options.map { formatIntervalLabel(it) }
        val current = getBackupIntervalMinutes()
        val selectedIdx = options.indexOf(current).let { if (it >= 0) it else options.indexOf(BackupPolicy.DEFAULT_INTERVAL_MINUTES) }

        EinkDialog.singleChoice(
            this,
            getString(R.string.settings_backup_interval_title),
            labels,
            selectedIdx,
        ) { chosenIdx ->
            if (chosenIdx in options.indices) {
                setBackupIntervalMinutes(options[chosenIdx])
            }
        }
    }

    private fun refreshBackupStats() {
        if (!NotesLocation.hasAccess(this)) {
            backupCount = 0
            backupSizeBytes = 0L
            updateBackupStatsUi()
            return
        }
        val store = BackupStore(NotesLocation.root(this))
        io.execute {
            val count = store.count()
            val size = store.totalSizeBytes()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                backupCount = count
                backupSizeBytes = size
                updateBackupStatsUi()
            }
        }
    }

    private fun updateBackupStatsUi() {
        if (backupCount == 0) {
            textBackupsSummary.text = getString(R.string.backup_none)
        } else {
            val countStr = resources.getQuantityString(R.plurals.backup_count, backupCount, backupCount)
            val sizeStr = Formatter.formatFileSize(this, backupSizeBytes)
            textBackupsSummary.text = getString(R.string.backup_summary_with_size, countStr, sizeStr)
        }
        rowDeleteBackups.isEnabled = backupCount > 0
        rowDeleteBackups.isClickable = backupCount > 0
    }

    private fun askDeleteAllBackups() {
        EinkDialog.confirm(
            this,
            getString(R.string.backup_delete_all_title),
            getString(R.string.backup_delete_all_message),
            getString(R.string.delete),
        ) {
            val store = BackupStore(NotesLocation.root(this))
            io.execute {
                val ok = store.deleteAll() is PnoteResult.Ok
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (!ok) Toast.makeText(this, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
                    refreshBackupStats()
                }
            }
        }
    }

    // ---------------------------------------------------------------- Papierkorb

    private fun refreshTrashCount() {
        if (!NotesLocation.hasAccess(this)) {
            trashCount = 0
            updateTrashUi()
            return
        }
        val repo = NoteRepository(NotesLocation.root(this))
        io.execute {
            val count = repo.trashCount()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                trashCount = count
                updateTrashUi()
            }
        }
    }

    private fun updateTrashUi() {
        textTrashCount.text = resources.getQuantityString(R.plurals.notes_count, trashCount, trashCount)
        rowEmptyTrash.isEnabled = trashCount > 0
        rowEmptyTrash.isClickable = trashCount > 0
    }

    private fun askEmptyTrash() {
        EinkDialog.confirm(
            this,
            getString(R.string.trash_empty_title),
            getString(R.string.trash_empty_message),
            getString(R.string.trash_empty_confirm),
        ) {
            val root = NotesLocation.root(this)
            io.execute {
                val ok = TrashService(NoteRepository(root), BackupStore(root)).emptyTrash() is PnoteResult.Ok
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (!ok) Toast.makeText(this, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
                    refreshTrashCount()
                    refreshBackupStats()
                }
            }
        }
    }

    private fun getTrashRetentionDays(): Int =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(TrashPolicy.PREF_KEY_RETENTION_DAYS, TrashPolicy.DEFAULT_RETENTION_DAYS)

    private fun formatRetentionLabel(days: Int): String = when (days) {
        TrashPolicy.RETENTION_NEVER -> getString(R.string.trash_retention_never)
        TrashPolicy.DEFAULT_RETENTION_DAYS -> getString(R.string.trash_retention_default, days)
        else -> resources.getQuantityString(R.plurals.trash_retention_days, days, days)
    }

    private fun updateTrashRetentionUi() {
        textTrashRetention.text = formatRetentionLabel(getTrashRetentionDays())
    }

    private fun showTrashRetentionDialog() {
        val options = TrashPolicy.RETENTION_OPTIONS
        val current = getTrashRetentionDays()
        val selectedIdx = options.indexOf(current).let { if (it >= 0) it else options.indexOf(TrashPolicy.DEFAULT_RETENTION_DAYS) }
        EinkDialog.singleChoice(
            this,
            getString(R.string.trash_retention_title),
            options.map { formatRetentionLabel(it) },
            selectedIdx,
        ) { idx ->
            if (idx in options.indices) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(TrashPolicy.PREF_KEY_RETENTION_DAYS, options[idx]) }
                updateTrashRetentionUi()
            }
        }
    }

    companion object {
        const val PREFS_NAME = "settings"
    }
}
