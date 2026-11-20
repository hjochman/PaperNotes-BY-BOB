package local.papernotes.editor

import local.papernotes.editor.PageCanvasView.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusGestureLogicTest {
    private class MapStore(val map: MutableMap<String, Int> = HashMap()) : ToolSettingsStore {
        override fun getInt(key: String, default: Int): Int = map[key] ?: default
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    private val marker = PenCatalog.pens.first { it.isMarker }
    private val snapshot = PenSnapshot(marker, marker.maxWidth, PenColor.GRAY)
    private val other = PenSnapshot(PenCatalog.default, PenCatalog.default.defaultWidth, PenColor.BLACK)

    private fun toggle(speed: StylusTapSpeed = StylusTapSpeed.NORMAL, burstProtection: Boolean = false, cooldown: Long = 800L) =
        StylusGestureLogic(StylusGestureConfig(true, speed, burstProtection, 500L, 800L, cooldown))

    @Test
    fun speedProfilesHaveExpectedWindows() {
        assertEquals(200L, StylusTapSpeed.VERY_FAST.windowMs)
        assertEquals(300L, StylusTapSpeed.FAST.windowMs)
        assertEquals(400L, StylusTapSpeed.NORMAL.windowMs)
        assertEquals(500L, StylusTapSpeed.RELAXED.windowMs)
        assertEquals(650L, StylusTapSpeed.SLOW.windowMs)
        assertEquals(5, StylusTapSpeed.values().size)
    }

    @Test
    fun codesRoundTripAndFallBackToDefaults() {
        for (s in StylusTapSpeed.values()) assertEquals(s, StylusTapSpeed.fromCode(s.code))
        assertEquals(StylusTapSpeed.NORMAL, StylusTapSpeed.fromCode(99))
    }

    @Test
    fun immediateDoubleTapWithoutBurstProtection() {
        val l = toggle(burstProtection = false, cooldown = 400)
        val d1 = l.onDoubleTapEvent(1000, Tool.PEN, snapshot)
        assertTrue(d1 is DoubleTapDecision.ExecuteNow)
        assertEquals(StylusGestureAction.SwitchToEraser, (d1 as DoubleTapDecision.ExecuteNow).action)

        // Während Cooldown: ignoriert
        assertEquals(DoubleTapDecision.Ignored, l.onDoubleTapEvent(1200, Tool.ERASER, other))

        // Nach Cooldown: Umschalten zurück
        val d2 = l.onDoubleTapEvent(1500, Tool.ERASER, other)
        assertTrue(d2 is DoubleTapDecision.ExecuteNow)
        assertEquals(StylusGestureAction.RestorePen(snapshot), (d2 as DoubleTapDecision.ExecuteNow).action)
    }

    @Test
    fun burstProtectionSchedulesSingleDoubleTap() {
        val l = toggle(burstProtection = true)
        val d1 = l.onDoubleTapEvent(1000, Tool.PEN, snapshot)
        assertTrue(d1 is DoubleTapDecision.Schedule)
        val schedule = d1 as DoubleTapDecision.Schedule
        assertEquals(500L, schedule.delayMs)
        assertEquals(StylusGestureAction.SwitchToEraser, schedule.action)

        // Nach Ablauf ohne Folgetipps: Commit
        l.commitScheduledToggle(1500, schedule.action)

        // Neuer einzelner Doppeltipp schaltet zurück
        val d2 = l.onDoubleTapEvent(3000, Tool.ERASER, other)
        assertTrue(d2 is DoubleTapDecision.Schedule)
        assertEquals(StylusGestureAction.RestorePen(snapshot), (d2 as DoubleTapDecision.Schedule).action)
    }

    @Test
    fun burstProtectionCancelsWhenContinuousTappingOccurs() {
        val l = toggle(burstProtection = true)
        // 1. Doppeltipp plant Umschalten
        val d1 = l.onDoubleTapEvent(1000, Tool.PEN, snapshot)
        assertTrue(d1 is DoubleTapDecision.Schedule)

        // 2. Doppeltipp kurz danach (Zappeln) bricht sofort ab!
        val d2 = l.onDoubleTapEvent(1300, Tool.PEN, snapshot)
        assertEquals(DoubleTapDecision.CancelPending, d2)

        // 3. und 4. Folgetipps im Zappel-Burst bleiben abgebrochen
        assertEquals(DoubleTapDecision.CancelPending, l.onDoubleTapEvent(1600, Tool.PEN, snapshot))
        assertEquals(DoubleTapDecision.CancelPending, l.onDoubleTapEvent(1900, Tool.PEN, snapshot))

        // Erst nach Beruhigung und Reset: neuer Versuch
        l.resetBurstState()
        val d3 = l.onDoubleTapEvent(3000, Tool.PEN, snapshot)
        assertTrue(d3 is DoubleTapDecision.Schedule)
    }

    @Test
    fun autoReturnRestoresSavedPen() {
        val l = toggle(burstProtection = false)
        l.prepareToggleAction(Tool.PEN, snapshot)
        val restored = l.getSavedPenOrCurrent(other)
        assertEquals(snapshot, restored)

        // Nach einmaliger Wiederherstellung liefert der nächste Aufruf das aktuelle Snapshot
        assertEquals(other, l.getSavedPenOrCurrent(other))
    }

    @Test
    fun disabledGestureIgnoresEverything() {
        val l = StylusGestureLogic(StylusGestureConfig(enabled = false))
        assertEquals(DoubleTapDecision.Ignored, l.onDoubleTapEvent(1000, Tool.PEN, snapshot))
    }

    // --- Persistenz in EditorToolState ---

    @Test
    fun stateDefaultsForStylusGesture() {
        val c = EditorToolState(MapStore()).stylusGesture
        assertTrue(c.enabled)
        assertTrue(c.burstProtection)
        assertTrue(c.autoReturnEnabled)
        assertEquals(800L, c.autoReturnDelayMs)
        assertEquals(StylusTapSpeed.NORMAL, c.speed)
        assertEquals(StylusGestureConfig.DEFAULT_COOLDOWN_MS, c.cooldownMs)
    }

    @Test
    fun stateStylusSettingsPersist() {
        val store = MapStore()
        val first = EditorToolState(store)
        first.setStylusGestureEnabled(false)
        first.setStylusBurstProtection(false)
        first.setStylusAutoReturnDelayMs(1500L)
        first.setStylusTapSpeed(StylusTapSpeed.SLOW)
        first.setStylusCooldownMs(1200)

        val c = EditorToolState(store).stylusGesture
        assertFalse(c.enabled)
        assertFalse(c.burstProtection)
        assertEquals(1500L, c.autoReturnDelayMs)
        assertEquals(StylusTapSpeed.SLOW, c.speed)
        assertEquals(1200L, c.cooldownMs)
    }

    @Test
    fun stateStylusSettingsAreSanitized() {
        val s = EditorToolState(
            MapStore(
                hashMapOf(
                    EditorToolState.KEY_STYLUS_SPEED to -4,
                    EditorToolState.KEY_STYLUS_COOLDOWN to 999_999,
                ),
            ),
        )
        assertEquals(StylusTapSpeed.NORMAL, s.stylusGesture.speed)
        assertEquals(StylusGestureConfig.MAX_COOLDOWN_MS, s.stylusGesture.cooldownMs)
        s.setStylusCooldownMs(-10)
        assertEquals(StylusGestureConfig.MIN_COOLDOWN_MS, s.stylusGesture.cooldownMs)
    }

    @Test
    fun stateRestoresPenSnapshotAndKeepsBarState() {
        val s = EditorToolState(MapStore())
        s.selectPen(marker)
        s.setPenWidth(marker.maxWidth)
        s.selectColor(PenColor.GRAY)
        val snap = s.penSnapshot()
        assertEquals(PenSnapshot(marker, marker.maxWidth, PenColor.GRAY), snap)

        s.switchToEraserKeepingBar()
        assertEquals(Tool.ERASER, s.tool)
        assertFalse(s.barVisible)

        s.selectPen(PenCatalog.default)
        s.selectColor(PenColor.BLACK)
        s.restorePen(snap)
        assertEquals(Tool.PEN, s.tool)
        assertEquals(marker, s.pen)
        assertEquals(marker.maxWidth, s.penWidth)
        assertEquals(PenColor.GRAY, s.penColor)
        assertFalse(s.barVisible)
    }
}
