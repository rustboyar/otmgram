package dev.otmgram.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Opens a downloaded file in another app, e.g. a PDF viewer. */
fun openFile(context: Context, path: String, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        // A path outside the folders shared in file_paths.xml.
        Log.w("openFile", "Can't share $path", e)
        Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
    }
}

/** "2.4 MB", "830 KB", "120 B". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
