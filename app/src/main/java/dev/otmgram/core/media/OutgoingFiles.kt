package dev.otmgram.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files waiting to be uploaded, in the cache; TDLib reads them by path. Once sent, TDLib
 * has them on Telegram (and downloads them again if needed), so old ones are removed.
 */
@Singleton
class OutgoingFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory: File get() = File(context.cacheDir, "outgoing").apply { mkdirs() }

    fun newFile(extension: String) = File(directory, "${UUID.randomUUID()}.$extension")

    /** A file keeping [name], which recipients see for documents. */
    fun newNamedFile(name: String) = File(File(directory, UUID.randomUUID().toString()).apply { mkdirs() }, name)

    /** Removes files older than [KEEP_MS]: long sent, or a send that never will (e.g. offline for days). */
    fun removeOld(now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.deleteRecursively() }
    }

    fun removeAll() {
        directory.deleteRecursively()
    }

    private companion object {
        const val KEEP_MS = 3 * 24 * 60 * 60 * 1000L
    }
}
