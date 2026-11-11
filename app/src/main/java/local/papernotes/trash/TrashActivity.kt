package local.papernotes.trash

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
import local.papernotes.CategoryMenu
import local.papernotes.CategoryTarget
import local.papernotes.EinkDialog
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.home.DateLabels
import local.papernotes.home.Pagination
import local.papernotes.storage.BackupStore
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import local.papernotes.SettingsActivity
import local.papernotes.storage.TrashPolicy
import local.papernotes.storage.TrashService
import local.papernotes.storage.TrashedNote
import local.papernotes.widget.NotesWidgetUpdater
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Papierkorb-Ansicht: Notizen aus `.trash/` mit Titel, Ursprungsordner, Löschdatum und Seitenzahl, Blättern statt Scrollen (E-Ink).
 * Pro Eintrag "Wiederherstellen" und "Endgültig löschen" (mit Rückfrage), oben "Papierkorb leeren" (mit Rückfrage).
 * Endgültiges Löschen entfernt auch die Sicherungen der Notiz ([TrashService]). Die automatische Löschfrist stellt man in den Einstellungen ein. Dateizugriffe laufen nie im UI-Thread; Titel und Pfade werden nicht geloggt.
 */
class TrashActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var actionEmptyTrash: View
    private lateinit var textCount: TextView
    private lateinit var contentArea: View
    private lateinit var pageRows: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var buttonPrevPage: View
    private lateinit var buttonNextPage: View
    private lateinit var textPageNumber: TextView

    private var repo: NoteRepository? = null
    private var items: List<TrashedNote> = emptyList()
    private var pages: List<List<Int>> = emptyList()
    private var page = 0
    private var loaded = false
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)
        actionEmptyTrash = findViewById(R.id.actionEmptyTrash)
        textCount = findViewById(R.id.textCount)
        contentArea = findViewById(R.id.contentArea)
        pageRows = findViewById(R.id.pageRows)
        textEmpty = findViewById(R.id.textEmpty)
        buttonPrevPage = findViewById(R.id.buttonPrevPage)
        buttonNextPage = findViewById(R.id.buttonNextPage)
        textPageNumber = findViewById(R.id.textPageNumber)

        if (savedInstanceState != null) page = savedInstanceState.getInt(STATE_PAGE, 0)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.textTitle).apply {
            text = getString(R.string.settings_trash) + "  ▾"
            setOnClickListener { CategoryMenu.show(this@TrashActivity, this, io) { CategoryMenu.switchFrom(this@TrashActivity, CategoryTarget.TRASH, it) } }
        }
        actionEmptyTrash.setOnClickListener { askEmptyTrash() }
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
        if (NotesLocation.hasAccess(this)) repo = NoteRepository(NotesLocation.root(this))
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

    // ---------------------------------------------------------------- Laden und Darstellen

    private fun load() {
        val r = repo
        if (r == null) {
            items = emptyList()
            loaded = true
            render()
            return
        }
        val token = ++loadToken
        io.execute {
            val list = (r.listTrash() as? PnoteResult.Ok)?.value
            runOnUiThread {
                if (isDestroyed || token != loadToken) return@runOnUiThread
                if (list == null) toast(R.string.error_action_failed)
                items = list.orEmpty()
                loaded = true
                render()
            }
        }
    }

    private fun render() {
        if (!loaded) return
        textCount.text = resources.getQuantityString(R.plurals.notes_count, items.size, items.size)
        actionEmptyTrash.isEnabled = items.isNotEmpty()
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

    /** Eine Zeile fester Höhe: links Titel und Details, rechts die beiden Aktionen. */
    private fun buildRow(note: TrashedNote, dates: DateLabels): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(8), 0)
            background = getDrawable(R.drawable.bg_list_row)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = note.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(getColor(R.color.black))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val retentionDays = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            .getInt(TrashPolicy.PREF_KEY_RETENTION_DAYS, TrashPolicy.DEFAULT_RETENTION_DAYS)
        val expiresAt = TrashPolicy.expiresAt(note.deleted, retentionDays)
        val details = TrashEntryText.details(
            folder = TrashEntryText.folderName(note.folderPath, getString(R.string.home_all_notes)),
            deleted = getString(R.string.trash_deleted_format, dates.format(note.deleted)),
            pages = resources.getQuantityString(R.plurals.trash_pages, note.pageCount, note.pageCount),
            expires = expiresAt?.let { getString(R.string.trash_expires_format, dates.format(it)) }.orEmpty(),
        )
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
        row.addView(action(R.drawable.ic_restore, R.string.trash_restore) { restore(note) })
        row.addView(action(R.drawable.ic_trash, R.string.trash_delete_forever) { askDeleteForever(note) })
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        return row
    }

    private fun action(icon: Int, label: Int, onClick: () -> Unit) =
        TextView(this, null, 0, R.style.Widget_PaperNotes_SelectionAction).apply {
            setText(label)
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- Aktionen

    private fun restore(note: TrashedNote) {
        val r = repo ?: return
        io.execute {
            val failed = r.restoreFromTrash(note.entryName) is PnoteResult.Err
            NotesWidgetUpdater.requestUpdate(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (failed) toast(R.string.error_action_failed)
                load()
            }
        }
    }

    private fun askDeleteForever(note: TrashedNote) {
        EinkDialog.confirm(
            this,
            getString(R.string.trash_delete_title),
            getString(R.string.trash_delete_message, note.title),
            getString(R.string.trash_delete_forever),
        ) { runRepo { it.deleteForever(note.entryName) } }
    }

    private fun askEmptyTrash() {
        if (items.isEmpty()) return
        EinkDialog.confirm(
            this,
            getString(R.string.trash_empty_title),
            getString(R.string.trash_empty_message),
            getString(R.string.trash_empty_confirm),
        ) { runRepo { it.emptyTrash() } }
    }

    /** Führt [block] im Hintergrund aus, meldet Fehler und lädt danach neu. */
    private fun runRepo(block: (TrashService) -> PnoteResult<*>) {
        val r = repo ?: return
        io.execute {
            val failed = block(TrashService(r, BackupStore(NotesLocation.root(this)))) is PnoteResult.Err
            NotesWidgetUpdater.requestUpdate(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (failed) toast(R.string.error_action_failed)
                load()
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    private companion object {
        const val ROW_HEIGHT_DP = 88
        const val STATE_PAGE = "page"
    }
}
