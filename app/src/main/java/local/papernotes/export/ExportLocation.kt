package local.papernotes.export

import android.os.Environment
import java.io.File

/**
 * Zielordner für gespeicherte Exporte: PDFs in `Documents/PaperNotesPDF`, Aufnahmen in `Documents/PaperNotesAudio`,
 * bewusst außerhalb des Notizordners `Documents/PaperNotes`.
 */
internal object ExportLocation {
    const val FOLDER_NAME = "PaperNotesPDF"
    const val AUDIO_FOLDER_NAME = "PaperNotesAudio"

    /** Der PDF-Ordner ohne Zugriffsprüfung und ohne Anlegen. */
    fun directory(): File = File(documents(), FOLDER_NAME)

    /** Der Ordner für exportierte Aufnahmen ohne Zugriffsprüfung und ohne Anlegen. */
    fun audioDirectory(): File = File(documents(), AUDIO_FOLDER_NAME)

    private fun documents(): File {
        @Suppress("DEPRECATION") // Unter API 29 mit Legacy-Zugriff der vorgesehene Weg (wie NotesLocation).
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    }
}
