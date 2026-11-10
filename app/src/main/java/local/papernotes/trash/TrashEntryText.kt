package local.papernotes.trash

/** Textbausteine eines Papierkorb-Eintrags. Die Beschriftungen kommen lokalisiert von außen; hier steht nur das Zusammensetzen. */
object TrashEntryText {
    const val SEPARATOR = " · "

    /** Anzeigename des Ursprungsordners; die oberste Ebene (leerer Pfad) heißt [rootLabel]. */
    fun folderName(folderPath: String, rootLabel: String): String = folderPath.ifEmpty { rootLabel }

    /** Detailzeile unter dem Titel: Ordner, Löschdatum und Seitenzahl, getrennt durch [SEPARATOR]; leere Teile entfallen. */
    fun details(folder: String, deleted: String, pages: String): String =
        listOf(folder, deleted, pages).filter { it.isNotBlank() }.joinToString(SEPARATOR)
}
