package local.papernotes.storage

/**
 * Zählt Fehlschläge in Folge und entscheidet, wann ein Hinweis fällig ist: erst nach [threshold] Fehlschlägen in Folge,
 * dann genau einmal, danach erst wieder nach einem Erfolg und erneut [threshold] Fehlschlägen. So bleibt ein dauerhaft
 * ausfallender Hintergrundvorgang (z. B. die automatische Sicherung) nicht unbemerkt, ohne den Nutzer mit Dauer-Toasts
 * zu überhäufen.
 *
 * Reine Logik ohne Android-Bezug, nur im Speicher (gilt je Instanz, z. B. je Editor-Sitzung). Aufrufe sind synchronisiert,
 * weil Hintergrund-Thread und Oberfläche darauf zugreifen können.
 */
class FailureStreak(private val threshold: Int) {
    private var streak = 0
    private var noticeShown = false

    init {
        require(threshold >= 1)
    }

    /** Meldet einen Fehlschlag. Liefert true genau dann, wenn jetzt der Hinweis gezeigt werden soll. */
    @Synchronized
    fun onFailure(): Boolean {
        if (streak < threshold) streak++
        if (streak < threshold || noticeShown) return false
        noticeShown = true
        return true
    }

    /** Meldet einen Erfolg: Zähler und "Hinweis gezeigt" werden zurückgesetzt. */
    @Synchronized
    fun onSuccess() {
        streak = 0
        noticeShown = false
    }
}
