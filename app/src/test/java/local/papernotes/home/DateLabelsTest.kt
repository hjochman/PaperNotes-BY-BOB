package local.papernotes.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DateLabelsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private val now = millis(2026, 11, 5, 15, 30)

    private fun labels(locale: Locale) = DateLabels(locale, utc, { now }) { l, _ ->
        if (l.language == "de") "d. MMMM" else "MMMM d"
    }

    @Test
    fun todayIsTime() {
        val de = labels(Locale.GERMANY)
        assertEquals(DateLabels.Bucket.TODAY, de.bucket(millis(2026, 11, 5, 0, 5)))
        assertEquals(DateLabels.Bucket.TODAY, de.bucket(millis(2026, 11, 5, 23, 59)))
        assertEquals("10:20", de.format(millis(2026, 11, 5, 10, 20)))
    }

    @Test
    fun thisYearIsDayAndMonthName() {
        assertEquals(DateLabels.Bucket.THIS_YEAR, labels(Locale.GERMANY).bucket(millis(2026, 10, 6)))
        assertEquals("6. Oktober", labels(Locale.GERMANY).format(millis(2026, 10, 6)))
        assertEquals("October 6", labels(Locale.US).format(millis(2026, 10, 6)))
        assertEquals("1. Januar", labels(Locale.GERMANY).format(millis(2026, 1, 1, 0, 0)))
    }

    @Test
    fun yesterdayIsNotToday() {
        assertEquals(DateLabels.Bucket.THIS_YEAR, labels(Locale.GERMANY).bucket(millis(2026, 11, 4, 23, 59)))
    }

    @Test
    fun olderYearIsFullDate() {
        val de = labels(Locale.GERMANY)
        assertEquals(DateLabels.Bucket.OLDER, de.bucket(millis(2025, 12, 31)))
        val text = de.format(millis(2024, 2, 19))
        assertTrue(text, text.contains("2024") && text.contains("19"))
        val en = labels(Locale.US).format(millis(2024, 2, 19))
        assertTrue(en, en.contains("2024") && en.contains("19"))
    }

    @Test
    fun sameDayOfYearInOtherYearIsOlder() {
        assertEquals(DateLabels.Bucket.OLDER, labels(Locale.GERMANY).bucket(millis(2025, 11, 5)))
    }

    @Test
    fun timeZoneDecidesTheDay() {
        // 23:30 UTC am 5.11. ist in Berlin (UTC+1) bereits der 6.11.
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        val clock = millis(2026, 11, 6, 8, 0)
        val l = DateLabels(Locale.GERMANY, berlin, { clock }) { _, _ -> "d. MMMM" }
        assertEquals(DateLabels.Bucket.TODAY, l.bucket(millis(2026, 11, 5, 23, 30)))
    }
}
