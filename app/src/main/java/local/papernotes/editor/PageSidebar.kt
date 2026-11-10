package local.papernotes.editor

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.R
import local.papernotes.home.Pagination
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Seitenleiste des Editors: Fenster rechts über dem Blatt (wie [TemplateDialog] ohne Animation, Abdunkelung und Schatten)
 * mit den Reitern "Seiten" und "Lesezeichen". Die Vorschauliste wird nicht gescrollt, sondern per Pfeil geblättert; wie
 * viele Zeilen passen, ergibt sich aus der gemessenen Listenhöhe ([SidebarLogic]). Der Aufrufer koppelt Öffnen und Schließen
 * an `overlayOpened()/overlayClosed()` der Activity ([onDismiss]); solange die Leiste offen ist, schreibt der Stift nicht.
 * Der Zustand liegt in der [NoteSession]; Änderungen laufen über [Actions], danach zeichnet die Leiste sich neu.
 */
internal class PageSidebar(
    private val activity: Activity,
    private val session: NoteSession,
    /** Bildschirmbereich der Schreibfläche, den die Leiste (am rechten Rand) überdeckt. */
    private val area: Rect,
    private val actions: Actions,
) {
    interface Actions {
        /** Wechselt zur Seite [index] (die Leiste schließt sich danach). */
        fun jump(index: Int)

        /** Fügt am Ende eine Seite an und springt hin (die Leiste schließt sich danach). */
        fun newPage()

        fun deletePage(index: Int)

        fun setBookmark(index: Int, bookmarked: Boolean)
    }

    private enum class Tab { PAGES, BOOKMARKS }

    private val dialog = Dialog(activity, R.style.Theme_PaperNotes_Sidebar)
    private val previews = PagePreviews(activity)
    private val density = activity.resources.displayMetrics.density
    private var tab = Tab.PAGES
    private val listPages = IntArray(Tab.values().size)
    private var focusCurrent = true
    private var listHeight = -1

    private lateinit var tabPages: View
    private lateinit var tabBookmarks: View
    private lateinit var jumpRow: View
    private lateinit var jumpInput: EditText
    private lateinit var list: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var buttonPrev: View
    private lateinit var buttonNext: View

    /** Wird nach jedem Schließen aufgerufen (Außen antippen, Zurück, Sprung, Neue Seite, X). */
    var onDismiss: (() -> Unit)? = null

    private fun dp(v: Float) = (v * density).roundToInt()

    private val thumbWidth = dp(THUMB_DP)
    private val thumbPad = dp(4f)

    @Suppress("InflateParams") // Dialog-Inhalt hat kein Elternelement.
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_sidebar, null)
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            previews.shutdown()
            onDismiss?.invoke()
        }

        tabPages = root.findViewById(R.id.tabPages)
        tabBookmarks = root.findViewById(R.id.tabBookmarks)
        jumpRow = root.findViewById(R.id.jumpRow)
        jumpInput = root.findViewById(R.id.jumpInput)
        list = root.findViewById(R.id.sidebarList)
        pageLabel = root.findViewById(R.id.sidebarPageLabel)
        buttonPrev = root.findViewById(R.id.sidebarPrev)
        buttonNext = root.findViewById(R.id.sidebarNext)

        tabPages.setOnClickListener { selectTab(Tab.PAGES) }
        tabBookmarks.setOnClickListener { selectTab(Tab.BOOKMARKS) }
        root.findViewById<View>(R.id.sidebarClose).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.jumpGo).setOnClickListener { jump() }
        jumpInput.setOnEditorActionListener { _, _, _ ->
            jump()
            true
        }
        buttonPrev.setOnClickListener { listPages[tab.ordinal]--; bindList() }
        buttonNext.setOnClickListener { listPages[tab.ordinal]++; bindList() }
        root.findViewById<View>(R.id.sidebarNewPage).setOnClickListener {
            dialog.dismiss()
            actions.newPage()
        }

        previews.onReady = { pageId -> updateThumbs(pageId) }
        // Zeilenzahl aus der gemessenen Höhe der Liste; bei Höhenwechsel (Reiter, erstes Layout) neu aufbauen.
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h != listHeight) {
                listHeight = h
                list.post { if (dialog.isShowing) bindList() }
            }
        }
        applyTab()

        dialog.window?.let { w ->
            val width = max(activity.resources.displayMetrics.widthPixels / 3, dp(240f))
            w.setLayout(width, area.height())
            w.setGravity(Gravity.TOP or Gravity.END)
            w.attributes = w.attributes.also {
                it.x = 0
                it.y = area.top
            }
            w.setWindowAnimations(0)
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun selectTab(newTab: Tab) {
        if (newTab == tab) return
        tab = newTab
        focusCurrent = true
        applyTab()
        bindList()
    }

    private fun applyTab() {
        tabPages.isSelected = tab == Tab.PAGES
        tabBookmarks.isSelected = tab == Tab.BOOKMARKS
        jumpRow.visibility = if (tab == Tab.PAGES) View.VISIBLE else View.GONE
    }

    private fun jump() {
        val index = SidebarLogic.parseJump(jumpInput.text?.toString(), session.pageCount)
        if (index == null) {
            Toast.makeText(activity, activity.getString(R.string.sidebar_jump_error, session.pageCount), Toast.LENGTH_SHORT).show()
            return
        }
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(jumpInput.windowToken, 0)
        dialog.dismiss()
        actions.jump(index)
    }

    /** Höhe der größten Vorschau (nach Seitenverhältnis) samt Rahmen. */
    private fun thumbHeight(page: local.papernotes.model.Page): Int =
        (thumbWidth.toFloat() * page.height / page.width).roundToInt() + 2 * thumbPad

    private fun pageAt(index: Int) = session.note.pages.getValue(session.note.pageIds[index])

    private fun bindList() {
        if (!dialog.isShowing || listHeight <= 0) return
        val count = session.pageCount
        jumpInput.hint = activity.getString(R.string.sidebar_jump_hint, count)
        val items = when (tab) {
            Tab.PAGES -> (0 until count).toList()
            Tab.BOOKMARKS -> SidebarLogic.bookmarkedIndices(List(count) { pageAt(it).bookmarked })
        }
        val rowHeight = (0 until count).maxOf { thumbHeight(pageAt(it)) } + dp(8f)
        val perPage = SidebarLogic.itemsPerListPage(listHeight, rowHeight)
        if (focusCurrent) {
            listPages[tab.ordinal] = SidebarLogic.listPageOf(items.indexOf(session.pageIndex), perPage)
            focusCurrent = false
        }
        val listPageCount = SidebarLogic.listPageCount(items.size, perPage)
        val listPage = Pagination.clamp(listPages[tab.ordinal], listPageCount)
        listPages[tab.ordinal] = listPage

        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(
                TextView(activity).apply {
                    setText(R.string.sidebar_no_bookmarks)
                    setTextColor(activity.getColor(R.color.black))
                    textSize = 16f
                    setPadding(dp(12f), dp(16f), dp(12f), dp(16f))
                },
            )
        }
        for (index in SidebarLogic.slice(items, perPage, listPage)) addRow(index, rowHeight)
        pageLabel.text = Pagination.label(listPage, listPageCount)
        buttonPrev.isEnabled = Pagination.hasPrevious(listPage)
        buttonNext.isEnabled = Pagination.hasNext(listPage, listPageCount)
    }

    private fun addRow(index: Int, rowHeight: Int) {
        val page = pageAt(index)
        val row = LayoutInflater.from(activity).inflate(R.layout.item_sidebar_page, list, false)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        val number = index + 1
        val current = index == session.pageIndex

        val thumb = row.findViewById<ImageView>(R.id.pageThumb)
        thumb.layoutParams = thumb.layoutParams.also {
            it.width = thumbWidth + 2 * thumbPad
            it.height = thumbHeight(page)
        }
        thumb.tag = page.id
        thumb.isSelected = current
        thumb.contentDescription = activity.getString(if (current) R.string.sidebar_page_current else R.string.sidebar_page_label, number)
        val bmp = previews.peek(page, thumbWidth)
        if (bmp != null) thumb.setImageBitmap(bmp) else previews.request(page, thumbWidth, session.note.media)
        thumb.setOnClickListener {
            dialog.dismiss()
            actions.jump(index)
        }

        row.findViewById<TextView>(R.id.pageNumber).apply {
            text = NUMBER_FORMAT.format(number)
            setTypeface(typeface, if (current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        row.findViewById<TextView>(R.id.pageAudio).apply {
            visibility = if (page.audios.isEmpty()) View.GONE else View.VISIBLE
            text = activity.getString(R.string.audio_count, page.audios.size)
            contentDescription = activity.getString(R.string.audio_badge_desc, page.audios.size)
        }
        val bookmark = row.findViewById<ImageButton>(R.id.pageBookmark)
        bookmark.setImageResource(if (page.bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outline)
        bookmark.contentDescription = activity.getString(if (page.bookmarked) R.string.sidebar_bookmark_remove else R.string.sidebar_bookmark_set)
        bookmark.setOnClickListener {
            actions.setBookmark(index, !page.bookmarked)
            bindList()
        }
        row.findViewById<View>(R.id.pageMenu).setOnClickListener { showMenu(it, index) }
        list.addView(row)
    }

    private fun showMenu(anchor: View, index: Int) {
        val page = pageAt(index)
        val menu = PopupMenu(activity, anchor)
        val bookmarkItem = menu.menu.add(if (page.bookmarked) R.string.sidebar_bookmark_remove else R.string.sidebar_bookmark_set)
        val deleteItem = menu.menu.add(R.string.page_delete)
        deleteItem.isEnabled = session.pageCount > 1
        menu.setOnMenuItemClickListener { item ->
            when (item) {
                bookmarkItem -> {
                    actions.setBookmark(index, !page.bookmarked)
                    bindList()
                }
                deleteItem -> confirmDelete(index)
            }
            true
        }
        menu.show()
    }

    private fun confirmDelete(index: Int) {
        if (session.pageCount <= 1) {
            Toast.makeText(activity, R.string.error_last_page, Toast.LENGTH_SHORT).show()
            return
        }
        EinkDialog.confirm(
            activity,
            activity.getString(R.string.sidebar_delete_title),
            activity.getString(R.string.sidebar_delete_message, index + 1),
            activity.getString(R.string.delete),
        ) {
            actions.deletePage(index)
            bindList()
        }
    }

    /** Setzt ein nachgereichtes Vorschaubild in die Zeile der Seite [pageId]. */
    private fun updateThumbs(pageId: String) {
        if (!dialog.isShowing) return
        for (i in 0 until list.childCount) {
            val thumb = list.getChildAt(i).findViewById<ImageView>(R.id.pageThumb) ?: continue
            if (thumb.tag != pageId) continue
            val page = session.note.pages[pageId] ?: continue
            previews.peek(page, thumbWidth)?.let { thumb.setImageBitmap(it) }
        }
    }

    private companion object {
        const val THUMB_DP = 104f
        val NUMBER_FORMAT: java.text.NumberFormat = java.text.NumberFormat.getIntegerInstance()
    }
}
