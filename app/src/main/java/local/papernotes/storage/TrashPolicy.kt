package local.papernotes.storage

/**
 * Löschfrist des Papierkorbs: Einträge, die länger als die eingestellte Zahl Tage dort liegen, werden beim App-Start
 * endgültig gelöscht (samt Sicherungen, siehe [TrashService]). Reine Logik ohne Android-Abhängigkeit.
 */
object TrashPolicy {
    const val PREF_KEY_RETENTION_DAYS = "trash_retention_days"
    const val DEFAULT_RETENTION_DAYS = 30
    const val RETENTION_NEVER = 0

    val RETENTION_OPTIONS = listOf(RETENTION_NEVER, 7, 14, 30, 60, 90, 180, 365)

    private const val DAY_MS = 86_400_000L

    /** Zeitpunkt, ab dem ein am [deleted] gelöschter Eintrag automatisch entfernt wird; null bei [retentionDays] = 0 (nie). */
    fun expiresAt(deleted: Long, retentionDays: Int): Long? =
        if (retentionDays > RETENTION_NEVER) deleted + retentionDays * DAY_MS else null

    /** Wahr, wenn ein am [deleted] gelöschter Eintrag zum Zeitpunkt [now] bei [retentionDays] Tagen Frist abgelaufen ist (0 = nie). */
    fun isExpired(deleted: Long, now: Long, retentionDays: Int): Boolean =
        expiresAt(deleted, retentionDays)?.let { now >= it } ?: false
}
