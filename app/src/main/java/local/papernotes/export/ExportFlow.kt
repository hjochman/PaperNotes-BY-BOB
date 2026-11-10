package local.papernotes.export

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.media.MediaScannerConnection
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.NoteSharing
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.model.Note
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ablauf "Exportieren" für Startseite und Editor: Format wählen ("Exportieren als" -> PDF), Ziel wählen (Teilen oder
 * In Dokumente speichern), PDFs im Hintergrund-Thread erzeugen (Fortschritt als Text "Seite 3/12", keine Animation).
 * Je Notiz eine Datei. Hat mindestens eine der Notizen Tonaufnahmen, fragt der Ablauf vor dem Erzeugen "Aufnahmen mitnehmen?"
 * (B1): bei "ja" kommen die Aufnahmen als eigene Dateien neben das PDF (`<Notizname> - <Aufnahmename>.m4a`); ins PDF selbst
 * werden sie nicht gezeichnet (`PdfDocument` kennt keine Dateianhänge). Ohne Aufnahmen entfällt die Frage. Zuerst wird nach `cacheDir/export` geschrieben (vor jedem Export geleert); Teilen reicht diese Dateien
 * per FileProvider weiter, Speichern kopiert sie nach `Documents/PaperNotesPDF` (bestehende Dateien werden nie überschrieben).
 * Muss im UI-Thread benutzt werden. [onOverlayOpened]/[onOverlayClosed] melden jeden Dialog (Editor: Stift sperren).
 */
internal class ExportFlow(
    private val activity: Activity,
    private val onOverlayOpened: () -> Unit = {},
    private val onOverlayClosed: () -> Unit = {},
) {
    /** Eine zu exportierende Notiz; [load] läuft im Hintergrund-Thread und liefert null, wenn die Notiz nicht lesbar ist. */
    class Source(val title: String, val load: () -> Note?)

    private enum class Mode { SHARE, SAVE }

    private class Running(val cancelled: AtomicBoolean, val progress: EinkDialog.Progress)

    private var running: Running? = null

    /** Dialog "Exportieren als" (derzeit nur PDF), danach [chooseTarget]. */
    fun chooseFormat(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        track(
            EinkDialog.singleChoice(activity, activity.getString(R.string.export_as_title), listOf(activity.getString(R.string.export_format_pdf)), -1) {
                chooseTarget(sources)
            },
        )
    }

    /** Erzeugt die PDFs und reicht sie direkt weiter (Menüpunkt "Teilen"), ohne Formatwahl und Zielwahl. */
    fun share(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        run(sources, Mode.SHARE)
    }

    /** Erzeugt die PDFs und speichert sie direkt nach `Documents/PaperNotesPDF`, ohne Zielwahl. */
    fun save(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        run(sources, Mode.SAVE)
    }

    /** Dialog mit "Teilen" und "In Dokumente speichern". */
    fun chooseTarget(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        track(
            EinkDialog.singleChoice(
                activity,
                activity.getString(R.string.export_target_title),
                listOf(activity.getString(R.string.action_share), activity.getString(R.string.export_save)),
                -1,
            ) { index -> run(sources, if (index == 0) Mode.SHARE else Mode.SAVE) },
        )
    }

    /** Bricht einen laufenden Export ab und schließt den Fortschrittsdialog (z. B. in `onDestroy`). */
    fun cancel() {
        running?.let {
            it.cancelled.set(true)
            it.progress.dismiss()
        }
        running = null
    }

    private fun track(dialog: Dialog) {
        onOverlayOpened()
        dialog.setOnDismissListener { onOverlayClosed() }
    }

    private fun run(sources: List<Source>, mode: Mode) = launch(sources, mode, null)

    /**
     * Startet den Export. [withAudio] = null: zuerst prüfen, ob eine Notiz Aufnahmen hat; wenn ja, fragt ein Dialog
     * "Aufnahmen mitnehmen?" und der Ablauf beginnt danach mit der Antwort, sonst läuft er ohne Frage weiter.
     */
    private fun launch(sources: List<Source>, mode: Mode, withAudio: Boolean?) {
        if (running != null) return
        if (mode == Mode.SAVE && !NotesLocation.hasAccess(activity)) {
            toast(R.string.error_no_storage)
            return
        }
        val cancelled = AtomicBoolean(false)
        val progress = EinkDialog.progress(
            activity,
            activity.getString(R.string.export_progress_title),
            activity.getString(R.string.export_progress_start),
        ) {
            cancelled.set(true)
            running = null
        }
        track(progress.dialog)
        running = Running(cancelled, progress)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            var list = sources
            var audio = withAudio
            if (audio == null) {
                val scan = try {
                    scan(sources, cancelled)
                } catch (e: Exception) {
                    null
                }
                if (scan == null) {
                    activity.runOnUiThread { fail(cancelled, progress) }
                    executor.shutdown()
                    return@execute
                }
                list = scan.sources
                if (scan.hasAudio) {
                    activity.runOnUiThread {
                        if (activity.isDestroyed || cancelled.get()) return@runOnUiThread
                        running = null
                        progress.dismiss()
                        askAudio(list, mode)
                    }
                    executor.shutdown()
                    return@execute
                }
                audio = false
            }
            val outcome = try {
                produce(list, mode, audio, cancelled) { text -> activity.runOnUiThread { if (!cancelled.get()) progress.setText(text) } }
            } catch (e: Exception) {
                Outcome.Failed
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || cancelled.get()) return@runOnUiThread
                running = null
                progress.dismiss()
                finish(outcome, mode)
            }
            executor.shutdown()
        }
    }

    private fun fail(cancelled: AtomicBoolean, progress: EinkDialog.Progress) {
        if (activity.isDestroyed || cancelled.get()) return
        running = null
        progress.dismiss()
        toast(R.string.error_export)
    }

    /** Ergebnis der Vorprüfung; bei genau einer Notiz bleibt sie geladen, damit sie nicht ein zweites Mal gelesen wird. */
    private class Scan(val sources: List<Source>, val hasAudio: Boolean)

    /** Hintergrund-Thread: prüft, ob eine der Notizen Tonaufnahmen mit vorhandener Datei hat. Null bei Abbruch. */
    private fun scan(sources: List<Source>, cancelled: AtomicBoolean): Scan? {
        if (sources.size == 1) {
            val note = sources[0].load()
            return Scan(listOf(Source(sources[0].title) { note }), note != null && hasAudio(note))
        }
        for (source in sources) {
            if (cancelled.get()) return null
            val note = source.load() ?: continue
            if (hasAudio(note)) return Scan(sources, true)
        }
        return Scan(sources, false)
    }

    private fun hasAudio(note: Note): Boolean =
        note.pageIds.any { id -> note.pages[id]?.audios?.any { note.media.containsKey(it.filePath) } == true }

    private fun askAudio(sources: List<Source>, mode: Mode) {
        val items = listOf(activity.getString(R.string.export_audio_no), activity.getString(R.string.export_audio_yes))
        track(
            EinkDialog.singleChoice(activity, activity.getString(R.string.export_audio_title), items, -1) { index ->
                launch(sources, mode, index == 1)
            },
        )
    }

    private sealed class Outcome {
        object Failed : Outcome()
        class Done(val files: List<File>, val failed: Int) : Outcome() {
            /** Anzahl der PDF-Dateien (ohne mitexportierte Aufnahmen). */
            val pdfCount: Int get() = files.count { it.name.endsWith(".pdf", ignoreCase = true) }
        }
    }

    /** Hintergrund-Thread: erzeugt die PDFs; bei [Mode.SAVE] liegen die Ergebnisdateien danach in Documents. */
    private fun produce(sources: List<Source>, mode: Mode, withAudio: Boolean, cancelled: AtomicBoolean, progress: (String) -> Unit): Outcome {
        val dir = File(activity.cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs()) return Outcome.Failed
        dir.listFiles()?.forEach { it.delete() }
        val used = HashSet<String>()
        val names = sources.map { ExportFileNames.next(it.title, used) }
        val exporter = PdfExporter(activity)
        val done = ArrayList<File>()
        var failed = 0
        for ((i, source) in sources.withIndex()) {
            if (cancelled.get()) return Outcome.Failed
            val note = source.load()
            if (note == null) {
                failed++
                continue
            }
            val target = File(dir, names[i])
            val result = exporter.write(
                note,
                target,
                onPage = { page, total ->
                    val pageText = activity.getString(R.string.export_progress_page, page, total)
                    progress(if (sources.size > 1) activity.getString(R.string.export_progress_note, i + 1, sources.size) + "\n" + pageText else pageText)
                },
                isCancelled = { cancelled.get() },
            )
            when (result) {
                PdfExporter.Result.OK -> {
                    done.add(target)
                    // Aufnahmen hängen als Dateianhang an der passenden PDF-Seite; nur wenn das nicht geht, liegen sie als eigene Dateien daneben.
                    if (withAudio && !attachAudio(note, target)) done.addAll(writeAudio(note, names[i].removeSuffix(".pdf"), dir, used))
                }
                PdfExporter.Result.CANCELLED -> return Outcome.Failed
                PdfExporter.Result.FAILED -> failed++
            }
        }
        if (done.isEmpty()) return Outcome.Failed
        if (mode == Mode.SHARE) return Outcome.Done(done, failed)
        return save(done, dir, failed)
    }

    /** Hängt die Aufnahmen von [note] an die Seiten von [pdf]. Ohne Aufnahmen true; false, wenn das PDF unverändert blieb. */
    private fun attachAudio(note: Note, pdf: File): Boolean {
        val items = ArrayList<PdfAttachments.Item>()
        for ((index, page) in note.pageIds.mapNotNull { note.pages[it] }.withIndex()) {
            for (audio in page.audios) {
                val bytes = note.media[audio.filePath] ?: continue
                val ext = audio.filePath.substringAfterLast('.', "m4a").lowercase()
                val title = audio.title.ifBlank { "Audio" }
                val mime = if (ext == "m4a" || ext == "mp4") "audio/mp4" else "application/octet-stream"
                items.add(PdfAttachments.Item(index, audio.filePath, "$title.$ext", title, mime, bytes))
            }
        }
        if (items.isEmpty()) return true
        return PdfAttachments.attach(pdf, note.pageIds.count { note.pages.containsKey(it) }, items)
    }

    /** Schreibt die Aufnahmen von [note] als `<base> - <Name>.<Endung>` nach [dir]. Nicht schreibbare Aufnahmen entfallen. */
    private fun writeAudio(note: Note, base: String, dir: File, used: MutableSet<String>): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (page in note.pageIds.mapNotNull { note.pages[it] }) {
            for (audio in page.audios) {
                if (!seen.add(audio.filePath)) continue
                val bytes = note.media[audio.filePath] ?: continue
                val ext = "." + audio.filePath.substringAfterLast('.', "m4a")
                val name = ExportFileNames.next("$base - ${audio.title.ifBlank { "Audio" }}", used, ext)
                val file = File(dir, name)
                try {
                    file.writeBytes(bytes)
                    out.add(file)
                } catch (e: IOException) {
                    file.delete()
                }
            }
        }
        return out
    }

    /** Kopiert [files] nach `Documents/PaperNotesPDF` (Namen eindeutig gegenüber vorhandenen Dateien), räumt den Cache auf. */
    private fun save(files: List<File>, cacheDir: File, failed: Int): Outcome {
        val out = ExportLocation.directory()
        if (!out.isDirectory && !out.mkdirs()) return Outcome.Failed
        val used = (out.list() ?: emptyArray()).mapTo(HashSet()) { it.lowercase() }
        val saved = ArrayList<File>()
        var copyFailed = failed
        for (file in files) {
            val ext = "." + file.name.substringAfterLast('.')
            val name = ExportFileNames.next(file.name.removeSuffix(ext), used, ext)
            val target = File(out, name)
            val tmp = File(out, ".$name.tmp")
            try {
                Files.copy(file.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (tmp.renameTo(target)) saved.add(target) else copyFailed++
            } catch (e: IOException) {
                copyFailed++
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
        cacheDir.listFiles()?.forEach { it.delete() }
        if (saved.isEmpty()) return Outcome.Failed
        MediaScannerConnection.scanFile(activity, saved.map { it.path }.toTypedArray(), Array(saved.size) { mimeOf(saved[it]) }, null)
        return Outcome.Done(saved, copyFailed)
    }

    private fun finish(outcome: Outcome, mode: Mode) {
        if (outcome !is Outcome.Done) {
            toast(R.string.error_export)
            return
        }
        if (outcome.failed > 0) toast(R.string.error_export_partial)
        if (mode == Mode.SAVE) {
            val n = outcome.pdfCount
            Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.export_saved, n, n, "Documents/${ExportLocation.FOLDER_NAME}"), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val title = activity.getString(if (outcome.files.size == 1) R.string.export_chooser else R.string.export_chooser_multi)
            val mime = if (outcome.files.all { it.name.endsWith(".pdf", ignoreCase = true) }) "application/pdf" else "*/*"
            activity.startActivity(NoteSharing.chooserIntent(activity, outcome.files, title, mime))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_share)
        } catch (e: IllegalArgumentException) {
            toast(R.string.error_share)
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    private fun mimeOf(file: File): String = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "audio/mp4"

    companion object {
        private const val STALE_AFTER_MS = 60 * 60 * 1000L

        /**
         * Löscht geteilte Exportdateien (PDF/Audio) in `cacheDir/export`, die älter als eine Stunde sind. Die Zielapp liest die Datei
         * erst nach dem Auswahldialog; sofortiges Löschen nach dem Teilen würde sie ihr wegnehmen. Nicht im UI-Thread aufrufen.
         */
        fun deleteStaleShares(cacheDir: File) {
            val limit = System.currentTimeMillis() - STALE_AFTER_MS
            File(cacheDir, "export").listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }
}
