package local.papernotes.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeJobLogicTest {
    private val battery = listOf(WidgetConfig(), WidgetConfig(showBattery = true))

    @Test
    fun cancelledWithoutBatteryOption() {
        assertEquals(ChargeJobAction.CANCEL, ChargeJobLogic.decide(emptyList(), false, true))
        assertEquals(ChargeJobAction.CANCEL, ChargeJobLogic.decide(listOf(WidgetConfig(showNotifications = true)), false, false))
    }

    @Test
    fun scheduledOnlyWhenNotChargingAndMissing() {
        assertEquals(ChargeJobAction.SCHEDULE, ChargeJobLogic.decide(battery, charging = false, pending = false))
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, charging = false, pending = true))
    }

    @Test
    fun neverScheduledWhileChargingOrUnknown() {
        // Sonst würde der requiresCharging-Job sofort wieder feuern (Endlosschleife).
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, charging = true, pending = false))
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, charging = true, pending = true))
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, charging = null, pending = false))
    }

    @Test
    fun cycleUnplugPlugUnplug() {
        // nicht ladend -> Job geplant; Anstecken -> Job lief, kein Neuplanen; nächster Tick nach Abstecken -> neu geplant
        assertEquals(ChargeJobAction.SCHEDULE, ChargeJobLogic.decide(battery, false, false))
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, true, false)) // Job gelaufen, Update im Job
        assertEquals(ChargeJobAction.KEEP, ChargeJobLogic.decide(battery, true, false)) // Tick während Ladung
        assertEquals(ChargeJobAction.SCHEDULE, ChargeJobLogic.decide(battery, false, false)) // Tick nach Abstecken
    }
}
