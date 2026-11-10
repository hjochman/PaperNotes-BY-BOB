package local.papernotes.widget

import local.papernotes.home.DateLabels
import local.papernotes.storage.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WidgetLogicTest {
    private fun note(id: String, modified: Long, folder: String = "", fav: Boolean = false, pinned: Boolean = false, title: String = id) =
        NoteSummary(id, title, folder, modified, modified, fav, pinned, 1)

    private val notes = listOf(
        note("a", 100, fav = true),
        note("b", 300, folder = "Arbeit"),
        note("c", 200, folder = "Arbeit/Projekt", pinned = true),
        note("d", 400, folder = "AB", fav = true),
        note("e", 50),
    )

    @Test
    fun recentSortsByModifiedDescendingAndLimits() {
        val sel = WidgetLogic.select(notes, WidgetConfig(), 3)
        assertEquals(listOf("d", "b", "c"), sel.map { it.id })
    }

    @Test
    fun recentIgnoresPinnedFirstRule() {
        // Fixierte Notizen stehen im Widget "Zuletzt geändert" nicht automatisch oben.
        val sel = WidgetLogic.select(notes, WidgetConfig(), 8)
        assertEquals(listOf("d", "b", "c", "a", "e"), sel.map { it.id })
    }

    @Test
    fun favoritesAndPinned() {
        assertEquals(listOf("d", "a"), WidgetLogic.select(notes, WidgetConfig(source = WidgetSource.FAVORITES), 8).map { it.id })
        assertEquals(listOf("c"), WidgetLogic.select(notes, WidgetConfig(source = WidgetSource.PINNED), 8).map { it.id })
    }

    @Test
    fun folderIncludesSubfoldersButNotSimilarNames() {
        val cfg = WidgetConfig(source = WidgetSource.FOLDER, folder = "Arbeit")
        // Fixierte zuerst wie auf der Startseite: c ist fixiert.
        assertEquals(listOf("c", "b"), WidgetLogic.select(notes, cfg, 8).map { it.id })
        assertEquals(listOf("d"), WidgetLogic.select(notes, cfg.copy(folder = "AB"), 8).map { it.id })
        assertEquals(5, WidgetLogic.select(notes, cfg.copy(folder = ""), 8).size)
    }

    @Test
    fun limitIsBoundedAndNonPositiveGivesNothing() {
        val many = (1..20).map { note("n$it", it.toLong()) }
        assertEquals(WidgetConfig.MAX_ROWS, WidgetLogic.select(many, WidgetConfig(), 50).size)
        assertTrue(WidgetLogic.select(many, WidgetConfig(), 0).isEmpty())
        assertTrue(WidgetLogic.select(emptyList(), WidgetConfig(), 5).isEmpty())
    }

    @Test
    fun visibleRowsFitHeight() {
        val full = WidgetConfig(count = 8)
        // 12 Rahmen (2x 4 Rand + 2x 2 Strich) + 48+1 Kopf; 48 je Zeile; Fuß nur ohne Titel.
        assertEquals(8, WidgetLogic.visibleRows(0, full))
        assertEquals(1, WidgetLogic.visibleRows(110, full)) // 110-12-49 = 49 -> 1 Zeile
        assertEquals(4, WidgetLogic.visibleRows(300, WidgetConfig(count = 5))) // 300-12-49 = 239 -> 4
        assertEquals(5, WidgetLogic.visibleRows(310, WidgetConfig(count = 5))) // 249 -> 5
        assertEquals(3, WidgetLogic.visibleRows(300, WidgetConfig(count = 3)))
        assertEquals(0, WidgetLogic.visibleRows(60, full)) // nur Kopfzeile passt
        assertEquals(8, WidgetLogic.visibleRows(500, full))
    }

    @Test
    fun footerReplacesTitleAndHeaderHidesWhenEmpty() {
        val noTitle = WidgetConfig(showTitle = false)
        assertTrue(WidgetLogic.showFooter(noTitle))
        assertFalse(WidgetLogic.showFooter(WidgetConfig()))
        val bare = WidgetConfig(showClock = false, showTitle = false, showNewButton = false)
        assertFalse(WidgetLogic.showHeader(bare))
        assertTrue(WidgetLogic.showHeader(bare.copy(showNewButton = true)))
        // Ohne Kopfzeile, aber mit Fußzeile: (100-12-49)/48 = 0 -> mindestens 1 Zeile.
        assertEquals(1, WidgetLogic.visibleRows(60, bare.copy(count = 5)))
        assertEquals(1, WidgetLogic.visibleRows(100, bare.copy(count = 5)))
    }

    @Test
    fun rowsSanitizeTitlesAndFormatDates() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(zone).apply { clear(); set(2026, 10, 5, 15, 30) }.timeInMillis
        val today = Calendar.getInstance(zone).apply { clear(); set(2026, 10, 5, 9, 15) }.timeInMillis
        val labels = DateLabels(Locale.GERMANY, zone, { now }) { _, _ -> "d. MMMM" }
        val rows = WidgetLogic.rows(
            listOf(note("x", today, title = "Zeile1\nZeile2", fav = true), note("y", today, title = "   ", pinned = true)),
            labels,
            "Ohne Titel",
        )
        assertEquals("Zeile1 Zeile2", rows[0].title)
        assertEquals("09:15", rows[0].dateText)
        assertTrue(rows[0].favorite)
        assertEquals("Ohne Titel", rows[1].title)
        assertTrue(rows[1].pinned)
    }

    @Test
    fun configDefaults() {
        val d = WidgetConfig.fromEntries { null }
        assertEquals(WidgetConfig(), d)
        assertTrue(d.showClock)
        assertFalse(d.showDate)
        assertEquals(5, d.count)
        assertEquals(WidgetSource.RECENT, d.source)
        assertTrue(d.showNewButton)
        assertTrue(d.showTitle)
    }

    @Test
    fun configRoundTripAndInvalidValues() {
        val cfg = WidgetConfig(false, true, WidgetSource.FOLDER, "A/B", 8, false, false)
        val map = cfg.toEntries()
        assertEquals(cfg, WidgetConfig.fromEntries { map[it] })
        val bad = mapOf("count" to "7", "source" to "NOPE", "folder" to "../x", "clock" to "maybe")
        val parsed = WidgetConfig.fromEntries { bad[it] }
        assertEquals(WidgetConfig(), parsed)
        assertEquals(setOf(3, 4, 5, 6, 8), WidgetConfig.COUNT_OPTIONS.toSet())
    }

    // ---- Option "Favoriten oben"

    private fun ids(cfg: WidgetConfig, list: List<NoteSummary> = notes, limit: Int = 8) = WidgetLogic.select(list, cfg, limit).map { it.id }

    @Test
    fun favoritesFirstOffIsUnchanged() {
        assertEquals(listOf("d", "b", "c", "a", "e"), ids(WidgetConfig(favoritesFirst = false)))
        assertEquals(listOf("c", "b"), ids(WidgetConfig(source = WidgetSource.FOLDER, folder = "Arbeit", favoritesFirst = false)))
    }

    @Test
    fun favoritesFirstRecentPutsFavoritesOnTopWithoutDuplicates() {
        val list = listOf(note("a", 100, fav = true), note("b", 300), note("c", 200), note("d", 400, fav = true), note("e", 50))
        // Favoriten d, a (modified absteigend), danach die übrigen b, c, e; jede Notiz nur einmal.
        assertEquals(listOf("d", "a", "b", "c", "e"), ids(WidgetConfig(favoritesFirst = true), list))
        assertEquals(listOf("d", "a", "b"), ids(WidgetConfig(favoritesFirst = true), list, 3))
    }

    @Test
    fun favoritesFirstOnlyFavorites() {
        val list = listOf(note("a", 100, fav = true), note("b", 300, fav = true))
        assertEquals(listOf("b", "a"), ids(WidgetConfig(favoritesFirst = true), list))
    }

    @Test
    fun favoritesFirstNoFavoritesKeepsSourceOrder() {
        val list = listOf(note("a", 100), note("b", 300))
        assertEquals(listOf("b", "a"), ids(WidgetConfig(favoritesFirst = true), list))
    }

    @Test
    fun moreFavoritesThanRowsFillAllRowsNewestFirst() {
        val list = (1..6).map { note("f$it", it * 10L, fav = true) } + note("x", 999)
        assertEquals(listOf("f6", "f5", "f4"), ids(WidgetConfig(favoritesFirst = true), list, 3))
    }

    @Test
    fun favoritesFirstFolderIncludesFavoritesOutsideFolder() {
        val cfg = WidgetConfig(source = WidgetSource.FOLDER, folder = "Arbeit", favoritesFirst = true)
        // Favoriten aus dem ganzen Bestand (d in "AB", a im Wurzelordner), danach der Ordner Arbeit (c fixiert zuerst, dann b).
        assertEquals(listOf("d", "a", "c", "b"), ids(cfg))
        assertEquals(listOf("d", "a"), ids(cfg, limit = 2))
    }

    @Test
    fun favoritesFirstFolderUsesStartPageOrderAmongFavorites() {
        val list = listOf(
            note("p", 10, folder = "Arbeit", fav = true, pinned = true),
            note("q", 500, folder = "Arbeit", fav = true),
            note("r", 300, folder = "Arbeit"),
        )
        // Fixierte zuerst (NoteOrdering), dann modified absteigend.
        assertEquals(listOf("p", "q", "r"), ids(WidgetConfig(source = WidgetSource.FOLDER, folder = "Arbeit", favoritesFirst = true), list))
    }

    @Test
    fun favoritesFirstPinnedSourceAddsFavorites() {
        val cfg = WidgetConfig(source = WidgetSource.PINNED, favoritesFirst = true)
        // Favoriten d, a zuerst, dann die fixierte c.
        assertEquals(listOf("d", "a", "c"), ids(cfg))
    }

    @Test
    fun favoritesFirstHasNoEffectForFavoritesSource() {
        val on = WidgetConfig(source = WidgetSource.FAVORITES, favoritesFirst = true)
        assertEquals(ids(on.copy(favoritesFirst = false)), ids(on))
        assertEquals(listOf("d", "a"), ids(on))
    }

    @Test
    fun favoritesFirstRespectsRowLimit() {
        val many = (1..20).map { note("n$it", it.toLong(), fav = it % 2 == 0) }
        assertEquals(WidgetConfig.MAX_ROWS, WidgetLogic.select(many, WidgetConfig(favoritesFirst = true), 50).size)
        assertTrue(WidgetLogic.select(many, WidgetConfig(favoritesFirst = true), 0).isEmpty())
    }

    @Test
    fun favoritesFirstConfigDefaultAndRoundTrip() {
        assertFalse(WidgetConfig().favoritesFirst)
        assertFalse(WidgetConfig.fromEntries { null }.favoritesFirst)
        val cfg = WidgetConfig(favoritesFirst = true)
        assertEquals(cfg, WidgetConfig.fromEntries { cfg.toEntries()[it] })
        assertTrue(WidgetConfig.KEY_FAVORITES_FIRST in WidgetConfig.ALL_KEYS)
        assertTrue(WidgetConfig.ALL_KEYS.toSet().containsAll(cfg.toEntries().keys))
        // Alte gespeicherte Widgets ohne den Schlüssel bleiben unverändert (AUS); Unsinn ergibt den Standard.
        assertFalse(WidgetConfig.fromEntries { if (it == WidgetConfig.KEY_FAVORITES_FIRST) null else cfg.toEntries()[it] }.favoritesFirst)
        assertFalse(WidgetConfig.fromEntries { if (it == WidgetConfig.KEY_FAVORITES_FIRST) "maybe" else null }.favoritesFirst)
    }
}
