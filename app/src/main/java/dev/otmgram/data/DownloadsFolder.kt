package dev.otmgram.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The device's public Downloads folder; no storage permission needed (MediaStore). */
@Singleton
class DownloadsFolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(file: File, name: String, mimeType: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifEmpty { "application/octet-stream" })
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Can't create $name" }
        resolver.openOutputStream(uri).use { output ->
            checkNotNull(output) { "Can't write $name" }
            file.inputStream().use { it.copyTo(output) }
        }
    }
}
