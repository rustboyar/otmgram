package dev.otmgram.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Files waiting to be uploaded, in the cache; TDLib reads them by path. */
@Singleton
class OutgoingFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory: File get() = File(context.cacheDir, "outgoing").apply { mkdirs() }

    fun newFile(extension: String) = File(directory, "${UUID.randomUUID()}.$extension")

    /** A file keeping [name], which recipients see for documents. */
    fun newNamedFile(name: String) = File(File(directory, UUID.randomUUID().toString()).apply { mkdirs() }, name)
}
