package local.papernotes.home

/** Schlüssel eines auswählbaren Eintrags der Startseite: Notiz (ID) oder Ordner (Pfad). */
sealed class SelectionKey {
    data class Note(val id: String) : SelectionKey()
    data class Folder(val path: String) : SelectionKey()
}

/** Schlüssel des Eintrags oder null, wenn er nicht auswählbar ist (Kachel "+ Kurznotiz"). */
fun Entry.selectionKey(): SelectionKey? = when (this) {
    is Entry.Note -> SelectionKey.Note(summary.id)
    is Entry.Folder -> SelectionKey.Folder(item.path)
    Entry.NewNote -> null
}

/** Unveränderlicher Stand der Auswahl, damit Hintergrundarbeit nicht auf den lebenden [SelectionState] zugreift. */
data class SelectionSnapshot(val noteIds: List<String>, val folderPaths: List<String>) {
    /** Ausgewählte Ordner ohne solche, die in einem ebenfalls ausgewählten Ordner liegen; die Reihenfolge bleibt. */
    val topLevelFolders: List<String>
        get() = folderPaths.filter { f -> folderPaths.none { g -> g != f && f.startsWith("$g/") } }

    /** Falsch, wenn [target] ("" = Wurzel) ein ausgewählter Ordner oder ein Unterordner davon ist. */
    fun isValidMoveTarget(target: String): Boolean =
        folderPaths.none { f -> target == f || target.startsWith("$f/") }
}

/**
 * Auswahlzustand der Startseite (reine Logik, ohne Android-Bezug). Notizen und Ordner sind gemeinsam wählbar.
 * Ist der Modus nicht [active], ist die Auswahl leer und [toggle] wirkungslos.
 */
class SelectionState {
    private val items = LinkedHashSet<SelectionKey>()

    var active: Boolean = false
        private set

    val count: Int get() = items.size

    fun start() {
        active = true
    }

    /** Beendet den Modus und verwirft die Auswahl. */
    fun end() {
        active = false
        items.clear()
    }

    fun isSelected(key: SelectionKey): Boolean = key in items

    /** Setzt oder löst den Haken; liefert den neuen Zustand. Ohne aktiven Modus passiert nichts (false). */
    fun toggle(key: SelectionKey): Boolean {
        if (!active) return false
        return if (items.remove(key)) false else items.add(key)
    }

    /** Wählt genau [all] aus. */
    fun selectAll(all: Collection<SelectionKey>) {
        if (!active) return
        items.clear()
        items.addAll(all)
    }

    /** Verwirft Einträge, die nicht mehr in [existing] vorkommen (z. B. nach dem Neuladen). */
    fun retainOnly(existing: Collection<SelectionKey>) {
        items.retainAll(existing.toSet())
    }

    fun snapshot(): SelectionSnapshot = SelectionSnapshot(
        items.filterIsInstance<SelectionKey.Note>().map { it.id },
        items.filterIsInstance<SelectionKey.Folder>().map { it.path },
    )

    /** Speicherbare Form (für Drehung/Neustart der Activity). */
    fun encode(): ArrayList<String> = items.mapTo(ArrayList()) {
        when (it) {
            is SelectionKey.Note -> "n:${it.id}"
            is SelectionKey.Folder -> "f:${it.path}"
        }
    }

    /** Stellt Modus und Auswahl aus [encode] wieder her; unbekannte Einträge werden ignoriert. */
    fun restore(encoded: Collection<String>) {
        active = true
        items.clear()
        for (s in encoded) {
            when {
                s.startsWith("n:") -> items.add(SelectionKey.Note(s.substring(2)))
                s.startsWith("f:") -> items.add(SelectionKey.Folder(s.substring(2)))
            }
        }
    }
}
