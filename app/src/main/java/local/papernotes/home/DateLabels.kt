package local.papernotes.home

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Datumsanzeige der Kacheln: heute = Uhrzeit, im laufenden Jahr = Tag + Monatsname, sonst volles Datum.
 * Alles richtet sich nach [locale]; die Reihenfolge von Tag und Monat ("6. Oktober" / "October 6") liefert
 * [bestPattern] (auf dem Gerät `android.text.format.DateFormat.getBestDateTimePattern`), damit die Logik ohne Android testbar bleibt.
 */
class DateLabels(
    private val locale: Locale,
    private val zone: TimeZone,
    private val clock: () -> Long,
    private val bestPattern: (Locale, String) -> String,
) {
    enum class Bucket { TODAY, THIS_YEAR, OLDER }

    fun bucket(millis: Long): Bucket {
        val now = calendar(clock())
        val then = calendar(millis)
        return when {
            then.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> Bucket.OLDER
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> Bucket.TODAY
            else -> Bucket.THIS_YEAR
        }
    }

    fun format(millis: Long): String {
        val date = Date(millis)
        return when (bucket(millis)) {
            Bucket.TODAY -> DateFormat.getTimeInstance(DateFormat.SHORT, locale).also { it.timeZone = zone }.format(date)
            Bucket.THIS_YEAR -> SimpleDateFormat(bestPattern(locale, DAY_MONTH_SKELETON), locale).also { it.timeZone = zone }.format(date)
            Bucket.OLDER -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).also { it.timeZone = zone }.format(date)
        }
    }

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance(zone, locale).also { it.timeInMillis = millis }

    companion object {
        const val DAY_MONTH_SKELETON = "dMMMM"
    }
}
