package local.papernotes.storage

/**
 * Logik für automatische Sicherungskopien während des Schreibens im Editor.
 *
 * Reine Logik-Klasse ohne Android-Abhängigkeit, vollständig über JVM-Unit-Tests abgedeckt.
 */
object BackupPolicy {
    const val PREF_KEY_INTERVAL_MINUTES = "backup_interval_minutes"
    const val DEFAULT_INTERVAL_MINUTES = 5
    const val INTERVAL_OFF = 0

    val INTERVAL_OPTIONS = listOf(INTERVAL_OFF, 1, 2, 5, 10, 15, 30)

    /** Prüft, ob ein Intervallwert in der Liste der erlaubten Optionen liegt. */
    fun isValidInterval(minutes: Int): Boolean = minutes in INTERVAL_OPTIONS

    /**
     * Entscheidet anhand des aktuellen Zeitpunkts [now], des Zeitpunkts der letzten Sicherung [lastBackupTime],
     * des eingestellten Intervalls in Minuten [intervalMinutes] und des Änderungszustands [modifiedSinceBackup],
     * ob jetzt eine Sicherungskopie angelegt werden soll.
     *
     * @param now Aktuelle Zeit in Millisekunden
     * @param lastBackupTime Zeitstempel der letzten Sicherung in Millisekunden
     * @param intervalMinutes Eingestelltes Intervall in Minuten (0 = Aus)
     * @param modifiedSinceBackup Wahr, wenn seit der letzten Sicherung Änderungen vorgenommen bzw. gespeichert wurden
     */
    fun shouldBackup(
        now: Long,
        lastBackupTime: Long,
        intervalMinutes: Int,
        modifiedSinceBackup: Boolean,
    ): Boolean {
        if (intervalMinutes <= INTERVAL_OFF) return false
        if (!modifiedSinceBackup) return false
        val intervalMs = intervalMinutes * 60_000L
        return (now - lastBackupTime) >= intervalMs
    }
}
