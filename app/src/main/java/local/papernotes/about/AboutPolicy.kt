package local.papernotes.about

/** Reine Entscheidungslogik: Wann öffnet die App beim Start automatisch die Über-Seite? */
object AboutPolicy {
    /**
     * Wahr beim ersten Start nach der Installation ([lastSeen] = null) und nach jedem Update mit geänderter Version.
     * Ist die aktuelle Version unbekannt (leer), wird nichts angezeigt.
     */
    fun shouldShowAbout(lastSeen: String?, current: String): Boolean = current.isNotEmpty() && lastSeen != current
}
