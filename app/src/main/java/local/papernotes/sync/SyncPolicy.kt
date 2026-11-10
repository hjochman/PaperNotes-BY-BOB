package local.papernotes.sync

/**
 * Schutzgrenzen gegen versehentliches Massenlöschen auf dem Server. Der Server-Papierkorb und die Versionierung
 * bleiben zusätzlich als Netz, aber der Sync soll gar nicht erst viel löschen, ohne dass der Nutzer es bestätigt.
 */
object SyncPolicy {
    /** Mehr Löschungen als diese verlangen immer eine Bestätigung. */
    const val MAX_DELETES = 10

    /** Ab dieser Zahl zählt zusätzlich der Anteil am Bestand. */
    const val SMALL_DELETES = 3

    /**
     * Wahr, wenn der Plan erst nach Bestätigung laufen darf: mehr als [MAX_DELETES] Dateien löschen, mehr als die Hälfte
     * des Bestands löschen (ab mehr als [SMALL_DELETES] Dateien) oder der lokale Bestand ist leer, obwohl der Server
     * laut Gedächtnis Notizen hat (typisch bei nicht eingehängtem Speicher oder leerem Ordner).
     */
    fun needsConfirmation(plan: SyncPlan, state: SyncState, local: LocalSnapshot): Boolean {
        val deletes = plan.fileDeletes
        if (deletes == 0) return false
        if (local.files.isEmpty() && state.files.isNotEmpty()) return true
        if (deletes > MAX_DELETES) return true
        return deletes > SMALL_DELETES && deletes * 2 > state.files.size
    }
}
