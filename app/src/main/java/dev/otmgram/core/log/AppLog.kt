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
            append(line('E', "Crash", "Uncaught in thread ${thread.name}", e))
            previous?.uncaughtException(thread, e)
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue(line('I', tag, message, null))
    }

    fun w(tag: String, message: String, e: Throwable? = null) {
        Log.w(tag, message, e)
        enqueue(line('W', tag, message, e))
    }

    fun e(tag: String, message: String, e: Throwable? = null) {
        Log.e(tag, message, e)
        enqueue(line('E', tag, message, e))
    }

    private fun line(level: Char, tag: String, message: String, e: Throwable?): String {
        val trace = e?.let { StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString() }.orEmpty()
        return "${LocalDateTime.now().format(time)} $level $tag: $message\n$trace"
    }

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
