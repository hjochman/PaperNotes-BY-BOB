package local.papernotes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

/**
 * Speicherort der Notizen im normalen Benutzerbereich: `Documents/PaperNotes` (sichtbar im Dateimanager,
 * für Nextcloud-Ordnersync erreichbar, überlebt eine Deinstallation).
 *
 * Grundlage: Android 10 (API 29) mit `targetSdk 29`, `requestLegacyExternalStorage="true"` und der
 * Laufzeitberechtigung `WRITE_EXTERNAL_STORAGE`. Hebt man `targetSdk` auf 30 oder höher, wirkt das
 * Legacy-Flag nicht mehr, dann wäre `MANAGE_EXTERNAL_STORAGE` oder der Storage Access Framework nötig.
 */
internal object NotesLocation {
    const val FOLDER_NAME = "PaperNotes"
    const val PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** Wahr, wenn die Berechtigung erteilt und der gemeinsame Speicher eingehängt ist. */
    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /** Nur nach positivem [hasAccess] aufrufen. Legt den Ordner bei Bedarf an. */
    fun root(@Suppress("UNUSED_PARAMETER") context: Context): File = location().also { it.mkdirs() }

    /** Der Speicherort ohne Zugriffsprüfung und ohne Anlegen, nur zur Anzeige (z. B. in den Einstellungen). */
    fun location(): File {
        @Suppress("DEPRECATION") // Unter API 29 mit Legacy-Zugriff der vorgesehene Weg.
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documents, FOLDER_NAME)
    }
}
