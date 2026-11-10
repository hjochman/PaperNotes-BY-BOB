package local.papernotes.editor

import android.app.AlertDialog
import android.content.Context
import android.eink.view.edit.HWEditView
import android.eink.view.edit.IHWEditParam
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.SeekBar
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import local.papernotes.R
import local.papernotes.model.ImageAttachment
import local.papernotes.model.Page
import local.papernotes.model.Stroke
import local.papernotes.model.Textbox
import java.util.UUID

/**
 * Schreibfläche: unten [PageBackgroundView] (Vorlage), darüber transparent und vollflächig die [HWEditView]
 * (belegt in Spike 2). Dünne Schicht über der E-Ink-Klasse; alles Rechnerische liegt in [PageHistory] und [TrackMapper].
 *
 * Annahmen (auf dem Tablet zu prüfen):
 * - `OnEditListener.onEdit` kommt im UI-Thread und nach jeder Änderung der Track-Liste (Strichende, Radieren, Lasso
 *   löschen/ausschneiden/einfügen, erster Lasso-Verschiebeschritt). Weitere Verschiebungen derselben Auswahl melden
 *   laut Gerätebeobachtung KEIN Ereignis, sie werden beim Beenden der Auswahl (`onSelectCancel`) und in [flush] erfasst.
 * - `setTrackList`/`clear` lösen selbst kein `onEdit` aus (`cancelSelection` darin ruft aber `onSelectCancel`). Alle
 *   eigenen Aufrufe laufen deshalb unter dem Reentrancy-Flag [applying]; Ereignisse darin werden ignoriert.
 * - Der Verlauf des Frameworks (`undo()`/`redo()`) wird nicht benutzt. Löst jemand ihn dennoch aus (Typ 1/2), wird das
 *   Ereignis ignoriert.
 * - Der Stift-Pfad bleibt unberührt: Es gibt keine Listener am Touch-Pfad, nur am Strichende.
 * - Die Tracks der `HWEditView` liegen in Ansichts-Pixeln, [Stroke], [PageHistory] und [onChanged] in Seitenkoordinaten.
 *   Dazwischen vermittelt [PageTransform] (Seite füllt die Ansichtsbreite, Versatz 0; bei Seitenbreite = Ansichtsbreite
 *   also 1:1). Zoom und Verschieben ändern später nur [transform] (siehe [updateTransform]).
 * - Die Stiftbreite ([setPen]) gilt in Ansichts-Pixeln, die gespeicherte Breite ist durch den Maßstab geteilt.
 */
class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Tool { PEN, ERASER, LASSO, TEXT, IMAGE, AUDIO }

    /**
     * Radierstil in der Sprache von HiNote ("Standard, Fläche, Striche löschen"). Zuordnung zu den Framework-Werten
     * ist eine Annahme aus dem Spike (0 = Track, 1 = Quick, 2 = Range) und auf dem Gerät zu bestätigen.
     * Literale statt Stub-Konstanten, damit der Enum keine E-Ink-Klasse lädt.
     */
    enum class EraserStyle(internal val frameworkValue: Int) {
        STANDARD(0),
        AREA(2),
        STROKES(1),
    }

    private val background = PageBackgroundView(context)
    private var ink: HWEditView? = null
    private var mapper: TrackMapper? = null
    private var history = PageHistory(emptyList())
    private var applying = false
    private var pageWidth = 0
    private var transform = PageTransform.IDENTITY

    private var tool = Tool.PEN
    private var penType = DEFAULT_PEN_TYPE
    private var penColor = Color.BLACK
    private var penWidth = DEFAULT_PEN_WIDTH
    private var eraserStyle = EraserStyle.STANDARD
    private var eraserRadius = DEFAULT_ERASER_RADIUS

    /** Wird nach jeder erfassten Änderung mit der neuen Strichliste aufgerufen (UI-Thread). */
    var onChanged: ((List<Stroke>) -> Unit)? = null

    /** Wird nach jeder Änderung der Textboxen der gezeigten Seite mit der neuen Liste aufgerufen (UI-Thread). */
    var onTextboxesChanged: ((List<Textbox>) -> Unit)? = null

    /**
     * Wird nach jeder Änderung der Bilder der gezeigten Seite mit der neuen Liste und den dabei neu entstandenen
     * Mediendateien (Name -> Bytes, sonst leer) aufgerufen (UI-Thread).
     */
    var onImagesChanged: ((List<ImageAttachment>, Map<String, ByteArray>) -> Unit)? = null

    /** Wird nur bei Änderung des (gerundeten) Zoomfaktors in Prozent aufgerufen (UI-Thread); 100 = Seite füllt die Breite. */
    var onZoomChanged: ((Int) -> Unit)? = null
    private var zoomPercent = 100

    /** Wird im Bild-Werkzeug bei einem Tippen auf freie Fläche (kein Bild getroffen) aufgerufen: Bildauswahl öffnen. */
    var onRequestImagePick: (() -> Unit)? = null

    /**
     * Wird aufgerufen, wenn das Bearbeiten einer Textbox beginnt (mit dem Eingabefeld) oder endet (`null`). Die Activity
     * verbindet damit die Formatleiste über der Tastatur.
     */
    var onTextEditChanged: ((RichEditText?) -> Unit)? = null
    private var editingView: TextboxView? = null
    private var preEditTransform: PageTransform? = null

    private var textboxes: List<Textbox> = emptyList()
    private val textboxViews = ArrayList<TextboxView>()
    private var images: List<ImageAttachment> = emptyList()
    private var media: Map<String, ByteArray> = emptyMap()
    private val imageViews = ArrayList<ImageAttachmentView>()
    private var imageTouchTarget: ImageAttachmentView? = null
    private var cropOverlay: CropOverlayView? = null
    private var pageHeight = 0
    private var writingSuspended = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var cropJustClosed = false
    private var emptyDownX = 0f
    private var emptyDownY = 0f

    private var lassoClip: List<Stroke> = emptyList()
    private var lassoAnchor: View? = null
    private var lassoTapCandidate = false
    private var lassoDownX = 0f
    private var lassoDownY = 0f

    private var lassoSelectInk = true
    private var lassoSelectText = true
    private var lassoSelectImage = true

    private var isScalingOrPanning = false
    private var initialSpan = 0f
    private var initialFocusX = 0f
    private var initialFocusY = 0f
    private var focusPageX = 0f
    private var focusPageY = 0f
    private var initialScale = 1f
    private var initialOffsetX = 0f
    private var initialOffsetY = 0f

    /** False, wenn `HWEditView` nicht erzeugt werden konnte (z. B. kein Huawei-Gerät). Dann gibt es nur den Hintergrund. */
    val isInkAvailable: Boolean get() = ink != null

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    init {
        addView(background, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            val v = HWEditView(context)
            mapper = TrackMapper(v.getHWTrackCreator())
            configure(v)
            addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ink = v
        } catch (t: Throwable) {
            ink = null
            mapper = null
        }
    }

    private fun configure(v: HWEditView) {
        val fb = v.getFBSurface()
        fb.setAutoHandWrite(true)
        fb.setAhwToolTypes(intArrayOf(TOOL_TYPE_STYLUS))
        fb.setAhwPenType(penType)
        fb.setAhwPenWidth(penWidth)
        fb.setAhwPenColor(penColor)
        v.setSelectToolTypes(intArrayOf(TOOL_TYPE_STYLUS))
        v.setEraseToolTypes(intArrayOf(TOOL_TYPE_STYLUS))

        // Schneller Radierer wie in HiNote: Hintergrund als Weiß binden, sonst läuft der träge Standardpfad (Spike 2).
        // Bei Stil "Fläche" (Range) bindet der Spike nichts. Der Radierkreis fehlt im Schnellpfad (Folgepunkt).
        v.setOnEraserPrepareListener { _, picture ->
            if (eraserStyle != EraserStyle.AREA) picture.bindColor(Color.WHITE)
        }
        installStrokeEraser(v)
        v.setEraserShowBorder(false)
        v.setEraserStyle(eraserStyle.frameworkValue)
        v.setEraserRadius(eraserRadius)

        v.setOnEditListener { _, param ->
            if (param.type == IHWEditParam.TYPE_EDIT) syncHistory()
        }
        // Pflicht: HWEditView ruft den Listener beim Beenden einer Auswahl ohne Null-Prüfung auf.
        v.setOnSelectCompleteListener(object : HWEditView.OnSelectCompleteListener {
            override fun onSelectCancel(view: HWEditView) {
                syncHistory()
            }

            override fun onSelectComlete(view: HWEditView) {
                post { showLassoMenu() }
            }
        })
        v.setState(stateFor(tool))
    }

    /**
     * "Striche löschen": Das Framework kennt nur Teilstück-Radieren (HiNote bildet beide Radierarten auf denselben
     * Framework-Stil ab). Der eigene Abschluss-Listener lässt das Framework schneiden und entfernt danach jeden dabei
     * berührten Original-Strich ganz: Tracks, die nach dem Schnitt nicht mehr (dasselbe Objekt) in der Liste stehen,
     * gelten als berührt. Die Zwischenstände des Frameworks kommen nicht in den Verlauf (`applying`), es entsteht ein
     * einziger Eintrag.
     */
    private fun installStrokeEraser(v: HWEditView) {
        val base = v.getOnEraseCompleteListener()
        v.setOnEraseCompleteListener { view, path ->
            if (eraserStyle != EraserStyle.STROKES) {
                base?.onEraseComlete(view, path)
                return@setOnEraseCompleteListener
            }
            val before = ArrayList<Any?>(v.getTrackList())
            val wasApplying = applying
            applying = true
            try {
                base?.onEraseComlete(view, path)
                val now = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any?, Boolean>())
                now.addAll(v.getTrackList())
                val kept = before.filter { it in now }
                if (kept.size != before.size) v.setTrackList(kept)
            } finally {
                applying = wasApplying
            }
            syncHistory()
        }
    }

    /** Aktionsmenü zur Strich-Auswahl (wie HiNote: Kopieren, Ausschneiden, Löschen), unter der Auswahl. */
    private fun showLassoMenu() {
        val v = ink ?: return
        if (tool != Tool.LASSO) return
        val path = v.getSelectionPath() ?: return
        val b = RectF()
        path.computeBounds(b, true)
        if (b.isEmpty) return
        showMenuAt(b.centerX(), b.bottom) { menu ->
            menu.menu.add(0, MENU_COPY, 0, R.string.lasso_copy)
            menu.menu.add(0, MENU_CUT, 1, R.string.lasso_cut)
            menu.menu.add(0, MENU_DELETE, 2, R.string.delete)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COPY -> lassoCopy(false)
                    MENU_CUT -> lassoCopy(true)
                    MENU_DELETE -> {
                        v.eraseSelection()
                        syncHistory()
                    }
                }
                true
            }
        }
    }

    /** Menü „Einfügen“ an der angetippten Stelle (Ansichtskoordinaten); die Striche werden dort mittig eingefügt. */
    private fun showPasteMenu(x: Float, y: Float) {
        if (tool != Tool.LASSO || lassoClip.isEmpty()) return
        showMenuAt(x, y) { menu ->
            menu.menu.add(0, MENU_PASTE, 0, R.string.lasso_paste)
            menu.setOnMenuItemClickListener {
                lassoPaste(x, y)
                true
            }
        }
    }

    private fun showMenuAt(x: Float, y: Float, fill: (PopupMenu) -> Unit) {
        val anchor = lassoAnchor ?: View(context).also {
            lassoAnchor = it
            addView(it, LayoutParams(1, 1))
        }
        val lp = anchor.layoutParams as LayoutParams
        lp.leftMargin = x.toInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        lp.topMargin = y.toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        anchor.layoutParams = lp
        anchor.post {
            val menu = PopupMenu(context, anchor)
            fill(menu)
            menu.show()
        }
    }

    /** Merkt die ausgewählten Striche in Seitenkoordinaten; bei [cut] werden sie von der Seite genommen. */
    private fun lassoCopy(cut: Boolean) {
        val v = ink ?: return
        val m = mapper ?: return
        val tracks = (if (cut) v.cutSelection() else v.copySelection()) ?: return
        lassoClip = m.fromTracks(tracks)
        if (cut) syncHistory()
    }

    /** Fügt die gemerkten Striche so ein, dass die Mitte ihres Umrisses auf dem Punkt ([viewX], [viewY]) liegt. */
    private fun lassoPaste(viewX: Float, viewY: Float) {
        val v = ink ?: return
        val m = mapper ?: return
        val points = lassoClip.flatMap { it.points }
        if (points.isEmpty()) return
        v.cancelSelection()
        val dx = transform.viewToPageX(viewX) - (points.minOf { it.x } + points.maxOf { it.x }) / 2f
        val dy = transform.viewToPageY(viewY) - (points.minOf { it.y } + points.maxOf { it.y }) / 2f
        val moved = lassoClip.map { s -> s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) }
        v.paste(m.toTracks(moved), null)
        syncHistory()
    }

    /** True, wenn der Punkt (Ansichtskoordinaten) in der aktuellen Strich-Auswahl liegt. */
    private fun selectionContains(x: Float, y: Float): Boolean {
        val path = ink?.getSelectionPath() ?: return false
        val b = RectF()
        path.computeBounds(b, true)
        val region = Region()
        region.setPath(path, Region(b.left.toInt(), b.top.toInt(), b.right.toInt() + 1, b.bottom.toInt() + 1))
        return region.contains(x.toInt(), y.toInt())
    }

    /**
     * Zeigt [page] mit ihrem Verlauf. [noteMedia] sind die Mediendateien der Notiz (für die Bilder der Seite).
     * Der Aufrufer muss vorher [flush] für die bisherige Seite aufgerufen haben.
     */
    fun showPage(page: Page, pageHistory: PageHistory, noteMedia: Map<String, ByteArray> = emptyMap()) {
        stopTextEdit()
        history = pageHistory
        pageWidth = page.width
        pageHeight = page.height
        media = noteMedia
        transform = computeTransform()
        mapper?.transform = transform
        background.setPageGeometry(pageWidth, transform)
        background.setTemplate(page.template)
        applyToInk(pageHistory.strokes)
        textboxes = page.textboxes
        rebuildTextboxViews()
        images = page.images
        rebuildImageViews()
        reportZoom()
    }

    private fun reportZoom() {
        val base = if (width > 0 && pageWidth > 0) width.toFloat() / pageWidth.toFloat() else 1f
        val p = ZoomLogic.percent(transform.scale, base)
        if (p == zoomPercent) return
        zoomPercent = p
        onZoomChanged?.invoke(p)
    }

    /** Abbildung Seite -> Ansicht für die aktuelle Seite; vor dem Layout (Breite 0) die Identität. */
    private fun computeTransform(): PageTransform =
        if (width > 0 && pageWidth > 0) PageTransform.fitWidth(width.toFloat(), pageWidth.toFloat()) else PageTransform.IDENTITY

    /**
     * Übernimmt eine geänderte [transform] (Größenwechsel der Ansicht, später Zoom/Verschieben): offene Änderungen werden
     * noch mit der alten Abbildung erfasst, danach werden die Tracks aus dem Verlauf (Seitenkoordinaten) neu gesetzt.
     */
    fun setTransform(next: PageTransform) {
        if (next == transform) return
        transform = next
        mapper?.transform = next
        background.setPageGeometry(pageWidth, next)
        applyToInk(history.strokes)
        for (v in textboxViews) v.model?.let { v.bind(it, next) }
        for (v in imageViews) v.model?.let { v.bind(it, media[it.filePath], next) }
        reportZoom()
    }

    private fun updateTransform() {
        if (pageWidth <= 0) return
        val next = computeTransform()
        if (next == transform) return
        flush()
        setTransform(next)
    }

    private fun rebuildImageViews() {
        cancelCrop()
        for (v in imageViews) removeView(v)
        imageViews.clear()
        for (img in images) addImageView(img)
    }

    /** Bilder liegen direkt über dem Hintergrund und unter der E-Ink-Schicht, damit Striche darüber sichtbar bleiben. */
    private fun addImageView(img: ImageAttachment) {
        val v = ImageAttachmentView(context)
        v.editable = tool == Tool.IMAGE || (tool == Tool.LASSO && lassoSelectImage)
        v.onGeometryChanged = { view, moved -> replaceImage(view, moved) }
        v.onTap = { view -> showImageMenu(view) }
        v.bind(img, media[img.filePath], transform)
        addView(v, 1 + imageViews.size)
        imageViews.add(v)
    }

    /**
     * Fügt das Bild [bytes] (Datei des Auswahldialogs) mittig auf der Seite ein. False, wenn es kein lesbares Bild ist.
     * Die Datei wird unter einem neuen Namen in die Medien der Notiz übernommen (über [onImagesChanged]).
     */
    fun addImage(bytes: ByteArray): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0) return false
        val (iw, ih) = ImageAttachmentView.sizeOf(bytes) ?: return false
        val (w, h) = ImageUtil.initialSize(iw, ih, pageWidth.toFloat(), pageHeight.toFloat())
        val name = "${UUID.randomUUID()}.${ImageUtil.extensionOf(bytes)}"
        val img = ImageAttachment(
            UUID.randomUUID().toString(), name,
            (pageWidth - w) / 2f, ((pageHeight - h) / 2f).coerceAtLeast(0f), w, h,
        )
        media = media + (name to bytes)
        images = images + img
        addImageView(img)
        onImagesChanged?.invoke(images, mapOf(name to bytes))
        return true
    }

    private fun replaceImage(view: ImageAttachmentView, img: ImageAttachment, added: Map<String, ByteArray> = emptyMap()) {
        images = images.map { if (it.id == img.id) img else it }
        view.bind(img, media[img.filePath], transform)
        onImagesChanged?.invoke(images, added)
    }

    private fun removeImage(view: ImageAttachmentView) {
        val id = view.model?.id ?: return
        images = images.filter { it.id != id }
        removeView(view)
        imageViews.remove(view)
        onImagesChanged?.invoke(images, emptyMap())
    }

    private fun copyImage(view: ImageAttachmentView) {
        val img = view.model ?: return
        val offset = pageWidth * COPY_OFFSET_FRACTION
        val copy = img.copy(id = UUID.randomUUID().toString(), x = img.x + offset, y = img.y + offset)
        images = images + copy
        addImageView(copy)
        onImagesChanged?.invoke(images, emptyMap())
    }

    private fun showImageMenu(view: ImageAttachmentView) {
        val menu = PopupMenu(context, view)
        menu.menu.add(0, MENU_CROP, 0, R.string.image_crop)
        menu.menu.add(0, MENU_COPY, 1, R.string.image_copy)
        menu.menu.add(0, MENU_DELETE, 2, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CROP -> startCrop(view)
                MENU_COPY -> copyImage(view)
                MENU_DELETE -> removeImage(view)
            }
            true
        }
        menu.show()
    }

    /** Legt einen Auswahlrahmen über das Bild; der aufgezogene Ausschnitt wird zum neuen Bild. */
    private fun startCrop(view: ImageAttachmentView) {
        cancelCrop()
        val overlay = CropOverlayView(context) { fractions -> finishCrop(view, fractions) }
        addView(overlay, LayoutParams(view.width, view.height).apply {
            leftMargin = view.left
            topMargin = view.top
        })
        cropOverlay = overlay
        Toast.makeText(context, R.string.image_crop_hint, Toast.LENGTH_SHORT).show()
    }

    private fun cancelCrop() {
        cropOverlay?.let { removeView(it) }
        cropOverlay = null
    }

    private fun finishCrop(view: ImageAttachmentView, f: ImageUtil.Fractions?) {
        // Der Rahmen wird erst nach dem laufenden Touch-Ereignis entfernt.
        post { cancelCrop() }
        if (f == null) return
        val img = view.model ?: return
        val source = media[img.filePath] ?: return
        val (bytes, ext) = ImageAttachmentView.cropBytes(source, f) ?: run {
            Toast.makeText(context, R.string.error_image, Toast.LENGTH_SHORT).show()
            return
        }
        val name = "${UUID.randomUUID()}.$ext"
        media = media + (name to bytes)
        replaceImage(view, ImageUtil.cropped(img, f, name), mapOf(name to bytes))
    }

    private fun rebuildTextboxViews() {
        for (v in textboxViews) removeView(v)
        textboxViews.clear()
        for (tb in textboxes) {
            val v = TextboxView(context)
            v.editable = tool == Tool.TEXT || (tool == Tool.LASSO && lassoSelectText)
            wireTextbox(v)
            v.bind(tb, transform)
            addView(v)
            textboxViews.add(v)
        }
    }

    private fun wireTextbox(v: TextboxView) {
        v.onGeometryChanged = { view, moved -> replaceTextbox(view, moved) }
        // Im Textfeld-Werkzeug bearbeitet ein Tippen die Box direkt auf dem Blatt (wie HiNote); sonst gibt es das Menü.
        v.onTap = { view -> if (tool == Tool.TEXT) startTextEdit(view) else showTextboxMenu(view) }
        v.onLongPress = { view -> showTextboxMenu(view) }
        v.onContentEdited = { _, moved ->
            textboxes = textboxes.map { if (it.id == moved.id) moved else it }
            onTextboxesChanged?.invoke(textboxes)
        }
    }

    /** Beginnt das Bearbeiten von [view] direkt auf dem Blatt; eine andere offene Box wird zuvor beendet. */
    private fun startTextEdit(view: TextboxView) {
        if (editingView === view) return
        stopTextEdit()
        preEditTransform = transform
        val e = view.startEditing { }
        editingView = view
        onTextEditChanged?.invoke(e)
        post { ensureEditingVisible() }
    }

    /** Beendet das Bearbeiten (Tastatur zu, leere Box wird verworfen) und stellt die Ansicht wieder her. */
    fun stopTextEdit() {
        val v = editingView ?: return
        editingView = null
        v.stopEditing()
        onTextEditChanged?.invoke(null)
        if (v.model?.text.isNullOrBlank()) removeTextbox(v)
        val pre = preEditTransform
        preEditTransform = null
        if (pre != null && pre != transform && pre.scale == transform.scale) {
            flush()
            setTransform(pre)
        }
    }

    /** Verschiebt die Seite nach oben, wenn die bearbeitete Box unter der Tastatur (unterer Rand der Ansicht) läge. */
    private fun ensureEditingVisible() {
        val v = editingView ?: return
        val tb = v.model ?: return
        if (height <= 0) return
        val bottom = transform.pageToViewY(tb.y + tb.height)
        val margin = 8 * resources.displayMetrics.density
        val over = bottom + margin - height
        if (over <= 0f) return
        val minOffsetY = height - pageHeight * transform.scale
        val next = (transform.offsetY - over).coerceAtLeast(minOf(minOffsetY, transform.offsetY))
        if (next == transform.offsetY) return
        flush()
        setTransform(PageTransform(transform.scale, transform.offsetX, next))
    }

    private fun replaceTextbox(view: TextboxView, tb: Textbox) {
        textboxes = textboxes.map { if (it.id == tb.id) tb else it }
        view.bind(tb, transform)
        onTextboxesChanged?.invoke(textboxes)
    }

    private fun removeTextbox(view: TextboxView) {
        if (editingView === view) stopTextEdit()
        val id = view.model?.id ?: return
        textboxes = textboxes.filter { it.id != id }
        removeView(view)
        textboxViews.remove(view)
        onTextboxesChanged?.invoke(textboxes)
    }

    private fun showTextboxMenu(view: TextboxView) {
        val tb = view.model ?: return
        val menu = PopupMenu(context, view)
        menu.menu.add(0, MENU_EDIT, 0, R.string.textbox_edit)
        menu.menu.add(0, MENU_FORMAT, 1, R.string.textbox_format)
        menu.menu.add(0, MENU_DELETE, 2, R.string.delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> startTextEdit(view)
                MENU_FORMAT -> formatTextbox(view)
                MENU_DELETE -> removeTextbox(view)
            }
            true
        }
        menu.show()
    }

    private fun formatTextbox(view: TextboxView) {
        val tb = view.model ?: return
        val originalTb = tb

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        // 1. Font Size label & control
        val sizeLabel = TextView(context).apply {
            text = context.getString(R.string.textbox_font_size, tb.fontSize.toInt())
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        root.addView(sizeLabel)

        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnMinus = Button(context).apply {
            text = "-"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            )
        }

        val seekBar = SeekBar(context).apply {
            max = 120 // from 10 to 130
            progress = (tb.fontSize - 10f).toInt().coerceIn(0, 120)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPlus = Button(context).apply {
            text = "+"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            )
        }

        sizeRow.addView(btnMinus)
        sizeRow.addView(seekBar)
        sizeRow.addView(btnPlus)

        root.addView(sizeRow)

        // Spacer
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (16 * resources.displayMetrics.density).toInt())
        })

        // 2. Background Color label & RadioGroup
        val bgLabel = TextView(context).apply {
            text = context.getString(R.string.textbox_bg)
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        root.addView(bgLabel)

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }

        val rbNone = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.textbox_bg_none)
            setTextColor(Color.BLACK)
        }
        radioGroup.addView(rbNone)

        val rbWhite = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.textbox_bg_white)
            setTextColor(Color.BLACK)
        }
        radioGroup.addView(rbWhite)

        val rbGray = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.textbox_bg_gray)
            setTextColor(Color.BLACK)
        }
        radioGroup.addView(rbGray)

        when (tb.bgColor) {
            Color.WHITE -> rbWhite.isChecked = true
            0xFFF5F5F5.toInt() -> rbGray.isChecked = true
            else -> rbNone.isChecked = true
        }

        root.addView(radioGroup)

        var currentSize = tb.fontSize
        var currentBg = tb.bgColor

        fun updatePreview() {
            val updated = view.model?.copy(fontSize = currentSize, bgColor = currentBg) ?: return
            replaceTextbox(view, updated)
            sizeLabel.text = context.getString(R.string.textbox_font_size, currentSize.toInt())
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentSize = (progress + 10).toFloat()
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnMinus.setOnClickListener {
            if (currentSize > 10f) {
                currentSize -= 1f
                seekBar.progress = (currentSize - 10f).toInt()
                updatePreview()
            }
        }

        btnPlus.setOnClickListener {
            if (currentSize < 130f) {
                currentSize += 1f
                seekBar.progress = (currentSize - 10f).toInt()
                updatePreview()
            }
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentBg = when (checkedId) {
                rbWhite.id -> Color.WHITE
                rbGray.id -> 0xFFF5F5F5.toInt()
                else -> 0
            }
            updatePreview()
        }

        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> replaceTextbox(view, originalTb) }
            .setOnCancelListener { replaceTextbox(view, originalTb) }
            .show()
    }

    private fun addTextboxAt(viewX: Float, viewY: Float) {
        if (pageWidth <= 0) return
        val w = pageWidth * NEW_WIDTH_FRACTION
        val h = pageWidth * NEW_HEIGHT_FRACTION
        val x = transform.viewToPageX(viewX).coerceIn(0f, (pageWidth - w).coerceAtLeast(0f))
        val y = transform.viewToPageY(viewY).coerceAtLeast(0f)
        val tb = Textbox(UUID.randomUUID().toString(), "", x, y, w, h, pageWidth * NEW_FONT_FRACTION)
        textboxes = textboxes + tb
        val v = TextboxView(context)
        v.editable = true
        wireTextbox(v)
        v.bind(tb, transform)
        addView(v)
        textboxViews.add(v)
        startTextEdit(v)
    }

    private fun hitsTextbox(x: Float, y: Float): Boolean =
        textboxViews.any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /**
     * Im Textfeld-Werkzeug: Berührungen auf leerer Fläche gehören der Ansicht (neue Textbox), nicht der E-Ink-Schicht.
     * Im Bild-Werkzeug gehören alle Berührungen der Ansicht (die Bilder liegen unter der E-Ink-Schicht), außer dem
     * Auswahlrahmen beim Zuschneiden.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (tool == Tool.IMAGE) {
            val overlay = cropOverlay
            if (overlay != null) {
                if (ev.x >= overlay.left && ev.x <= overlay.right && ev.y >= overlay.top && ev.y <= overlay.bottom) return false
                cancelCrop()
                cropJustClosed = true
            }
            return true
        }
        if (tool != Tool.TEXT) return false
        return !hitsTextbox(ev.x, ev.y)
    }

    /**
     * Reicht Berührungen an das oberste getroffene Bild weiter (Verschieben, Größe, Tippen). Ein Tippen auf freie Fläche
     * öffnet die Bildauswahl (wie beim Textfeld-Werkzeug eine neue Textbox), außer es hat nur den Zuschneiderahmen geschlossen.
     */
    private fun handleImageTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            imageTouchTarget = imageViews.lastOrNull { it.contains(event.x, event.y) }
            emptyDownX = event.x
            emptyDownY = event.y
        }
        val target = imageTouchTarget
        if (target == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP && !cropJustClosed) {
                val dx = event.x - emptyDownX
                val dy = event.y - emptyDownY
                if (dx * dx + dy * dy <= touchSlop * touchSlop) onRequestImagePick?.invoke()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cropJustClosed = false
            return true
        }
        val e = MotionEvent.obtain(event)
        e.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        target.dispatchTouchEvent(e)
        e.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            imageTouchTarget = null
        }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Log.d("PageCanvasView", "dispatchTouchEvent: action=${ev.actionMasked}, pointers=${ev.pointerCount}, tool=${tool}")
        val isFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

        // Multi-Touch Geste für Zoom und Verschieben (Pan)
        if (ev.pointerCount >= 2) {
            if (!isScalingOrPanning) {
                isScalingOrPanning = true
                suspendWriting(true)
                flush()

                val p1x = ev.getX(0)
                val p1y = ev.getY(0)
                val p2x = ev.getX(1)
                val p2y = ev.getY(1)

                val dx = p1x - p2x
                val dy = p1y - p2y
                initialSpan = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(10f)

                initialFocusX = (p1x + p2x) / 2f
                initialFocusY = (p1y + p2y) / 2f

                focusPageX = transform.viewToPageX(initialFocusX)
                focusPageY = transform.viewToPageY(initialFocusY)

                initialScale = transform.scale
                initialOffsetX = transform.offsetX
                initialOffsetY = transform.offsetY
            }

            if (ev.actionMasked == MotionEvent.ACTION_MOVE && isScalingOrPanning) {
                val p1x = ev.getX(0)
                val p1y = ev.getY(0)
                val p2x = ev.getX(1)
                val p2y = ev.getY(1)

                val dx = p1x - p2x
                val dy = p1y - p2y
                val currentSpan = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(10f)

                val currentFocusX = (p1x + p2x) / 2f
                val currentFocusY = (p1y + p2y) / 2f

                val baseScale = if (width > 0 && pageWidth > 0) width.toFloat() / pageWidth.toFloat() else 1f
                val rawScale = initialScale * (currentSpan / initialSpan)

                var nextScale = rawScale.coerceIn(baseScale, baseScale * 5f)
                var nextOffsetX = currentFocusX - focusPageX * nextScale
                var nextOffsetY = currentFocusY - focusPageY * nextScale

                if (nextScale <= baseScale) {
                    nextScale = baseScale
                    nextOffsetX = 0f
                    if (pageHeight * nextScale < height) {
                        nextOffsetY = (height - pageHeight * nextScale) / 2f
                    } else {
                        nextOffsetY = nextOffsetY.coerceIn(height - pageHeight * nextScale, 0f)
                    }
                } else {
                    nextOffsetX = nextOffsetX.coerceIn(width - pageWidth * nextScale, 0f)
                    if (pageHeight * nextScale < height) {
                        nextOffsetY = (height - pageHeight * nextScale) / 2f
                    } else {
                        nextOffsetY = nextOffsetY.coerceIn(height - pageHeight * nextScale, 0f)
                    }
                }

                setTransform(PageTransform(nextScale, nextOffsetX, nextOffsetY))
            }
            return true
        }

        // Wenn die Zoom-Geste aktiv war und jetzt Finger gehoben werden
        if (isScalingOrPanning && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL || ev.pointerCount < 2)) {
            isScalingOrPanning = false
            suspendWriting(false)
            invalidate()
            return true
        }

        // Tippen mit dem Stift: in die Auswahl öffnet das Aktionsmenü erneut, an eine andere Stelle bietet „Einfügen“ an
        // (falls etwas kopiert wurde).
        if (tool == Tool.LASSO && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lassoTapCandidate = true
                    lassoDownX = ev.x
                    lassoDownY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lassoDownX
                    val dy = ev.y - lassoDownY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) lassoTapCandidate = false
                }
                MotionEvent.ACTION_UP -> {
                    if (lassoTapCandidate) {
                        val x = ev.x
                        val y = ev.y
                        if (selectionContains(x, y)) post { showLassoMenu() } else post { showPasteMenu(x, y) }
                    }
                    lassoTapCandidate = false
                }
            }
        }

        // Standard-Ereignisse an Kinder weiterleiten
        val handled = super.dispatchTouchEvent(ev)

        // Wenn es ein Finger-Touch ACTION_DOWN ist, den wir an Kinder weitergeleitet haben und der nicht konsumiert wurde:
        // Wir konsumieren ihn selbst (geben true zurück), damit wir zukünftige ACTION_MOVE und ACTION_POINTER_DOWN Events erhalten!
        if (isFinger && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            return true
        }

        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == Tool.IMAGE) return handleImageTouch(event)
        if (tool != Tool.TEXT) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                emptyDownX = event.x
                emptyDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - emptyDownX
                val dy = event.y - emptyDownY
                if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                    // Tippen neben die bearbeitete Box beendet das Bearbeiten, sonst entsteht eine neue Box.
                    if (editingView != null) stopTextEdit() else addTextboxAt(event.x, event.y)
                }
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Öffnet oder schließt sich die Tastatur beim Bearbeiten, ändert sich nur die Höhe: Zoom bleibt, die Box wird sichtbar.
        if (editingView != null && w == oldw) {
            post { ensureEditingVisible() }
            return
        }
        updateTransform()
    }

    /** Wechselt nur den Hintergrund der gezeigten Seite; die Striche bleiben unberührt. */
    fun setTemplate(id: String) = background.setTemplate(id)

    /** Erfasst ausstehende Änderungen (z. B. verschobene Lasso-Auswahl) im Verlauf. */
    fun flush() {
        val v = ink ?: return
        v.cancelSelection()
        syncHistory()
    }

    /** Liest die Strichliste der Ansicht zurück (`getTrackList()`) und gibt den Stand des Verlaufs zurück. */
    fun currentStrokes(): List<Stroke> {
        syncHistory()
        return history.strokes
    }

    fun setTool(newTool: Tool) {
        if (newTool == tool) return
        stopTextEdit()
        tool = newTool
        ink?.setState(stateFor(newTool))
        updateWriting()
        updateEditableStates()
        if (newTool != Tool.IMAGE) cancelCrop()
    }

    fun setLassoFilters(selectInk: Boolean, selectText: Boolean, selectImage: Boolean) {
        lassoSelectInk = selectInk
        lassoSelectText = selectText
        lassoSelectImage = selectImage
        updateLassoSelectTypes()
        updateEditableStates()
    }

    private fun updateLassoSelectTypes() {
        val v = ink ?: return
        if (lassoSelectInk) {
            v.setSelectToolTypes(intArrayOf(TOOL_TYPE_STYLUS))
        } else {
            // Die echte Huawei HWEditView wirft eine IllegalArgumentException bei leeren Arrays.
            // Wir übergeben ein Array mit einem ungültigen/inaktiven Typ (99), damit die Länge > 0 ist,
            // der Stift (Typ 2) aber keine Striche auswählen kann.
            v.setSelectToolTypes(intArrayOf(99))
        }
    }

    private fun updateEditableStates() {
        val isTextMode = tool == Tool.TEXT
        val isImageMode = tool == Tool.IMAGE
        val isLassoMode = tool == Tool.LASSO

        for (v in textboxViews) {
            v.editable = isTextMode || (isLassoMode && lassoSelectText)
        }
        for (v in imageViews) {
            v.editable = isImageMode || (isLassoMode && lassoSelectImage)
        }
    }

    /** Natives Schreiben nur, wenn nichts darüberliegt und nicht das Textfeld-, Bild- oder Audio-Werkzeug aktiv ist. */
    private fun updateWriting() {
        ink?.getFBSurface()?.setAutoHandWrite(
            !writingSuspended && tool != Tool.TEXT && tool != Tool.IMAGE && tool != Tool.AUDIO,
        )
    }

    fun setPen(type: Int, color: Int, width: Float) {
        penType = type
        penColor = color
        penWidth = width
        val fb = ink?.getFBSurface() ?: return
        fb.setAhwPenType(type)
        fb.setAhwPenColor(color)
        fb.setAhwPenWidth(width)
    }

    fun setEraser(style: EraserStyle, radius: Int) {
        eraserStyle = style
        eraserRadius = radius
        val v = ink ?: return
        v.setEraserStyle(style.frameworkValue)
        v.setEraserRadius(radius)
    }

    /**
     * Nimmt einen Bereich (Bildschirmkoordinaten, z. B. die Stift-Leiste als Popup-Fenster) vom nativen Schreiben aus.
     * Sonst wertet die E-Ink-Schicht Stiftberührungen dort als Striche und die Knöpfe bleiben unbedienbar. Wie HiNote:
     * erlaubt ist die Fläche der Ansicht abzüglich des Fensters. `null` hebt die Ausnahme auf.
     */
    fun setWriteExclusion(screenRect: Rect?) {
        val v = ink ?: return
        val clip = if (screenRect == null || width == 0 || height == 0) {
            null
        } else {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val hole = Path().apply {
                addRect(
                    (screenRect.left - loc[0]).toFloat(), (screenRect.top - loc[1]).toFloat(),
                    (screenRect.right - loc[0]).toFloat(), (screenRect.bottom - loc[1]).toFloat(), Path.Direction.CW,
                )
            }
            Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                op(hole, Path.Op.DIFFERENCE)
            }
        }
        v.getFBSurface().setClip(clip)
    }

    /**
     * Sperrt das native Schreiben ganz, solange ein Dialog oder Menü offen ist (deren Fenster liegen über dem Blatt;
     * der Stift würde sonst dort schreiben, statt die Knöpfe zu bedienen).
     */
    fun suspendWriting(suspended: Boolean) {
        writingSuspended = suspended
        updateWriting()
    }

    fun undo(): Boolean {
        flush()
        val strokes = history.undo() ?: return false
        applyToInk(strokes)
        onChanged?.invoke(strokes)
        return true
    }

    fun redo(): Boolean {
        flush()
        val strokes = history.redo() ?: return false
        applyToInk(strokes)
        onChanged?.invoke(strokes)
        return true
    }

    private fun syncHistory() {
        val v = ink ?: return
        val m = mapper ?: return
        if (applying) return
        if (history.record(m.fromTracks(v.getTrackList()))) onChanged?.invoke(history.strokes)
    }

    private fun applyToInk(strokes: List<Stroke>) {
        val v = ink ?: return
        val m = mapper ?: return
        applying = true
        try {
            val tracks = m.toTracks(strokes)
            v.setTrackList(tracks)
            // Wie im Spike 2 bei leerer Liste: clear() stellt sicher, dass alte Striche vom Bildschirm verschwinden.
            if (tracks.isEmpty()) v.clear()
        } finally {
            applying = false
        }
    }

    private fun stateFor(t: Tool): Int = when (t) {
        Tool.PEN -> STATE_HANDWRITE
        Tool.ERASER -> STATE_ERASE
        Tool.LASSO -> STATE_SELECT
        Tool.TEXT -> STATE_HANDWRITE
        Tool.IMAGE -> STATE_HANDWRITE
        Tool.AUDIO -> STATE_HANDWRITE
    }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 4
        const val MENU_CROP = 5
        const val MENU_COPY = 6
        const val MENU_FORMAT = 7
        const val MENU_CUT = 8
        const val MENU_PASTE = 9
        const val COPY_OFFSET_FRACTION = 0.03f
        const val NEW_WIDTH_FRACTION = 0.5f
        const val NEW_HEIGHT_FRACTION = 0.1f
        const val NEW_FONT_FRACTION = 0.03f
        // Werte der Framework-Klassen HWEraserView/HWEditView (Stub: android.eink.view). Literale, damit der Companion keine E-Ink-Klasse lädt.
        const val STATE_HANDWRITE = 1
        const val STATE_ERASE = 2
        const val STATE_SELECT = 3
        const val TOOL_TYPE_STYLUS = 2
        const val DEFAULT_PEN_TYPE = 2
        const val DEFAULT_PEN_WIDTH = 4f
        const val DEFAULT_ERASER_RADIUS = 20
    }
}
