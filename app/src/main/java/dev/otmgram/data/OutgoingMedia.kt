package dev.otmgram.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.media.MediaTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns picked or recorded media into message content TDLib can send. */
@Singleton
class OutgoingMedia @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: OutgoingFiles,
    private val transcoder: MediaTranscoder,
) {
    /**
     * A photo or video picked from the gallery, with an optional caption; null for other
     * types. With [compress], large photos and videos are made smaller first.
     */
    suspend fun fromGallery(uri: Uri, caption: String = "", compress: Boolean = true): TdApi.InputMessageContent? {
        val type = context.contentResolver.getType(uri).orEmpty()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        return when {
            type.startsWith("image/") -> withContext(Dispatchers.IO) {
                val original = copy(uri, files.newFile(extension ?: "jpg"))
                photo(if (compress) compressedPhoto(original, type) else original, caption)
            }
            type.startsWith("video/") -> {
                val original = withContext(Dispatchers.IO) { copy(uri, files.newFile(extension ?: "mp4")) }
                // The transcoder runs on the main thread.
                val file = if (compress && needsCompression(original)) transcoder.compressVideo(original) else original
                withContext(Dispatchers.IO) { video(file, caption) }
            }
            else -> null
        }
    }

    /** Any file, sent as is under its own name. */
    suspend fun document(uri: Uri, caption: String = ""): TdApi.InputMessageContent = withContext(Dispatchers.IO) {
        val file = copy(uri, files.newNamedFile(displayName(uri)))
        // Content type detection off: a file is sent as a file, not turned into a photo or video.
        TdApi.InputMessageDocument(TdApi.InputDocument(local(file), null, true), formatted(caption))
    }

    /** Duration in seconds, display size accounting for rotation, and bitrate in bits/s. */
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
            bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
        )
    }

    private fun photo(file: File, caption: String): TdApi.InputMessageContent {
        val (width, height) = imageSize(file)
        val photo = TdApi.InputPhoto(local(file), null, null, intArrayOf(), width, height)
        return TdApi.InputMessagePhoto(photo, formatted(caption), false, null, false)
    }

    /**
     * At most [MAX_PHOTO_SIDE] px as JPEG, rotated upright; Telegram's servers recompress
     * photos to that anyway, so only the upload gets smaller. Small JPEGs stay as they are.
     */
    private fun compressedPhoto(file: File, type: String): File {
        val (width, height) = imageSize(file)
        val small = max(width, height) <= MAX_PHOTO_SIDE && file.length() <= SMALL_PHOTO_BYTES
        if (type == "image/jpeg" && small) return file
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val scale = minOf(1f, MAX_PHOTO_SIDE.toFloat() / max(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val output = files.newFile("jpg")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        file.delete()
        return output
    }

    private fun video(file: File, caption: String): TdApi.InputMessageContent {
        val info = videoInfo(file)
        val video = TdApi.InputVideo(
            local(file), null, null, 0, intArrayOf(), info.duration, info.width, info.height, true,
        )
        return TdApi.InputMessageVideo(video, formatted(caption), false, null, false)
    }

    /** Larger than 720p, or a high bitrate: worth re-encoding. */
    private fun needsCompression(file: File): Boolean {
        val info = videoInfo(file)
        return minOf(info.width, info.height) > MediaTranscoder.COMPRESSED_SHORT_SIDE ||
            info.bitrate > MediaTranscoder.COMPRESSED_BITRATE * 3 / 2
    }

    /** Width and height as displayed, with the orientation applied. */
    private fun imageSize(file: File): Pair<Int, Int> {
        var size = 0 to 0
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            size = info.size.width to info.size.height
            decoder.setTargetSize(1, 1) // Only the header is needed.
        }
        return size
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.replace('/', '_') ?: "file"

    private fun copy(uri: Uri, file: File): File {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Can't read $uri" }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun local(file: File) = TdApi.InputFileLocal(file.path)

    private fun formatted(text: String) = TdApi.FormattedText(text.trim(), emptyArray())

    private companion object {
        const val MAX_PHOTO_SIDE = 2560
        const val SMALL_PHOTO_BYTES = 1_500_000L
        const val JPEG_QUALITY = 87
    }
}

data class VideoInfo(val duration: Int, val width: Int, val height: Int, val bitrate: Int)
