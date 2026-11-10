package local.papernotes.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import local.papernotes.MainActivity
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.editor.EditorActivity
import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Ziel des "+"-Knopfs im Widget (nicht exportiert, nur per PendingIntent erreichbar): fragt nach dem Ordner (oberste
 * Ebene ist vorgewählt), legt dann eine Notiz an wie "Kurznotiz" der Startseite (Titel "Notiz <Datum>", eine leere
 * Seite) und öffnet sie im Editor. Gibt es keine Ordner, entfällt die Frage. Ohne Speicherzugriff öffnet sich die
 * Startseite, die um die Berechtigung bittet. Durchsichtiges Fenster, keine Animation.
 */
class NewNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!NotesLocation.hasAccess(this)) {
            openHome()
            return
        }
        val root = NotesLocation.root(this)
        val loader = Executors.newSingleThreadExecutor()
        loader.execute {
            val folders = try {
                (NoteRepository(root).listAllFolders() as? PnoteResult.Ok)?.value.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (folders.isEmpty()) createNote(root, "") else askFolder(root, folders)
            }
        }
        loader.shutdown()
    }

    /** Ordnerfrage: Eintrag 0 ist die oberste Ebene (Standard). Abbrechen beendet ohne Notiz. */
    private fun askFolder(root: java.io.File, folders: List<String>) {
        val targets = listOf("") + folders
        val labels = targets.map { if (it.isEmpty()) getString(R.string.home_all_notes) else it }.toTypedArray()
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_note_in)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ -> createNote(root, targets[selected]) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun createNote(root: java.io.File, folder: String) {
        val title = getString(R.string.new_note_title) + " " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
        val io = Executors.newSingleThreadExecutor()
        io.execute {
            val note = NoteFactory.newNote(title, folder)
            val ok = try {
                NoteRepository(root).save(note) is PnoteResult.Ok
            } catch (e: Exception) {
                false
            }
            if (ok) NotesWidgetUpdater.requestUpdate(this, force = true)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    startActivity(EditorActivity.intent(this, note.id))
                } else {
                    Toast.makeText(this, R.string.error_create, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
        io.shutdown()
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Suppress("DEPRECATION") // Keine Übergangsanimation (E-Ink).
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
