package local.papernotes.export

import android.app.Activity
import android.content.ActivityNotFoundException
import android.media.MediaScannerConnection
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.NoteSharing
import local.papernotes.NotesLocation
import local.papernotes.R
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * Export der aktuellen Aufnahme (A4): Dialog "Teilen" oder "In Dokumente speichern". Die Bytes kommen aus dem Notizpaket
 * (`media/<uuid>.m4a`). Teilen schreibt nach `cacheDir/export` (per FileProvider freigegeben) und reicht die Datei weiter,
 * Speichern schreibt nach `Documents/PaperNotesAudio` (bestehende Dateien werden nie überschrieben).
 * Muss im UI-Thread benutzt werden. [onOverlayOpened]/[onOverlayClosed] melden den Dialog (Editor: Stift sperren).
 */
internal class AudioExport(
    private val activity: Activity,
    private val onOverlayOpened: () -> Unit = {},
    private val onOverlayClosed: () -> Unit = {},
) {
    private var busy = false

    /** Fragt das Ziel ab und exportiert [bytes] unter einem aus [title] abgeleiteten Dateinamen. */
    fun choose(title: String, bytes: ByteArray) {
        val dialog = EinkDialog.singleChoice(
            activity,
            activity.getString(R.string.audio_export_title),
            listOf(activity.getString(R.string.action_share), activity.getString(R.string.audio_export_save)),
            -1,
        ) { index -> run(title, bytes, save = index == 1) }
        onOverlayOpened()
        dialog.setOnDismissListener { onOverlayClosed() }
    }

    private fun run(title: String, bytes: ByteArray, save: Boolean) {
        if (busy) return
        if (save && !NotesLocation.hasAccess(activity)) {
            toast(R.string.error_no_storage)
            return
        }
        busy = true
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val file = try {
                if (save) saveToDocuments(title, bytes) else writeToCache(title, bytes)
            } catch (e: IOException) {
                null
            }
            activity.runOnUiThread {
                busy = false
                if (activity.isDestroyed) return@runOnUiThread
                if (file == null) toast(R.string.error_audio_export) else finish(file, save)
            }
            executor.shutdown()
        }
    }

    /** Schreibt die Bytes nach `cacheDir/export` (vorher geleert). */
    private fun writeToCache(title: String, bytes: ByteArray): File? {
        val dir = File(activity.cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, ExportFileNames.next(title, HashSet(), EXTENSION))
        target.writeBytes(bytes)
        return target
    }

    /** Schreibt die Bytes atomar nach `Documents/PaperNotesAudio` (Name eindeutig gegenüber vorhandenen Dateien). */
    private fun saveToDocuments(title: String, bytes: ByteArray): File? {
        val out = ExportLocation.audioDirectory()
        if (!out.isDirectory && !out.mkdirs()) return null
        val used = (out.list() ?: emptyArray()).mapTo(HashSet()) { it.lowercase() }
        val name = ExportFileNames.next(title, used, EXTENSION)
        val target = File(out, name)
        val tmp = File(out, ".$name.tmp")
        val tmpSource = File(activity.cacheDir, "audio-export.tmp")
        try {
            tmpSource.writeBytes(bytes)
            Files.copy(tmpSource.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (!tmp.renameTo(target)) return null
        } finally {
            tmpSource.delete()
            if (tmp.exists()) tmp.delete()
        }
        MediaScannerConnection.scanFile(activity, arrayOf(target.path), arrayOf(MIME_TYPE), null)
        return target
    }

    private fun finish(file: File, save: Boolean) {
        if (save) {
            Toast.makeText(activity, activity.getString(R.string.audio_export_saved, "Documents/${ExportLocation.AUDIO_FOLDER_NAME}"), Toast.LENGTH_LONG).show()
            return
        }
        try {
            activity.startActivity(NoteSharing.chooserIntent(activity, listOf(file), activity.getString(R.string.audio_export_chooser), MIME_TYPE))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_share)
        } catch (e: IllegalArgumentException) {
            toast(R.string.error_share)
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val EXTENSION = ".m4a"
        const val MIME_TYPE = "audio/mp4"
    }
}
