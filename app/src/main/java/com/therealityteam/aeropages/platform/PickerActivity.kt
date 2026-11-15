package com.therealityteam.aeropages.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.ui.OpenRequests
import com.therealityteam.aeropages.ui.SaveRequests

/**
 * A transparent activity that shows the system document picker (Storage Access Framework), so
 * files on the device, on cloud providers and on network shares can be opened and written
 * without the all-files permission. The picked document flows back through [OpenRequests]
 * (open) or [SaveRequests] (save a copy) and the activity finishes at once.
 */
class PickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE)
        if (mode == MODE_SAVE) {
            val session = intent.getStringExtra(EXTRA_SESSION) ?: ""
            val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument(intent.getStringExtra(EXTRA_MIME) ?: "*/*")) { uri: Uri? ->
                if (uri != null) SaveRequests.post(session, uri)
                finish()
            }
            if (savedInstanceState == null) launcher.launch(intent.getStringExtra(EXTRA_NAME) ?: "document")
        } else {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    OpenRequests.submit(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                finish()
            }
            if (savedInstanceState == null) launcher.launch(OPENABLE_TYPES)
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"
        private const val MODE_OPEN = "open"
        private const val MODE_SAVE = "save"

        /** Some providers only supply a generic MIME type. The importer checks the display name in that case. */
        val OPENABLE_TYPES = (DeskFiles.documentMimeTypes.keys + "application/octet-stream").toTypedArray()

        fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            else -> "application/octet-stream"
        }

        /** Show the system picker; the chosen document arrives through [OpenRequests] like a file-manager open. */
        fun openDocument(context: Context) {
            context.startActivity(Intent(context, PickerActivity::class.java).putExtra(EXTRA_MODE, MODE_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Ask where to write a copy; the target arrives through [SaveRequests] for the session with [sessionId]. */
        fun createDocument(context: Context, sessionId: String, suggestedName: String, extension: String) {
            context.startActivity(
                Intent(context, PickerActivity::class.java).putExtra(EXTRA_MODE, MODE_SAVE).putExtra(EXTRA_SESSION, sessionId)
                    .putExtra(EXTRA_NAME, suggestedName).putExtra(EXTRA_MIME, mimeFor(extension)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
