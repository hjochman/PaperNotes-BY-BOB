package local.papernotes.editor

/** Fünf Tipp-Geschwindigkeiten: maximale Zeit zwischen den beiden Tipps eines Doppeltipps. */
enum class StylusTapSpeed(val code: Int, val windowMs: Long) {
    VERY_FAST(0, 200L),
    FAST(1, 300L),
    NORMAL(2, 400L),
    RELAXED(3, 500L),
    SLOW(4, 650L);

    companion object {
        fun fromCode(code: Int): StylusTapSpeed = values().firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * Einstellungen der Stift-Geste.
 * - [burstProtection]: Zappelschutz. Schnelle Folgetipps (mehrere Doppeltipps hintereinander) werden ignoriert.
 * - [autoReturnDelayMs]: Auto-Rücksprung zum Stift nach dem Radieren (-1 = aus, >= 0 = Verzögerung in ms).
 */
data class StylusGestureConfig(
    val enabled: Boolean = true,
    val speed: StylusTapSpeed = StylusTapSpeed.NORMAL,
    val burstProtection: Boolean = true,
    val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    val autoReturnDelayMs: Long = DEFAULT_AUTO_RETURN_DELAY_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    val autoReturnEnabled: Boolean get() = autoReturnDelayMs >= 0L

    companion object {
        const val DEFAULT_BURST_WINDOW_MS = 500L
        const val DEFAULT_AUTO_RETURN_DELAY_MS = 800L
        const val AUTO_RETURN_DISABLED = -1L
        const val DEFAULT_COOLDOWN_MS = 800L
        const val MIN_COOLDOWN_MS = 0L
        const val MAX_COOLDOWN_MS = 3000L
    }
}

/** Stiftzustand, der beim Wechsel zum Radierer gemerkt und danach wiederhergestellt wird. */
data class PenSnapshot(val pen: PenSpec, val width: Int, val color: PenColor)

/** Ergebnis eines Tasten-Ereignisses. */
sealed class StylusGestureAction {
    object None : StylusGestureAction()
    object SwitchToEraser : StylusGestureAction()
    data class RestorePen(val snapshot: PenSnapshot) : StylusGestureAction()
}

/** Ergebnis der Doppeltipp-Auswertung (für verzögertes Ausführen bei Zappelschutz). */
sealed class DoubleTapDecision {
    object Ignored : DoubleTapDecision()
    object CancelPending : DoubleTapDecision()
    data class ExecuteNow(val action: StylusGestureAction) : DoubleTapDecision()
    data class Schedule(val delayMs: Long, val action: StylusGestureAction) : DoubleTapDecision()
}

/**
 * Erkennt Doppeltipp der Stift-Taste (z. B. M-Pencil) mit Zappelschutz und Auto-Rücksprung-Gedächtnis.
 * Ohne Android-Bezug (JVM-testbar).
 */
class StylusGestureLogic(var config: StylusGestureConfig = StylusGestureConfig()) {

    private var savedPen: PenSnapshot? = null
    private var lastTriggerTime = NEVER
    private var lastTapTime = NEVER
    private var burstActive = false

    /**
     * Auswertung eines Doppeltipp-Ereignisses.
     * Wenn [StylusGestureConfig.burstProtection] aktiv ist, wird das Umschalten um [StylusGestureConfig.burstWindowMs]
     * verzögert. Kommt in dieser Zeit ein weiterer Doppeltipp (Zappeln), wird das Umschalten abgebrochen.
     */
    fun onDoubleTapEvent(time: Long, tool: PageCanvasView.Tool, current: PenSnapshot): DoubleTapDecision {
        if (!config.enabled) return DoubleTapDecision.Ignored

        if (!config.burstProtection) {
            val action = executeToggle(time, tool, current)
            return if (action is StylusGestureAction.None) DoubleTapDecision.Ignored else DoubleTapDecision.ExecuteNow(action)
        }

        // Zappelschutz aktiv:
        val gap = time - lastTapTime
        lastTapTime = time

        if (gap in 0..config.burstWindowMs || burstActive) {
            // Schneller Folgetipp -> Zappel-Burst erkannt! Laufendes Umschalten abbrechen.
            burstActive = true
            return DoubleTapDecision.CancelPending
        }

        // Erster isolierter Doppeltipp: Verzögert planen
        val action = prepareToggleAction(tool, current)
        return DoubleTapDecision.Schedule(config.burstWindowMs, action)
    }

    /** Führt das vorbereitete Umschalten aus (nachdem die Zappelschutz-Wartezeit ohne Folgetipp abgelaufen ist). */
    fun commitScheduledToggle(time: Long, action: StylusGestureAction) {
        burstActive = false
        lastTriggerTime = time
        if (action is StylusGestureAction.SwitchToEraser) {
            // savedPen ist bereits in prepareToggleAction gesetzt
        } else if (action is StylusGestureAction.RestorePen) {
            savedPen = null
        }
    }

    /** Verwirft den Zappel-Zustand, z. B. wenn die Wartezeit ohne Folgetipps ablief. */
    fun resetBurstState() {
        burstActive = false
    }

    /** Bereitet die Umschalt-Aktion vor und merkt sich den aktuellen Stiftzustand. */
    fun prepareToggleAction(tool: PageCanvasView.Tool, current: PenSnapshot): StylusGestureAction {
        return if (tool == PageCanvasView.Tool.ERASER) {
            val snapshot = savedPen ?: current
            StylusGestureAction.RestorePen(snapshot)
        } else {
            savedPen = current
            StylusGestureAction.SwitchToEraser
        }
    }

    /** Führt ein sofortiges Umschalten aus (ohne Zappelschutz-Verzögerung). */
    fun executeToggle(time: Long, tool: PageCanvasView.Tool, current: PenSnapshot): StylusGestureAction {
        val cooldown = if (config.cooldownMs > 0) config.cooldownMs else (config.speed.windowMs * 2)
        if (lastTriggerTime != NEVER && time - lastTriggerTime < cooldown) {
            return StylusGestureAction.None
        }
        lastTriggerTime = time
        return if (tool == PageCanvasView.Tool.ERASER) {
            val snapshot = savedPen ?: current
            savedPen = null
            StylusGestureAction.RestorePen(snapshot)
        } else {
            savedPen = current
            StylusGestureAction.SwitchToEraser
        }
    }

    /** Gibt den gemerkten Stift zurück (für Auto-Rücksprung nach dem Radieren). */
    fun getSavedPenOrCurrent(current: PenSnapshot): PenSnapshot {
        val snapshot = savedPen ?: current
        savedPen = null
        return snapshot
    }

    /** Verwirft laufende Gesten, z. B. wenn der Nutzer das Werkzeug von Hand wechselt. */
    fun cancel() {
        savedPen = null
        burstActive = false
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}
