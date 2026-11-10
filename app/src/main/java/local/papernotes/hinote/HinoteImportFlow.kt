package local.papernotes.hinote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import local.papernotes.EinkDialog
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.storage.NoteRepository
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ablauf "HiNote-Notizen importieren" für die Startseite: Dateiauswahl per Storage Access Framework
 * (`ACTION_OPEN_DOCUMENT`, mehrere Dateien, `.hinote` hat keinen eigenen MIME-Typ) oder direktes, rekursives Durchsuchen von
 * `Documents/hinote/custom` (Unterverzeichnisse bis Tiefe 3 -> Unterordner von `HiNote-Import`, Regeln in [HinoteImportPlanner]). Der Import läuft in einem Hintergrund-Thread mit Textfortschritt ("Notiz 3/14", keine Animation,
 * abbrechbar); danach zeigt ein Dialog die Zählung. Muss im UI-Thread benutzt werden.
 */
internal class HinoteImportFlow(private val activity: Activity, private val repo: () -> NoteRepository?, private val onFinished: () -> Unit) {
    private class Running(val cancelled: AtomicBoolean, val progress: EinkDialog.Progress)

    private var running: Running? = null

    /** Öffnet die Dateiauswahl; das Ergebnis gehört an [onPicked]. */
    fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_PICK)
        } catch (e: android.content.ActivityNotFoundException) {
            EinkDialog.message(activity, activity.getString(R.string.import_title), activity.getString(R.string.import_error_picker), activity.getString(R.string.dialog_ok))
        }
    }

    /** Auswertung von `onActivityResult`; true, wenn der Aufruf zu diesem Ablauf gehörte. */
    fun onPicked(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip -> for (i in 0 until minOf(clip.itemCount, MAX_FILES)) clip.getItemAt(i).uri?.let { uris.add(it) } }
        val sources = uris.distinct().map { uri -> HinoteImportSource(uri.toString()) { activity.contentResolver.openInputStream(uri) } }
        start { sources }
        return true
    }

    /** Durchsucht `Documents/hinote/custom` rekursiv nach `*.hinote` (Datei-API, Legacy-Speicherzugriff, Tiefe 3, keine Symlinks) und importiert alle. */
    fun importFromFolder() {
        if (!NotesLocation.hasAccess(activity)) {
            EinkDialog.message(activity, activity.getString(R.string.import_title), activity.getString(R.string.error_no_storage), activity.getString(R.string.dialog_ok))
            return
        }
        start { scanFolder() }
    }

    fun cancel() {
        running?.let {
            it.cancelled.set(true)
            it.progress.dismiss()
        }
        running = null
    }

    private fun scanFolder(): List<HinoteImportSource> {
        @Suppress("DEPRECATION")
        val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "hinote"), "custom")
        return HinoteFolderScanner.scan(dir, MAX_FILES).map { s ->
            HinoteImportSource(s.file.name, HinoteImportPlanner.folderFor(s.relativeDir), s.lastModified, s.relativeDir.isNotEmpty()) { s.file.inputStream() }
        }
    }

    private fun start(sources: () -> List<HinoteImportSource>) {
        if (running != null) return
        val r = repo()
        if (r == null) {
            EinkDialog.message(activity, activity.getString(R.string.import_title), activity.getString(R.string.error_no_storage), activity.getString(R.string.dialog_ok))
            return
        }
        val cancelled = AtomicBoolean(false)
        val progress = EinkDialog.progress(activity, activity.getString(R.string.import_progress_title), activity.getString(R.string.export_progress_start)) {
            cancelled.set(true)
            running = null
        }
        running = Running(cancelled, progress)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            var empty = false
            val report = try {
                val list = sources()
                empty = list.isEmpty()
                if (empty) null else HinoteImportRunner.run(
                    list, r, activity.getString(R.string.new_note_title),
                    isCancelled = { cancelled.get() },
                    onProgress = { done, total ->
                        val text = activity.getString(R.string.export_progress_note, done, total)
                        activity.runOnUiThread { if (!cancelled.get()) progress.setText(text) }
                    },
                )
            } catch (e: RuntimeException) {
                null
            } catch (e: java.io.IOException) {
                null
            }
            finishWith(report, empty, progress, cancelled)
            executor.shutdown()
        }
    }

    private fun finishWith(report: HinoteImportReport?, empty: Boolean, progress: EinkDialog.Progress, cancelled: AtomicBoolean) {
        activity.runOnUiThread {
            if (activity.isDestroyed || cancelled.get()) return@runOnUiThread
            running = null
            progress.dismiss()
            showResult(report, empty)
            onFinished()
        }
    }

    private fun showResult(report: HinoteImportReport?, empty: Boolean) {
        val title = activity.getString(R.string.import_title)
        val ok = activity.getString(R.string.dialog_ok)
        if (report == null) {
            val msg = if (empty) R.string.import_error_none else R.string.import_error_failed
            EinkDialog.message(activity, title, activity.getString(msg), ok)
            return
        }
        val sb = StringBuilder(activity.getString(R.string.import_result, report.imported, report.skipped, report.withWarnings))
        if (report.failed > 0) sb.append('\n').append(activity.getString(R.string.import_result_failed, report.failed))
        if (report.imported > 0) sb.append("\n\n").append(activity.getString(R.string.import_result_folder, HinoteImporter.TARGET_FOLDER))
        EinkDialog.message(activity, title, sb.toString(), ok)
    }

    companion object {
        const val REQUEST_PICK = 4711
        private const val MAX_FILES = 500
    }
}
