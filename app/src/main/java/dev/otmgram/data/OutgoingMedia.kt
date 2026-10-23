package dev.otmgram.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Turns picked or recorded media into message content TDLib can send. */
@Singleton
class OutgoingMedia @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Directory for files waiting to be uploaded; TDLib reads them by path. */
    val directory: File get() = File(context.cacheDir, "outgoing").apply { mkdirs() }

    /** A photo or video picked from the gallery; null for other types. */
    suspend fun fromGallery(uri: Uri): TdApi.InputMessageContent? = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        when {
            type.startsWith("image/") -> photo(copy(uri, extension ?: "jpg"))
            type.startsWith("video/") -> video(copy(uri, extension ?: "mp4"))
            else -> null
        }
    }

    private fun photo(file: File): TdApi.InputMessageContent {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val photo = TdApi.InputPhoto(local(file), null, null, intArrayOf(), bounds.outWidth, bounds.outHeight)
        return TdApi.InputMessagePhoto(photo, null, false, null, false)
    }

    private fun video(file: File): TdApi.InputMessageContent {
        val info = videoInfo(file)
        val video = TdApi.InputVideo(
            local(file), null, null, 0, intArrayOf(), info.duration, info.width, info.height, true,
        )
        return TdApi.InputMessageVideo(video, null, false, null, false)
    }

    /** Duration in seconds and display size, accounting for rotation. */
    fun videoInfo(file: File): VideoInfo = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.path)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
        val width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotated = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
        VideoInfo(
            duration = int(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000,
            width = if (rotated) height else width,
            height = if (rotated) width else height,
        )
    }

    fun newFile(extension: String) = File(directory, "${UUID.randomUUID()}.$extension")

    private fun copy(uri: Uri, extension: String): File {
        val file = newFile(extension)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Can't read $uri" }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun local(file: File) = TdApi.InputFileLocal(file.path)
}

data class VideoInfo(val duration: Int, val width: Int, val height: Int)
