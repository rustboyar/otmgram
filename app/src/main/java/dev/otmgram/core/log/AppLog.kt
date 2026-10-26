package dev.otmgram.core.log

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The app's log: to logcat, and to a small rotating file on the phone that Settings can
 * share. For finding out what happened on a real device: starts, logins, connection,
 * background checks, notifications, failures and crashes.
 *
 * Never log message text, names or phone numbers.
 */
@SuppressLint("LogNotTimber") // This is the app's logger; it writes to logcat on purpose.
object AppLog {
    /** TDLib's log, in the same folder. Unlike this one, it can contain message text and names. */
    const val TDLIB_FILE = "tdlib.log"

    private const val MAX_BYTES = 512 * 1024L
    private const val FILE = "app.log"
    private const val OLD_FILE = "app.log.1"

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "AppLog").apply { isDaemon = true } }

    @Volatile
    private var directory: File? = null

    /** Where the logs are, TDLib's included; null before [init]. */
    val folder: File? get() = directory

    /** Starts writing to files and records crashes. Call first thing in Application.onCreate. */
    fun init(context: Context) {
        directory = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Written right away: the process is about to die.
            append(line('E', "Crash", "Uncaught in thread ${thread.name}", trace(e)))
            previous?.uncaughtException(thread, e)
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue(line('I', tag, message, ""))
    }

    fun w(tag: String, message: String, e: Throwable? = null) {
        val trace = trace(e)
        Log.w(tag, message + "\n" + trace)
        enqueue(line('W', tag, message, trace))
    }

    fun e(tag: String, message: String, e: Throwable? = null) {
        val trace = trace(e)
        Log.e(tag, message + "\n" + trace)
        enqueue(line('E', tag, message, trace))
    }

    private fun line(level: Char, tag: String, message: String, trace: String): String =
        "${LocalDateTime.now().format(time)} $level $tag: $message\n$trace"

    /** [e]'s stack trace, with file paths and content URIs out of its messages. */
    private fun trace(e: Throwable?): String =
        e?.let { redact(StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString()) }.orEmpty()

    /**
     * [text] without file paths and content URIs: exception messages can name files, e.g. a
     * document's original name when saving it to Downloads failed.
     */
    internal fun redact(text: String): String = text
        .replace(CONTENT_URI, "<uri>")
        .replace(FILE_PATH, "<path>")

    private val CONTENT_URI = Regex("""(?:content|file)://\S+""")
    // Names can have spaces: up to the end of the line, or the " (reason)" Android adds.
    private val FILE_PATH = Regex("""(?m)(?<![\w.])/(?:data|storage|sdcard|mnt)/.*?(?= \(|$)""")

    private fun enqueue(text: String) {
        if (directory == null) return
        writer.execute { append(text) }
    }

    @Synchronized
    private fun append(text: String) {
        val dir = directory ?: return
        try {
            val file = File(dir, FILE)
            if (file.length() > MAX_BYTES) file.renameTo(File(dir, OLD_FILE))
            file.appendText(text)
        } catch (e: Exception) {
            Log.e("AppLog", "Can't write the log", e)
        }
    }
}
