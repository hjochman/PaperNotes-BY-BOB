package local.papernotes.editor

import local.papernotes.model.Stroke

/**
 * Eigener Undo/Redo-Verlauf einer Seite auf Strich-Ebene (reine Logik, kein Android-Bezug).
 *
 * Warum eigener Verlauf: `HWEditView.setTrackList()` und `clear()` leeren den Framework-Verlauf (belegt in Spike 2),
 * und die App setzt die Tracks bei Seitenwechsel neu.
 *
 * Speichereffizienz: Statt kompletter Momentaufnahmen je Schritt speichert jeder Schritt nur die Differenz zum
 * vorherigen Zustand ([Edit]: ab Position `index` wurden die Striche `removed` durch `added` ersetzt). Die Differenz
 * wird beim [record] aus gemeinsamem Anfang und gemeinsamem Ende der alten und neuen Strichliste bestimmt. Hinzufügen,
 * Radieren (ein Strich wird in Teile zerlegt), Löschen und Verschieben mit dem Lasso sind damit dieselbe Operation
 * "neue Strichliste melden". Unveränderte Striche vor und nach der Änderung behalten ihr Objekt (strukturelles Teilen
 * unveränderlicher [Stroke]-Objekte); ein Schritt kostet nur Speicher für die geänderten Striche.
 *
 * Grenzfall: Ändert ein Schritt weit auseinanderliegende Striche (Radierer über mehrere Striche), umfasst die Differenz
 * auch die unveränderten Striche dazwischen. Das ist korrekt, nur etwas speicherhungriger; das Limit begrenzt es.
 *
 * Nicht threadsicher; wird nur im UI-Thread benutzt.
 */
class PageHistory(initial: List<Stroke>, private val limit: Int = DEFAULT_LIMIT) {
    private class Edit(val index: Int, val removed: List<Stroke>, val added: List<Stroke>)

    private var current: List<Stroke> = initial.toList()
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    init {
        require(limit > 0) { "limit" }
    }

    /** Aktuelle Strichliste (unveränderlich; wird nie nachträglich verändert). */
    val strokes: List<Stroke> get() = current

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Meldet die neue Strichliste nach einer Änderung. Gibt false zurück (und ändert nichts), wenn sie dem aktuellen
     * Zustand entspricht. Eine echte Änderung verwirft den Redo-Zweig.
     */
    fun record(newStrokes: List<Stroke>): Boolean {
        val old = current
        val oldN = old.size
        val newN = newStrokes.size
        val maxCommon = minOf(oldN, newN)
        var prefix = 0
        while (prefix < maxCommon && same(old[prefix], newStrokes[prefix])) prefix++
        var suffix = 0
        while (suffix < maxCommon - prefix && same(old[oldN - 1 - suffix], newStrokes[newN - 1 - suffix])) suffix++
        if (prefix == oldN && prefix == newN) return false

        val removed = old.subList(prefix, oldN - suffix).toList()
        val added = newStrokes.subList(prefix, newN - suffix).toList()
        val next = ArrayList<Stroke>(newN)
        next.addAll(old.subList(0, prefix)) // gemeinsames Objekt-Teilen statt neuer Kopien
        next.addAll(added)
        next.addAll(old.subList(oldN - suffix, oldN))
        current = next

        undoStack.addLast(Edit(prefix, removed, added))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        return true
    }

    /** Macht den letzten Schritt rückgängig und gibt die neue Strichliste zurück, oder null ohne Verlauf. */
    fun undo(): List<Stroke>? {
        val edit = undoStack.removeLastOrNull() ?: return null
        current = replace(current, edit.index, edit.added.size, edit.removed)
        redoStack.addLast(edit)
        return current
    }

    /** Stellt den zuletzt rückgängig gemachten Schritt wieder her, oder null ohne Redo-Zweig. */
    fun redo(): List<Stroke>? {
        val edit = redoStack.removeLastOrNull() ?: return null
        current = replace(current, edit.index, edit.removed.size, edit.added)
        undoStack.addLast(edit)
        return current
    }

    private fun replace(list: List<Stroke>, index: Int, removeCount: Int, insert: List<Stroke>): List<Stroke> {
        val out = ArrayList<Stroke>(list.size - removeCount + insert.size)
        out.addAll(list.subList(0, index))
        out.addAll(insert)
        out.addAll(list.subList(index + removeCount, list.size))
        return out
    }

    private fun same(a: Stroke, b: Stroke) = a === b || a == b

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
