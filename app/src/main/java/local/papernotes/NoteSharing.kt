package local.papernotes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Teilen von Exportdateien (PDF, Audio) aus `cacheDir/export` (nur dieser Ordner ist per FileProvider freigegeben, siehe
 * `res/xml/file_paths.xml`), Weitergabe per `ACTION_SEND` bzw. `ACTION_SEND_MULTIPLE` mit temporärer Leseberechtigung.
 */
internal object NoteSharing {
    /**
     * Auswahldialog-Intent für [files] (mindestens eine Datei) mit dem Medientyp [mimeType]. Wirft [IllegalArgumentException],
     * wenn eine Datei außerhalb des freigegebenen Ordners (`export`) liegt.
     */
    fun chooserIntent(context: Context, files: List<File>, chooserTitle: String, mimeType: String = "application/octet-stream"): Intent {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("", uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
