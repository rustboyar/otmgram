package dev.otmgram.core.log

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.otmgram.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The logs as one zip to send, e.g. to yourself in Telegram. */
object LogArchive {

    /** Zips the app's and TDLib's logs with a short header about the device. */
    suspend fun create(context: Context): File = withContext(Dispatchers.IO) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val dir = File(context.cacheDir, "logs").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // Only the newest archive is kept.
        }
        val zip = File(dir, "otmgram-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("about.txt"))
            out.write(header(stamp).toByteArray())
            out.closeEntry()
            AppLog.folder?.listFiles()?.sortedBy { it.name }?.forEach { file ->
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip
    }

    /** The share sheet for [zip]. */
    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Otmgram logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share logs")
    }

    private fun header(stamp: String) = """
        Otmgram ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}
        ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        Exported $stamp
    """.trimIndent() + "\n"
}
