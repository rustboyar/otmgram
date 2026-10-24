package dev.otmgram.ui.media

import androidx.core.net.toUri
import android.webkit.MimeTypeMap
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import java.io.File

private fun typeOf(path: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase())

/** Opens a web, mail or phone link (see safeUrl) in the app for it. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_link_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** Opens a downloaded file in another app, e.g. a PDF viewer. */
fun openFile(context: Context, path: String, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            // The type from the file's extension where known, not the one the sender gave, which
            // could steer the file to an app that doesn't expect it.
            .setDataAndType(uri, typeOf(path) ?: mimeType.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_no_app, Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        // A path outside the folders shared in file_paths.xml.
        // Without the exception: its message has the path, and so the file's name.
        AppLog.w("openFile", "Can't share a file outside the shared folders")
        Toast.makeText(context, R.string.open_failed, Toast.LENGTH_SHORT).show()
    }
}

/** "2.4 MB", "830 KB", "120 B". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
