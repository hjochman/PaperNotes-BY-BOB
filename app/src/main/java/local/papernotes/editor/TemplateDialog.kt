package local.papernotes.editor

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import local.papernotes.R
import local.papernotes.editor.templates.TemplateThumbnails
import kotlin.math.max
import kotlin.math.min

/**
 * Dialog "Vorlage wechseln": je Gruppe eine Zeile mit Pfeilen und Vorschaukacheln. Gewählt wird erst mit "Bestätigen".
 * Blättern geschieht nur per Pfeil (kein Scrollen/Wischen, wie bei E-Ink üblich). Der Aufrufer koppelt Öffnen und
 * Schließen an `overlayOpened()/overlayClosed()` der Activity (sonst schreibt der Stift beim Antippen); dafür gibt
 * es [onDismiss]. Keine Animation, kein Schatten.
 */
class TemplateDialog(
    private val activity: Activity,
    currentTemplate: String,
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val onConfirm: (template: String, allPages: Boolean) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_PaperNotes_TemplateDialog)
    private var selected = TemplateCatalog.lookup(currentTemplate).id
    private val density = activity.resources.displayMetrics.density
    private val rows = ArrayList<Row>()
    private var thumbW = 0
    private var thumbH = 0
    private var tileW = 0
    private var tilesPerPage = MIN_TILES_PER_PAGE

    /** Wird nach jedem Schließen (Abbrechen, Bestätigen, Zurück) aufgerufen. */
    var onDismiss: (() -> Unit)? = null

    private class Row(val group: TemplateGroup, val tilesView: LinearLayout, val prev: View, val next: View) {
        val items = TemplateCatalog.inGroup(group)
        var page = 0
    }

    private fun dp(v: Float) = (v * density).toInt()

    @Suppress("InflateParams") // Dialog-Inhalt hat kein Elternelement.
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_template, null)
        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss?.invoke() }

        val dm = activity.resources.displayMetrics
        val dialogW = dm.widthPixels - dp(24f)
        // Mindestens vier Kacheln pro Zeile (mehr, wenn die Breite reicht); Höhe so, dass alle Gruppen samt Titel,
        // Checkbox und Knöpfen auf den Bildschirm passen.
        val rowCount = TemplateCatalog.groups.size
        val chrome = dp(48f + 1f + 48f + 1f + 56f + 8f) + rowCount * dp(6f + 18f + 6f + 40f)
        val maxTileH = ((dm.heightPixels * 0.94f - chrome) / rowCount).toInt()
        val rowW = dialogW - dp(16f) - 2 * dp(32f)
        val gap = dp(8f)
        val widthByRow = (rowW / MIN_TILES_PER_PAGE) - gap
        val aspect = pageWidth.toFloat() / pageHeight
        val imgH = min(maxTileH, (widthByRow / aspect).toInt())
        val imgW = (imgH * aspect).toInt()
        thumbW = max(1, imgW - 2 * dp(3f))
        thumbH = max(1, imgH - 2 * dp(3f))
        tileW = imgW + gap
        tilesPerPage = max(MIN_TILES_PER_PAGE, rowW / max(1, tileW))

        val groupsView = root.findViewById<LinearLayout>(R.id.templateGroups)
        for (group in TemplateCatalog.groups) {
            val rowView = LayoutInflater.from(activity).inflate(R.layout.row_template_group, groupsView, false)
            rowView.findViewById<TextView>(R.id.groupName).text = activity.getString(groupName(group))
            val row = Row(
                group, rowView.findViewById(R.id.tiles), rowView.findViewById(R.id.arrowPrev), rowView.findViewById(R.id.arrowNext),
            )
            row.prev.setOnClickListener { row.page--; bindRow(row) }
            row.next.setOnClickListener { row.page++; bindRow(row) }
            // Die Seite mit der aktuellen Vorlage zuerst zeigen.
            val idx = row.items.indexOfFirst { it.id == selected }
            if (idx >= 0) row.page = idx / tilesPerPage
            rows.add(row)
            groupsView.addView(rowView)
            bindRow(row)
        }

        val allPages = root.findViewById<CheckBox>(R.id.templateAllPages)
        root.findViewById<View>(R.id.templateCancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.templateConfirm).setOnClickListener {
            val choice = selected
            val all = allPages.isChecked
            dialog.dismiss()
            onConfirm(choice, all)
        }

        // Vorschauen: bereits fertige sofort, übrige im Hintergrund erzeugen und nachreichen.
        TemplateThumbnails.prepareAll(activity, thumbW, thumbH, pageWidth, pageHeight) { id ->
            if (dialog.isShowing) refreshThumb(id)
        }

        dialog.window?.let { w ->
            w.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setWindowAnimations(0)
        }
        dialog.show()
    }

    private fun bindRow(row: Row) {
        val pages = (row.items.size + tilesPerPage - 1) / tilesPerPage
        row.page = row.page.coerceIn(0, max(0, pages - 1))
        row.prev.visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        row.next.visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        row.prev.isEnabled = row.page > 0
        row.next.isEnabled = row.page < pages - 1
        row.tilesView.removeAllViews()
        val from = row.page * tilesPerPage
        val inflater = LayoutInflater.from(activity)
        for (spec in row.items.drop(from).take(tilesPerPage)) {
            val tile = inflater.inflate(R.layout.item_template_tile, row.tilesView, false)
            tile.layoutParams = LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT)
            val name = activity.getString(templateName(spec.id))
            val image = tile.findViewById<ImageView>(R.id.tileImage)
            image.layoutParams = image.layoutParams.also {
                it.width = tileW - dp(8f)
                it.height = thumbH + 2 * dp(3f)
            }
            tile.findViewById<TextView>(R.id.tileName).text = name
            tile.contentDescription = name
            tile.tag = spec.id
            image.setImageBitmap(TemplateThumbnails.peek(spec.id, thumbW, thumbH, pageWidth, pageHeight))
            tile.setOnClickListener { select(spec.id) }
            row.tilesView.addView(tile)
        }
        applySelection()
    }

    private fun select(id: String) {
        if (id == selected) return
        selected = id
        applySelection()
    }

    private fun applySelection() {
        for (row in rows) for (i in 0 until row.tilesView.childCount) {
            val tile = row.tilesView.getChildAt(i)
            val on = tile.tag == selected
            tile.findViewById<View>(R.id.tileImage).isSelected = on
            tile.findViewById<View>(R.id.tileCheck).visibility = if (on) View.VISIBLE else View.GONE
            tile.isSelected = on
        }
    }

    private fun refreshThumb(id: String) {
        val bmp = TemplateThumbnails.peek(id, thumbW, thumbH, pageWidth, pageHeight) ?: return
        for (row in rows) for (i in 0 until row.tilesView.childCount) {
            val tile = row.tilesView.getChildAt(i)
            if (tile.tag == id) tile.findViewById<ImageView>(R.id.tileImage).setImageBitmap(bmp)
        }
    }

    private fun groupName(group: TemplateGroup): Int = when (group) {
        TemplateGroup.BASIC -> R.string.tpl_group_basic
        TemplateGroup.WRITING -> R.string.tpl_group_writing
        TemplateGroup.PLANS -> R.string.tpl_group_plans
        TemplateGroup.WORK -> R.string.tpl_group_work
    }

    companion object {
        private const val MIN_TILES_PER_PAGE = 4

        /** Anzeigename der Vorlage [id] (Ressource). */
        fun templateName(id: String): Int = when (id) {
            "lines_wide" -> R.string.tpl_lines_wide
            "lines_narrow" -> R.string.tpl_lines_narrow
            "dotted" -> R.string.tpl_dotted
            "handwriting" -> R.string.tpl_handwriting
            "millimeter" -> R.string.tpl_millimeter
            "grid" -> R.string.tpl_grid
            "letter" -> R.string.tpl_letter
            "todo" -> R.string.tpl_todo
            "meeting" -> R.string.tpl_meeting
            "weekly" -> R.string.tpl_weekly
            "monthly" -> R.string.tpl_monthly
            "tasks" -> R.string.tpl_tasks
            "error_log" -> R.string.tpl_error_log
            "reading_notes" -> R.string.tpl_reading_notes
            "cornell" -> R.string.tpl_cornell
            "character_grid" -> R.string.tpl_character_grid
            "staff" -> R.string.tpl_staff
            "time_management" -> R.string.tpl_time_management
            "point_sheet" -> R.string.tpl_point_sheet
            "swot" -> R.string.tpl_swot
            else -> R.string.tpl_blank
        }
    }
}
