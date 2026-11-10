package local.papernotes.backup

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.home.DateLabels
import local.papernotes.home.Pagination
import local.papernotes.storage.BackupStore
import local.papernotes.storage.NoteBackupGroup
import local.papernotes.storage.NoteTitles
import local.papernotes.storage.PnoteResult
import local.papernotes.trash.TrashEntryText
import local.papernotes.trash.TrashPaging
import local.papernotes.widget.NotesWidgetUpdater
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Übersicht der automatischen Sicherungskopien: Gruppiert nach Notiz mit Titel der jüngsten Sicherung,
 * Datum, Seitenzahl und Anzahl der Versionen.
 * Blättern statt Scrollen (E-Ink). Pro Eintrag "Wiederherstellen als Kopie".
 * Dateizugriffe laufen nie im UI-Thread.
 */
class BackupsActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var textCount: TextView
    private lateinit var contentArea: View
    private lateinit var pageRows: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var buttonPrevPage: View
    private lateinit var buttonNextPage: View
    private lateinit var textPageNumber: TextView

    private var store: BackupStore? = null
    private var items: List<NoteBackupGroup> = emptyList()
    private var pages: List<List<Int>> = emptyList()
    private var page = 0
    private var loaded = false
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backups)
        textCount = findViewById(R.id.textCount)
        contentArea = findViewById(R.id.contentArea)
        pageRows = findViewById(R.id.pageRows)
        textEmpty = findViewById(R.id.textEmpty)
        buttonPrevPage = findViewById(R.id.buttonPrevPage)
        buttonNextPage = findViewById(R.id.buttonNextPage)
        textPageNumber = findViewById(R.id.textPageNumber)

        if (savedInstanceState != null) page = savedInstanceState.getInt(STATE_PAGE, 0)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        buttonPrevPage.setOnClickListener {
            page = Pagination.previous(page, pages.size)
            render()
        }
        buttonNextPage.setOnClickListener {
            page = Pagination.next(page, pages.size)
            render()
        }
        contentArea.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) contentArea.post { render() }
        }
        if (NotesLocation.hasAccess(this)) store = BackupStore(NotesLocation.root(this))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        loadToken++
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        val s = store
        if (s == null) {
            items = emptyList()
            loaded = true
            render()
            return
        }
        val token = ++loadToken
        io.execute {
            val list = s.listGroups()
            runOnUiThread {
                if (isDestroyed || token != loadToken) return@runOnUiThread
                items = list
                loaded = true
                render()
            }
        }
    }

    private fun render() {
        if (!loaded) return
        val totalBackupCount = items.sumOf { it.backupCount }
        textCount.text = resources.getQuantityString(R.plurals.backup_notes_count, items.size, items.size)
        val h = contentArea.height
        if (h <= 0) return
        pages = TrashPaging.pages(items.size, h, dp(ROW_HEIGHT_DP))
        page = Pagination.clamp(page, pages.size)
        pageRows.removeAllViews()
        if (items.isEmpty()) {
            textEmpty.visibility = View.VISIBLE
        } else {
            textEmpty.visibility = View.GONE
            val dates = DateLabels(Locale.getDefault(), TimeZone.getDefault(), System::currentTimeMillis) { loc, skeleton ->
                android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton)
            }
            pages[page].forEach { index -> pageRows.addView(buildRow(items[index], dates)) }
        }
        textPageNumber.text = Pagination.label(page, pages.size)
        buttonPrevPage.isEnabled = Pagination.hasPrevious(page)
        buttonNextPage.isEnabled = Pagination.hasNext(page, pages.size)
    }

    private fun buildRow(group: NoteBackupGroup, dates: DateLabels): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(8), 0)
            background = getDrawable(R.drawable.bg_list_row)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = group.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(getColor(R.color.black))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )

        val folder = TrashEntryText.folderName(group.folderPath, getString(R.string.home_all_notes))
        val dateText = getString(R.string.backup_saved_format, dates.format(group.latestTimestamp))
        val pageText = resources.getQuantityString(R.plurals.trash_pages, group.latestPageCount, group.latestPageCount)
        val details = if (group.backupCount > 1) {
            val countText = resources.getQuantityString(R.plurals.backup_count, group.backupCount, group.backupCount)
            "$folder • $dateText • $pageText ($countText)"
        } else {
            "$folder • $dateText • $pageText"
        }

        texts.addView(
            TextView(this).apply {
                text = details
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getColor(R.color.text_secondary))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action(R.drawable.ic_restore, R.string.backup_restore_as_copy) { restore(group, dates) })
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        return row
    }

    private fun action(icon: Int, label: Int, onClick: () -> Unit) =
        TextView(this, null, 0, R.style.Widget_PaperNotes_SelectionAction).apply {
            setText(label)
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun restore(group: NoteBackupGroup, dates: DateLabels) {
        val s = store ?: return
        val dateFormatted = dates.format(group.latestTimestamp)
        val newTitle = NoteTitles.copyTitle(group.title) { base ->
            getString(R.string.backup_restore_title_format, base, dateFormatted)
        }
        io.execute {
            val res = s.restore(
                noteId = group.noteId,
                backupFileName = group.latestBackupFileName,
                newTitle = newTitle,
            )
            if (res is PnoteResult.Ok) NotesWidgetUpdater.requestUpdate(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (res is PnoteResult.Ok) {
                    Toast.makeText(this, R.string.backup_restored_success, Toast.LENGTH_SHORT).show()
                    load()
                } else {
                    Toast.makeText(this, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    private companion object {
        const val ROW_HEIGHT_DP = 88
        const val STATE_PAGE = "page"
    }
}
