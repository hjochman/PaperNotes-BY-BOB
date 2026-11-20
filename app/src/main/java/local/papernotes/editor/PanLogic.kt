package local.papernotes.editor

import kotlin.math.hypot

/**
 * Merkt, wann ein Stift im Spiel ist (aufliegend, schwebend oder gerade abgehoben). Ohne Android-Bezug (JVM-testbar).
 *
 * Warum: Die E-Ink-Schicht schreibt selbst; der Handballen kommt oft kurz vor, mit oder nach dem Stift. Solange der Stift
 * im Spiel ist, darf ein Finger die Seite nicht verschieben. Zeiten sind `MotionEvent.getEventTime()` in Millisekunden.
 */
class PenGuard(private val graceMs: Long = GRACE_MS) {
    var isDown: Boolean = false
        private set
    private var lastActivity = NEVER

    fun penDown(time: Long) {
        isDown = true
        lastActivity = time
    }

    fun penUp(time: Long) {
        isDown = false
        lastActivity = time
    }

    /** Stift schwebt über dem Display (Hover-Ereignis) oder bewegt sich. */
    fun penNear(time: Long) {
        lastActivity = time
    }

    /** Ein Finger darf nur verschieben, wenn der Stift nicht aufliegt und seit seiner letzten Aktivität [graceMs] vergangen sind. */
    fun fingerAllowed(time: Long): Boolean = !isDown && time - lastActivity >= graceMs

    companion object {
        /** Nachlaufzeit nach dem Abheben bzw. Schweben des Stifts. */
        const val GRACE_MS = 800L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}

/** Ergebnis von [PanTracker.move]. */
enum class PanStep { NONE, STARTED, MOVED }

/**
 * Zustand einer Ein-Finger-Wischgeste zum Verschieben der Seite. Ohne Android-Bezug (JVM-testbar).
 *
 * Ablauf: [begin] beim Aufsetzen (nur wenn Verschieben erlaubt ist), [move] bei jeder Bewegung. Erst ab [slop] Pixeln
 * Abstand beginnt die Geste ([PanStep.STARTED]); ein Tippen bleibt so ein Tippen. [totalDx]/[totalDy] sind der Weg seit
 * dem Aufsetzen. [abort] (zweiter Zeiger, Stift taucht auf) beendet die Verschiebung ohne Wirkung; eine schon begonnene
 * Geste wird bis zum Loslassen weiter [swallowing] (kein Tippen am Ende). [end] setzt zurück.
 */
class PanTracker(private val slop: Float) {
    private enum class State { IDLE, ARMED, PANNING, DEAD }

    private var state = State.IDLE

    /** Aufsetzpunkt des Fingers (Ansichtspixel); Anfang des Gummiband-Pfeils. */
    var startX: Float = 0f
        private set
    var startY: Float = 0f
        private set

    var totalDx: Float = 0f
        private set
    var totalDy: Float = 0f
        private set

    /** True, solange eine begonnene Geste alle Ereignisse für sich beansprucht (nicht an Kinder oder Werkzeuge weitergeben). */
    val swallowing: Boolean get() = state == State.PANNING || state == State.DEAD

    /** True, wenn die Geste begonnen hat und beim Loslassen angewendet werden soll. */
    val isPanning: Boolean get() = state == State.PANNING

    fun begin(x: Float, y: Float) {
        state = State.ARMED
        startX = x
        startY = y
        totalDx = 0f
        totalDy = 0f
    }

    fun move(x: Float, y: Float): PanStep {
        if (state == State.IDLE || state == State.DEAD) return PanStep.NONE
        val wasArmed = state == State.ARMED
        if (wasArmed && hypot(x - startX, y - startY) <= slop) return PanStep.NONE
        state = State.PANNING
        totalDx = x - startX
        totalDy = y - startY
        return if (wasArmed) PanStep.STARTED else PanStep.MOVED
    }

    /** Beendet das Verschieben vorzeitig; war die Geste schon begonnen, wird sie bis [end] weiter verschluckt. */
    fun abort() {
        state = if (state == State.PANNING || state == State.DEAD) State.DEAD else State.IDLE
    }

    fun end() {
        state = State.IDLE
        totalDx = 0f
        totalDy = 0f
    }
}
