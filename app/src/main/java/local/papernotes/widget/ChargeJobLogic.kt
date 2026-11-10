package local.papernotes.widget

/** Was [StatusScheduler] mit dem Lade-Job ([ChargeStatusJobService]) tun soll. */
enum class ChargeJobAction { SCHEDULE, CANCEL, KEEP }

/**
 * Entscheidung "Lade-Job einplanen ja/nein", ohne Android-Bezug.
 *
 * Hintergrund: Der Job hat nur die Bedingung "lädt" (`setRequiresCharging`) und feuert deshalb sofort, sobald er bei
 * angestecktem Netzteil eingeplant wird, und danach sofort wieder, wenn man ihn gleich neu einplant. Daher wird er NUR
 * eingeplant, solange NICHT geladen wird. Ablauf:
 *  1. nicht ladend: Job wartet (kein Prozess, kein Alarm, kein Wake-Lock; das System hält die Bedingung).
 *  2. Netzteil angesteckt: das System startet den Job, er aktualisiert die Anzeige (Blitz) und plant NICHT neu.
 *  3. Während der Ladung: kein Job; der 15-Minuten-Tick ([StatusReceiver]) liest den Akku wie bisher.
 *  4. Abgesteckt: der nächste Tick (<= ~25 min, Inexakt-Alarm) zeigt den Blitz nicht mehr und plant den Job neu
 *     (Zustand "nicht ladend, Job fehlt" -> [ChargeJobAction.SCHEDULE]). Das Abstecken wird also weiterhin bis zu
 *     ~25 min verzögert angezeigt; nur das Anstecken ist zeitnah.
 * Ein sehr kurzes Abstecken zwischen zwei Ticks wird nicht bemerkt (der Blitz bliebe ohnehin an).
 */
object ChargeJobLogic {
    /**
     * [configs] = Konfiguration aller Widgets, [charging] = Ladezustand (null = Akku unlesbar), [pending] = Job schon eingeplant.
     * - keine Batterie-Option: [ChargeJobAction.CANCEL]
     * - lädt oder unbekannt: [ChargeJobAction.KEEP] (nie einplanen: sonst feuert er sofort wieder)
     * - lädt nicht und Job fehlt: [ChargeJobAction.SCHEDULE]; ist er schon eingeplant, [ChargeJobAction.KEEP]
     */
    fun decide(configs: List<WidgetConfig>, charging: Boolean?, pending: Boolean): ChargeJobAction = when {
        configs.none { it.showBattery } -> ChargeJobAction.CANCEL
        charging != false -> ChargeJobAction.KEEP
        pending -> ChargeJobAction.KEEP
        else -> ChargeJobAction.SCHEDULE
    }
}
