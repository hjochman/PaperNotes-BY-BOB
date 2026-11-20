package local.papernotes.editor

/** Austauschbarer Speicher für Werkzeug-Einstellungen (Android: SharedPreferences, Tests: Map). */
interface ToolSettingsStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** Stiftfarben der Leiste (Schwarz, Grau, Weiß). Werte wie `HandPenConfig` des Frameworks. */
enum class PenColor(val argb: Int) {
    BLACK(0xFF000000.toInt()),
    GRAY(0xFF505050.toInt()),
    WHITE(0xFFFFFFFF.toInt()),
}

/**
 * Reiner Zustand der Werkzeugleisten (kein Android-Bezug, JVM-testbar): aktives Werkzeug, ob die Unterleiste offen
 * ist, Stift- und Radierereinstellungen. Stift- und Radierereinstellungen werden über [ToolSettingsStore] gemerkt.
 */
class EditorToolState(private val store: ToolSettingsStore) {

    var tool: PageCanvasView.Tool = PageCanvasView.Tool.PEN
        private set

    /** Unterleiste (Stift/Radierer/Lasso) ausgeklappt? */
    var barOpen: Boolean = false
        private set

    /** Festgehalten (Pinnnadel): Die Leiste klappt dann nach dem Schreiben nicht automatisch ein. */
    var barPinned: Boolean = false
        private set

    val barVisible: Boolean get() = barOpen

    /** Kompakte Ansicht: Kopfzeile entfällt, ihre Knöpfe sitzen in der Werkzeugzeile. Wird gemerkt. */
    var compact: Boolean = false
        private set

    var lassoSelectInk: Boolean = true
        private set
    var lassoSelectText: Boolean = true
        private set
    var lassoSelectImage: Boolean = true
        private set

    var pen: PenSpec = PenCatalog.default
        private set
    var penColor: PenColor = PenColor.BLACK
        private set
    var eraserStyle: PageCanvasView.EraserStyle = PageCanvasView.EraserStyle.STANDARD
        private set
    var eraserRadius: Int = DEFAULT_ERASER_RADIUS
        private set

    /** Einstellungen der Stift-Geste (Tipp/Halten der Stift-Taste), über [ToolSettingsStore] gemerkt. */
    var stylusGesture: StylusGestureConfig = StylusGestureConfig()
        private set

    private val penWidths = HashMap<String, Int>()

    init {
        val typeValue = store.getInt(KEY_PEN_TYPE, PenCatalog.default.frameworkType)
        pen = PenCatalog.byFrameworkType(typeValue) ?: PenCatalog.default
        val colorValue = store.getInt(KEY_PEN_COLOR, PenColor.BLACK.argb)
        penColor = PenColor.values().firstOrNull { it.argb == colorValue } ?: PenColor.BLACK
        for (spec in PenCatalog.pens) {
            penWidths[spec.id] = spec.clampWidth(store.getInt(KEY_PEN_WIDTH + spec.id, spec.defaultWidth))
        }
        barPinned = store.getInt(KEY_BAR_PINNED, 0) != 0
        compact = store.getInt(KEY_COMPACT, 0) != 0
        barOpen = barPinned // Ohne Pin startet der Editor mit freiem Blatt.
        eraserStyle = styleOf(store.getInt(KEY_ERASER_STYLE, 0))
        eraserRadius = store.getInt(KEY_ERASER_RADIUS, DEFAULT_ERASER_RADIUS)
            .coerceIn(MIN_ERASER_RADIUS, MAX_ERASER_RADIUS)

        lassoSelectInk = store.getInt(KEY_LASSO_INK, 1) != 0
        lassoSelectText = store.getInt(KEY_LASSO_TEXT, 1) != 0
        lassoSelectImage = store.getInt(KEY_LASSO_IMAGE, 1) != 0

        stylusGesture = StylusGestureConfig(
            enabled = store.getInt(KEY_STYLUS_ENABLED, 1) != 0,
            speed = StylusTapSpeed.fromCode(store.getInt(KEY_STYLUS_SPEED, StylusTapSpeed.NORMAL.code)),
            burstProtection = store.getInt(KEY_STYLUS_BURST_PROTECTION, 1) != 0,
            autoReturnDelayMs = store.getInt(KEY_STYLUS_AUTO_RETURN, StylusGestureConfig.DEFAULT_AUTO_RETURN_DELAY_MS.toInt()).toLong(),
            cooldownMs = store.getInt(KEY_STYLUS_COOLDOWN, StylusGestureConfig.DEFAULT_COOLDOWN_MS.toInt())
                .toLong()
                .coerceIn(StylusGestureConfig.MIN_COOLDOWN_MS, StylusGestureConfig.MAX_COOLDOWN_MS),
        )
    }

    val penWidth: Int get() = penWidths.getValue(pen.id)

    /**
     * Wählt ein Werkzeug. Tippen auf das bereits aktive Werkzeug klappt dessen Unterleiste ein bzw. aus (wie HiNote);
     * ein Wechsel klappt sie aus.
     */
    fun selectTool(newTool: PageCanvasView.Tool) {
        if (newTool == tool) {
            barOpen = !barOpen
        } else {
            tool = newTool
            barOpen = true
        }
    }

    fun setLassoSelectInk(value: Boolean) {
        lassoSelectInk = value
        store.putInt(KEY_LASSO_INK, if (value) 1 else 0)
    }

    fun setLassoSelectText(value: Boolean) {
        lassoSelectText = value
        store.putInt(KEY_LASSO_TEXT, if (value) 1 else 0)
    }

    fun setLassoSelectImage(value: Boolean) {
        lassoSelectImage = value
        store.putInt(KEY_LASSO_IMAGE, if (value) 1 else 0)
    }

    /** Schaltet zwischen normaler und kompakter Ansicht um. */
    fun toggleCompact() {
        compact = !compact
        store.putInt(KEY_COMPACT, if (compact) 1 else 0)
    }

    /** Pinnnadel umschalten. Festhalten öffnet die Leiste zugleich. */
    fun togglePinned() {
        barPinned = !barPinned
        store.putInt(KEY_BAR_PINNED, if (barPinned) 1 else 0)
        if (barPinned) barOpen = true
    }

    /** Nach einem Strich, Radieren oder Lasso-Änderung: Leiste einklappen, außer sie ist festgehalten. */
    fun onContentChanged() {
        if (!barPinned) barOpen = false
    }

    fun selectPen(spec: PenSpec) {
        pen = spec
        store.putInt(KEY_PEN_TYPE, spec.frameworkType)
    }

    fun selectColor(color: PenColor) {
        penColor = color
        store.putInt(KEY_PEN_COLOR, color.argb)
    }

    /** Setzt die Breite des aktuellen Stifts (auf dessen Grenzen begrenzt). */
    fun setPenWidth(width: Int) {
        val w = pen.clampWidth(width)
        penWidths[pen.id] = w
        store.putInt(KEY_PEN_WIDTH + pen.id, w)
    }

    fun stepPenWidth(delta: Int) = setPenWidth(penWidth + delta)

    fun selectEraserStyle(style: PageCanvasView.EraserStyle) {
        eraserStyle = style
        store.putInt(KEY_ERASER_STYLE, codeOf(style))
    }

    fun setEraserRadius(radius: Int) {
        eraserRadius = radius.coerceIn(MIN_ERASER_RADIUS, MAX_ERASER_RADIUS)
        store.putInt(KEY_ERASER_RADIUS, eraserRadius)
    }

    fun stepEraserRadius(delta: Int) = setEraserRadius(eraserRadius + delta)

    /** Aktueller Stiftzustand (Stift, Breite, Farbe) zum späteren Wiederherstellen. */
    fun penSnapshot(): PenSnapshot = PenSnapshot(pen, penWidth, penColor)

    /** Stellt einen gemerkten Stiftzustand wieder her und wählt das Stift-Werkzeug, ohne die Unterleiste zu verändern. */
    fun restorePen(snapshot: PenSnapshot) {
        selectPen(snapshot.pen)
        setPenWidth(snapshot.width)
        selectColor(snapshot.color)
        tool = PageCanvasView.Tool.PEN
    }

    /** Wählt den Radierer ohne die Unterleiste zu verändern (für die Stift-Geste). */
    fun switchToEraserKeepingBar() {
        tool = PageCanvasView.Tool.ERASER
    }

    fun setStylusGestureEnabled(value: Boolean) {
        stylusGesture = stylusGesture.copy(enabled = value)
        store.putInt(KEY_STYLUS_ENABLED, if (value) 1 else 0)
    }

    fun setStylusBurstProtection(value: Boolean) {
        stylusGesture = stylusGesture.copy(burstProtection = value)
        store.putInt(KEY_STYLUS_BURST_PROTECTION, if (value) 1 else 0)
    }

    fun setStylusAutoReturnDelayMs(delayMs: Long) {
        stylusGesture = stylusGesture.copy(autoReturnDelayMs = delayMs)
        store.putInt(KEY_STYLUS_AUTO_RETURN, delayMs.toInt())
    }

    fun setStylusTapSpeed(speed: StylusTapSpeed) {
        stylusGesture = stylusGesture.copy(speed = speed)
        store.putInt(KEY_STYLUS_SPEED, speed.code)
    }

    fun setStylusCooldownMs(ms: Long) {
        val value = ms.coerceIn(StylusGestureConfig.MIN_COOLDOWN_MS, StylusGestureConfig.MAX_COOLDOWN_MS)
        stylusGesture = stylusGesture.copy(cooldownMs = value)
        store.putInt(KEY_STYLUS_COOLDOWN, value.toInt())
    }

    companion object {
        // Radierergrenzen: 2..120, Standard 20 (an HiNote angelehnt).
        const val MIN_ERASER_RADIUS = 2
        const val MAX_ERASER_RADIUS = 120
        const val DEFAULT_ERASER_RADIUS = 20

        const val KEY_PEN_TYPE = "pen_type"
        const val KEY_PEN_COLOR = "pen_color"
        const val KEY_PEN_WIDTH = "pen_width_"
        const val KEY_BAR_PINNED = "bar_pinned"
        const val KEY_COMPACT = "editor_compact"
        const val KEY_ERASER_STYLE = "eraser_style"
        const val KEY_ERASER_RADIUS = "eraser_radius"

        const val KEY_LASSO_INK = "lasso_ink"
        const val KEY_LASSO_TEXT = "lasso_text"
        const val KEY_LASSO_IMAGE = "lasso_image"

        const val KEY_STYLUS_ENABLED = "stylus_gesture_enabled"
        const val KEY_STYLUS_BURST_PROTECTION = "stylus_burst_protection"
        const val KEY_STYLUS_AUTO_RETURN = "stylus_auto_return_delay_ms"
        const val KEY_STYLUS_SPEED = "stylus_gesture_speed"
        const val KEY_STYLUS_COOLDOWN = "stylus_gesture_cooldown_ms"

        // Gespeicherte Codes unabhängig von der Reihenfolge des Enums.
        private fun codeOf(style: PageCanvasView.EraserStyle): Int = when (style) {
            PageCanvasView.EraserStyle.STANDARD -> 0
            PageCanvasView.EraserStyle.AREA -> 1
            PageCanvasView.EraserStyle.STROKES -> 2
        }

        private fun styleOf(code: Int): PageCanvasView.EraserStyle = when (code) {
            1 -> PageCanvasView.EraserStyle.AREA
            2 -> PageCanvasView.EraserStyle.STROKES
            else -> PageCanvasView.EraserStyle.STANDARD
        }
    }
}
