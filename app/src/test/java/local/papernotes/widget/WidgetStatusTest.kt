package local.papernotes.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStatusTest {
    private class MapStore : StatusStateStore {
        val map = HashMap<Int, String>()
        override fun get(id: Int) = map[id]
        override fun set(id: Int, value: String?) {
            if (value == null) map.remove(id) else map[id] = value
        }
    }

    @Test
    fun batteryPercentAndCharging() {
        assertEquals(BatteryReading(100, true), BatteryMath.reading(100, 100, 5, 1))
        assertEquals(BatteryReading(50, false), BatteryMath.reading(128, 255, 3, 0))
        assertEquals(BatteryReading(42, true), BatteryMath.reading(42, 100, BatteryMath.STATUS_CHARGING, 0))
        assertEquals(BatteryReading(0, false), BatteryMath.reading(0, 100, 3, 0))
        assertNull(BatteryMath.reading(-1, 100, 3, 0))
        assertNull(BatteryMath.reading(50, 0, 3, 0))
        assertEquals("73%", WidgetStatusLogic.batteryText(73))
        assertEquals("100%", WidgetStatusLogic.batteryText(150))
    }

    @Test
    fun notificationFilterIgnoresOngoingAndSummary() {
        assertTrue(NotificationFilter.counts(0))
        assertTrue(NotificationFilter.counts(0x10)) // z. B. FLAG_AUTO_CANCEL
        assertFalse(NotificationFilter.counts(NotificationFilter.FLAG_ONGOING_EVENT))
        assertFalse(NotificationFilter.counts(NotificationFilter.FLAG_GROUP_SUMMARY))
        assertFalse(NotificationFilter.counts(NotificationFilter.FLAG_ONGOING_EVENT or NotificationFilter.FLAG_GROUP_SUMMARY or 0x10))
    }

    @Test
    fun bellText() {
        assertEquals("14", WidgetStatusLogic.bellText(14, true))
        assertEquals("0", WidgetStatusLogic.bellText(0, true))
        assertEquals("99+", WidgetStatusLogic.bellText(100, true))
        assertEquals("–", WidgetStatusLogic.bellText(14, false)) // ohne Zugriff
        assertEquals("–", WidgetStatusLogic.bellText(null, true)) // noch nie gezählt
    }

    @Test
    fun defaultsDoNotChangeExistingWidgets() {
        val d = WidgetConfig()
        assertFalse(d.showBattery)
        assertFalse(d.showNotifications)
        assertFalse(d.usesStatus)
        assertEquals(StatusDisplay.NONE, WidgetStatusLogic.display(d, BatteryReading(80, false), 3, true))
        // Ohne Status gilt die Konfiguration unverändert (Titel und Datum wie eingestellt).
        assertEquals(HeaderLayout(true, false), WidgetStatusLogic.headerLayout(d, StatusDisplay.NONE, 100))
        assertEquals(HeaderLayout(true, true), WidgetStatusLogic.headerLayout(d.copy(showDate = true), StatusDisplay.NONE, 0))
        // Alte gespeicherte Konfiguration ohne die neuen Schlüssel bleibt unverändert.
        assertEquals(d, WidgetConfig.fromEntries { k -> if (k == WidgetConfig.KEY_BATTERY || k == WidgetConfig.KEY_NOTIFICATIONS) null else d.toEntries()[k] })
    }

    @Test
    fun configRoundTripIncludesStatusOptions() {
        val cfg = WidgetConfig(showBattery = true, showNotifications = true)
        val map = cfg.toEntries()
        assertEquals(cfg, WidgetConfig.fromEntries { map[it] })
        assertTrue(cfg.usesStatus)
        assertTrue(WidgetConfig.ALL_KEYS.containsAll(listOf(WidgetConfig.KEY_BATTERY, WidgetConfig.KEY_NOTIFICATIONS)))
    }

    @Test
    fun displayFollowsOptions() {
        val bat = BatteryReading(100, true)
        val both = WidgetConfig(showBattery = true, showNotifications = true)
        assertEquals(StatusDisplay("100%", true, "14"), WidgetStatusLogic.display(both, bat, 14, true))
        assertEquals(StatusDisplay("100%", true, null), WidgetStatusLogic.display(WidgetConfig(showBattery = true), bat, 14, true))
        assertEquals(StatusDisplay(null, false, "–"), WidgetStatusLogic.display(WidgetConfig(showNotifications = true), bat, 14, false))
        assertEquals(StatusDisplay("–", false, null), WidgetStatusLogic.display(WidgetConfig(showBattery = true), null, null, false))
    }

    @Test
    fun headerDropsTitleFirstThenDateNeverStatus() {
        val cfg = WidgetConfig(showClock = true, showDate = true, showBattery = true, showNotifications = true)
        val d = StatusDisplay("100%", true, "14")
        assertEquals(HeaderLayout(false, true), WidgetStatusLogic.headerLayout(cfg, d, 0)) // Breite unbekannt: Titel weg
        assertEquals(HeaderLayout(true, true), WidgetStatusLogic.headerLayout(cfg, d, 700))
        assertEquals(HeaderLayout(false, true), WidgetStatusLogic.headerLayout(cfg, d, 450)) // Titel passt nicht mehr
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(cfg, d, 250)) // auch Datum weg
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(cfg, d, 10)) // der Status bleibt immer
        // Uhr ohne Datum: Titel passt ab ca. 360dp
        val small = WidgetConfig(showClock = true, showNotifications = true)
        val bell = StatusDisplay(null, false, "5")
        assertEquals(HeaderLayout(true, false), WidgetStatusLogic.headerLayout(small, bell, 400))
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(small, bell, 300))
        // Titel ist aus: bleibt aus
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(small.copy(showTitle = false), bell, 900))
        // Datum nur mit Uhr
        assertEquals(HeaderLayout(true, false), WidgetStatusLogic.headerLayout(cfg.copy(showClock = false), d, 900))
    }

    @Test
    fun statusNeverAddsARowAndCountsAsHeader() {
        val bare = WidgetConfig(showClock = false, showTitle = false, showNewButton = false, count = 8)
        assertFalse(WidgetLogic.showHeader(bare))
        assertTrue(WidgetLogic.showHeader(bare.copy(showBattery = true))) // Status braucht die Kopfzeile
        // Die Zahl sichtbarer Notizzeilen hängt nicht vom Status ab.
        val cfg = WidgetConfig(count = 8)
        assertEquals(WidgetLogic.visibleRows(270, cfg), WidgetLogic.visibleRows(270, cfg.copy(showBattery = true, showNotifications = true)))
    }

    @Test
    fun trackerRendersOnlyOnChange() {
        val narrow = HeaderLayout(false, true)
        val wide = HeaderLayout(true, true)
        val tracker = StatusChangeTracker(MapStore())
        val a = StatusDisplay("100%", false, "3")
        assertEquals(StatusUpdate.FULL, tracker.decide(7, a, narrow)) // erster Aufruf
        assertEquals(StatusUpdate.NONE, tracker.decide(7, a, narrow)) // gleicher Text: nichts neu zeichnen
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(7, a.copy(batteryText = "99%"), narrow))
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(7, a.copy(batteryText = "99%", charging = true), narrow))
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(7, a.copy(batteryText = "99%", charging = true, bellText = "4"), narrow))
        assertEquals(StatusUpdate.FULL, tracker.decide(7, a.copy(batteryText = "99%", charging = true, bellText = "4"), wide))
        assertEquals(StatusUpdate.FULL, tracker.decide(8, a, wide)) // anderes Widget
        tracker.forget(7)
        assertEquals(StatusUpdate.FULL, tracker.decide(7, a, wide))
    }

    @Test
    fun trackerUsesRecordedStateFromFullRender() {
        val store = MapStore()
        val tracker = StatusChangeTracker(store)
        val wide = HeaderLayout(true, false)
        val a = StatusDisplay("50%", false, null)
        tracker.record(1, a, wide)
        assertEquals(StatusUpdate.NONE, StatusChangeTracker(store).decide(1, a, wide)) // überlebt neuen Tracker (Prozess)
    }

    @Test
    fun timerOnlyWhileSomeWidgetUsesStatus() {
        assertFalse(WidgetStatusLogic.timerNeeded(emptyList()))
        assertFalse(WidgetStatusLogic.timerNeeded(listOf(WidgetConfig(), WidgetConfig())))
        assertTrue(WidgetStatusLogic.timerNeeded(listOf(WidgetConfig(), WidgetConfig(showBattery = true))))
        assertTrue(WidgetStatusLogic.timerNeeded(listOf(WidgetConfig(showNotifications = true))))
        assertEquals(15 * 60 * 1000L, WidgetStatusLogic.TIMER_INTERVAL_MS)
    }

    @Test
    fun syncPartFollowsOptionAndStatus() {
        val d = WidgetConfig()
        assertTrue(d.showSync) // Standard: an
        assertFalse(d.usesStatus) // der Sync braucht den 15-Minuten-Timer nicht
        assertFalse(WidgetStatusLogic.timerNeeded(listOf(d)))
        assertEquals(StatusDisplay(null, false, null, "3"), WidgetStatusLogic.display(d, null, null, false, "3"))
        assertEquals(StatusDisplay.NONE, WidgetStatusLogic.display(d.copy(showSync = false), null, null, false, "3"))
        assertEquals(StatusDisplay.NONE, WidgetStatusLogic.display(d, null, null, false, null)) // Sync aus: kein Platz belegt
        assertEquals(StatusDisplay(null, false, null, "3", true), WidgetStatusLogic.display(d, null, null, false, "3", true)) // ohne Netz abgeblendet
        assertEquals(StatusDisplay.NONE, WidgetStatusLogic.display(d, null, null, false, null, true)) // ohne Text nichts abzublenden
        // Alte gespeicherte Konfiguration ohne den Schlüssel: Sync an.
        assertTrue(WidgetConfig.fromEntries { k -> if (k == WidgetConfig.KEY_SYNC) null else d.copy(showSync = false).toEntries()[k] }.showSync)
        val map = d.copy(showSync = false).toEntries()
        assertFalse(WidgetConfig.fromEntries { map[it] }.showSync)
        assertTrue(WidgetConfig.ALL_KEYS.contains(WidgetConfig.KEY_SYNC))
    }

    @Test
    fun syncTakesWidthAndTitleGoesFirst() {
        val cfg = WidgetConfig(showClock = true, showDate = false)
        val noSync = StatusDisplay(null, false, null)
        val sync = StatusDisplay(null, false, null, "12")
        assertEquals(HeaderLayout(true, false), WidgetStatusLogic.headerLayout(cfg, sync, 400))
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(cfg, sync, 280)) // Titel weg, Sync bleibt
        assertEquals(HeaderLayout(false, false), WidgetStatusLogic.headerLayout(cfg, sync, 0))
        assertTrue(sync.visible)
        assertFalse(noSync.visible)
    }

    @Test
    fun trackerSeesSyncTextChange() {
        val tracker = StatusChangeTracker(MapStore())
        val l = HeaderLayout(true, false)
        val a = StatusDisplay(null, false, null, "3")
        assertEquals(StatusUpdate.FULL, tracker.decide(1, a, l))
        assertEquals(StatusUpdate.NONE, tracker.decide(1, a, l))
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(1, a.copy(syncText = "2"), l))
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(1, a.copy(syncText = null), l))
    }

    @Test
    fun trackerSeesDimmedChange() {
        val tracker = StatusChangeTracker(MapStore())
        val l = HeaderLayout(true, false)
        val a = StatusDisplay(null, false, null, "1")
        assertEquals(StatusUpdate.FULL, tracker.decide(1, a, l))
        assertEquals(StatusUpdate.PARTIAL, tracker.decide(1, a.copy(syncDimmed = true), l))
        assertEquals(StatusUpdate.NONE, tracker.decide(1, a.copy(syncDimmed = true), l))
    }
}
