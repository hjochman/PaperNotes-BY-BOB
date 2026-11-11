package local.papernotes.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import local.papernotes.R
import local.papernotes.storage.NoteSummary

/** Rückrufe der Kacheln an die Startseite. */
internal interface TileActions {
    fun openNote(id: String)
    fun openFolder(path: String)
    fun newQuickNote()
    fun noteMenu(anchor: View, note: NoteSummary)
    fun folderMenu(anchor: View, folder: FolderItem)

    /** Auswahlmodus: Kacheln zeigen ein Auswahlkästchen, ein Tipp setzt oder löst den Haken und öffnet nichts. */
    val selecting: Boolean
    fun isSelected(key: SelectionKey): Boolean

    /** Kehrt den Haken um und liefert den neuen Zustand. */
    fun toggleSelection(key: SelectionKey): Boolean
}

/** Vorschau einer Seite: oben bündig und auf Breite skaliert, weißer Grund, Rand kommt vom Hintergrund-Drawable. */
internal class PreviewView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val inset = Math.round(context.resources.displayMetrics.density)

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(inset, inset, width - inset, height - inset)
        if (dst.width() <= 0 || dst.height() <= 0) return
        val srcHeight = Math.min(b.height, Math.round(b.width.toFloat() * dst.height() / dst.width()))
        src.set(0, 0, b.width, srcHeight)
        canvas.drawBitmap(b, src, dst, paint)
    }
}

/** Verbindet Vorschau-Views mit dem [PreviewLoader]: fehlende Bilder werden angefordert, fertige nur an betroffenen Views gesetzt. */
internal class PreviewBinder(private val loader: PreviewLoader) {
    private class Target(val view: PreviewView, val modified: Long)

    private val targets = HashMap<String, MutableList<Target>>()

    fun bind(view: PreviewView, id: String, modified: Long) {
        val bmp = loader.peek(id, modified)
        view.bitmap = bmp
        targets.getOrPut(id) { ArrayList() }.add(Target(view, modified))
        if (bmp == null) loader.request(id, modified)
    }

    /** Wird im UI-Thread aufgerufen, wenn für [id] ein Bild bereitsteht; aktualisiert nur die zugehörigen Views. */
    fun update(id: String) {
        targets[id]?.forEach { t -> loader.peek(id, t.modified)?.let { t.view.bitmap = it } }
    }

    fun clear() = targets.clear()
}

/** Baut die Zeilen der Startseite (Raster und Liste) aus [Entry]-Objekten. Nur Views, keine Logik. */
internal class HomeTiles(
    private val context: Context,
    private val m: GridMetrics,
    private val binder: PreviewBinder,
    private val dates: DateLabels,
    private val actions: TileActions,
) {
    private val res = context.resources
    private val density = res.displayMetrics.density
    private val black = context.getColor(R.color.black)
    private val gray = context.getColor(R.color.text_secondary)

    private fun dp(v: Float) = Math.round(v * density)

    /** Aktualisierer der Kästchen je Auswahlschlüssel (ein Eintrag kann nur einmal pro Seite vorkommen). */
    private val boxes = HashMap<SelectionKey, MutableList<(Boolean) -> Unit>>()

    /** Setzt die Kästchen von [key] auf [selected] (nach einem Tipp oder "Alle auswählen"). */
    fun setChecked(key: SelectionKey, selected: Boolean) {
        boxes[key]?.forEach { it(selected) }
    }

    /** Setzt alle Kästchen der aktuellen Seite gemäß [actions]. */
    fun refreshChecks() {
        boxes.forEach { (key, list) -> list.forEach { it(actions.isSelected(key)) } }
    }

    fun buildRow(entries: List<Entry>, indices: List<Int>, first: Boolean): View {
        val containsFolder = indices.any { entries[it] is Entry.Folder }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.rowHeight(containsFolder)).also {
            if (first) it.topMargin = m.topPadding
        }
        if (m.columns == 1) {
            row.addView(listRow(entries[indices[0]]), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return row
        }
        row.setPadding(m.sidePadding, 0, m.sidePadding, 0)
        indices.forEachIndexed { pos, i ->
            val e = entries[i]
            val tile = when (e) {
                is Entry.Note -> noteTile(e.summary)
                is Entry.Folder -> folderTile(e.item)
                Entry.NewNote -> newTile()
            }
            val lp = LinearLayout.LayoutParams(m.spanWidth(HomeModel.span(e)), ViewGroup.LayoutParams.WRAP_CONTENT)
            if (pos > 0) lp.marginStart = m.gap
            row.addView(tile, lp)
        }
        return row
    }

    // ---------------------------------------------------------------- Raster

    private fun noteTile(note: NoteSummary): View {
        val key = SelectionKey.Note(note.id)
        val tile = clickableColumn(key) { actions.openNote(note.id) }
        val frame = FrameLayout(context)
        val preview = PreviewView(context).apply {
            background = context.getDrawable(R.drawable.bg_preview)
            isDuplicateParentStateEnabled = true
        }
        binder.bind(preview, note.id, note.modified)
        frame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (note.favorite) frame.addView(starBadge(), badgeParams())
        if (note.audioCount > 0) frame.addView(audioBadge(note.audioCount), audioBadgeParams())
        if (actions.selecting) frame.addView(checkBox(key), checkBoxParams())
        tile.addView(frame, LinearLayout.LayoutParams(m.tileWidth, m.previewHeight))
        tile.addView(
            textRow(m.tileWidth, note.title, dates.format(note.modified), context.getString(R.string.note_menu_desc)) { actions.noteMenu(it, note) },
        )
        return tile
    }

    private fun folderTile(folder: FolderItem): View {
        val key = SelectionKey.Folder(folder.path)
        val tile = clickableColumn(key) { actions.openFolder(folder.path) }
        val box = FrameLayout(context)
        box.addView(
            FolderOutlineView(context).also { it.tabHeight = m.folderTabHeight },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val minis = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        folder.previews.forEachIndexed { idx, n ->
            val mini = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val pv = PreviewView(context).apply { background = context.getDrawable(R.drawable.bg_preview) }
            binder.bind(pv, n.id, n.modified)
            mini.addView(pv, LinearLayout.LayoutParams(m.miniWidth, m.miniHeight))
            mini.addView(
                text(12f, gray, 1).apply { text = n.title },
                LinearLayout.LayoutParams(m.miniWidth, m.miniTitleHeight).also { it.topMargin = dp(4f) },
            )
            minis.addView(mini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                if (idx > 0) it.marginStart = m.miniGap
            })
        }
        box.addView(
            minis,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = m.folderInnerPadding
                it.topMargin = m.folderTabHeight + m.folderInnerPadding
            },
        )
        if (actions.selecting) box.addView(checkBox(key), checkBoxParams())
        tile.addView(box, LinearLayout.LayoutParams(m.folderWidth, m.folderBoxHeight))
        val label = res.getQuantityString(R.plurals.folder_label, folder.noteCount, folder.name, folder.noteCount)
        val date = if (folder.modified > 0) dates.format(folder.modified) else ""
        tile.addView(textRow(m.folderWidth, label, date, context.getString(R.string.folder_menu_desc)) { actions.folderMenu(it, folder) })
        return tile
    }

    private fun newTile(): View {
        val tile = clickableColumn(null) { actions.newQuickNote() }
        val frame = FrameLayout(context).apply { background = context.getDrawable(R.drawable.bg_preview) }
        frame.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.CENTER),
        )
        tile.addView(frame, LinearLayout.LayoutParams(m.tileWidth, m.previewHeight))
        tile.addView(
            text(14f, black, 1).apply {
                text = context.getString(R.string.quick_note)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(m.tileWidth, m.textHeight),
        )
        return tile
    }

    /** Titel (bis 2 Zeilen), Datum und Menüknopf unter einer Kachel. Der Menüknopf liegt über dem rechten Rand des Textes. */
    private fun textRow(width: Int, title: String, date: String, menuDescription: String, onMenu: (View) -> Unit): View {
        val row = FrameLayout(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), dp(40f), 0)
        }
        column.addView(text(14f, black, 2).apply { text = title })
        if (date.isNotEmpty()) column.addView(text(12f, gray, 1).apply { text = date })
        row.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!actions.selecting) row.addView(menuButton(menuDescription, onMenu), FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.TOP or Gravity.END))
        row.layoutParams = LinearLayout.LayoutParams(width, m.textHeight)
        return row
    }

    // ---------------------------------------------------------------- Liste

    private fun listRow(entry: Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.bg_list_row)
            isClickable = true
            isFocusable = true
            setPadding(dp(24f), 0, dp(8f), 0)
        }
        val key: SelectionKey? = entry.selectionKey()
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        when (entry) {
            is Entry.Note -> {
                val n = entry.summary
                row.setOnClickListener { tap(key) { actions.openNote(n.id) } }
                val pv = PreviewView(context).apply { background = context.getDrawable(R.drawable.bg_preview) }
                binder.bind(pv, n.id, n.modified)
                row.addView(pv, LinearLayout.LayoutParams(m.listPreviewWidth, m.listPreviewHeight).also { it.marginEnd = dp(16f) })
                texts.addView(text(16f, black, 2).apply { text = n.title })
                texts.addView(text(14f, gray, 1).apply { text = dates.format(n.modified) })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (n.audioCount > 0) row.addView(audioBadge(n.audioCount), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24f)).also { it.marginEnd = dp(8f) })
                if (n.favorite) row.addView(starBadge(), LinearLayout.LayoutParams(dp(24f), dp(24f)).also { it.marginEnd = dp(8f) })
                if (actions.selecting) addListCheckBox(row, key!!) else row.addView(menuButton(context.getString(R.string.note_menu_desc)) { actions.noteMenu(it, n) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            }
            is Entry.Folder -> {
                val f = entry.item
                row.setOnClickListener { tap(key) { actions.openFolder(f.path) } }
                row.addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_folder)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(m.listPreviewWidth, m.listPreviewWidth).also { it.marginEnd = dp(16f) },
                )
                texts.addView(text(16f, black, 2).apply { text = res.getQuantityString(R.plurals.folder_label, f.noteCount, f.name, f.noteCount) })
                if (f.modified > 0) texts.addView(text(14f, gray, 1).apply { text = dates.format(f.modified) })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (actions.selecting) addListCheckBox(row, key!!) else row.addView(menuButton(context.getString(R.string.folder_menu_desc)) { actions.folderMenu(it, f) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            }
            Entry.NewNote -> {
                row.setOnClickListener { actions.newQuickNote() }
                row.addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_add)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(m.listPreviewWidth, m.listPreviewWidth).also { it.marginEnd = dp(16f) },
                )
                texts.addView(text(16f, black, 1).apply {
                    text = context.getString(R.string.quick_note)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        return row
    }

    // ---------------------------------------------------------------- Bausteine

    /** Im Auswahlmodus setzt oder löst ein Tipp den Haken von [key], sonst läuft [onClick]. */
    private fun tap(key: SelectionKey?, onClick: () -> Unit) {
        if (!actions.selecting) onClick() else if (key != null) setChecked(key, actions.toggleSelection(key))
    }

    private fun clickableColumn(key: SelectionKey?, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { tap(key, onClick) }
    }

    /**
     * Auswahlkästchen. Ein Tipp auf die Kachel ändert nur dieses Symbol (kein Neuaufbau der Seite, wenig E-Ink-Flackern);
     * der Rückruf aktualisiert die Kopfzeile.
     */
    private fun checkBox(key: SelectionKey) = ImageView(context).apply {
        fun show(selected: Boolean) {
            setImageResource(if (selected) R.drawable.ic_check_box else R.drawable.ic_check_box_outline)
            contentDescription = if (selected) context.getString(R.string.sort_selected) else null
        }
        show(actions.isSelected(key))
        boxes.getOrPut(key) { ArrayList() }.add(::show)
    }

    private fun checkBoxParams() = FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.BOTTOM or Gravity.END).also {
        it.setMargins(0, 0, dp(6f), dp(6f))
    }

    private fun addListCheckBox(row: LinearLayout, key: SelectionKey) {
        row.addView(
            checkBox(key),
            LinearLayout.LayoutParams(dp(32f), dp(32f), 0f).also {
                it.gravity = Gravity.BOTTOM
                it.setMargins(0, 0, dp(12f), dp(10f))
            },
        )
    }

    private fun text(sizeSp: Float, color: Int, maxLines: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        if (maxLines == 1) isSingleLine = true
    }

    private fun menuButton(description: String, onClick: (View) -> Unit) =
        ImageButton(context, null, 0, R.style.Widget_PaperNotes_IconButton).apply {
            setImageResource(R.drawable.ic_dots_vertical)
            contentDescription = description
            setOnClickListener { onClick(it) }
        }

    private fun starBadge() = ImageView(context).apply {
        setImageResource(R.drawable.ic_star_filled)
        setBackgroundColor(context.getColor(R.color.white))
        setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        contentDescription = context.getString(R.string.favorite_badge)
    }

    /** Hinweis auf Tonaufnahmen: Lautsprecher in der Größe des Sterns, daneben die Anzahl (B1). */
    private fun audioBadge(count: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.white))
        setPadding(dp(2f), dp(2f), dp(4f), dp(2f))
        contentDescription = context.getString(R.string.audio_badge_desc, count)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_speaker)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // der Container beschreibt das Abzeichen
            },
            LinearLayout.LayoutParams(dp(22f), dp(22f)),
        )
        addView(
            text(14f, black, 1).apply { this.text = context.getString(R.string.audio_count, count) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun audioBadgeParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26f), Gravity.TOP or Gravity.END).also {
        it.setMargins(0, dp(4f), dp(4f), 0)
    }

    private fun badgeParams() = FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.TOP or Gravity.START).also {
        it.setMargins(dp(4f), dp(4f), 0, 0)
    }
}
