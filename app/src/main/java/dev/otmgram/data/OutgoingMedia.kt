package dev.otmgram.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.media.MediaTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Total size of picked media as it is, and roughly after compression. */
data class SizeEstimate(val original: Long, val compressed: Long)

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
            // A GIF goes as an animation, which plays; as a photo it would be its first frame.
            type == "image/gif" -> withContext(Dispatchers.IO) { animation(copy(uri, files.newFile("gif")), caption) }
            type.startsWith("image/") -> withContext(Dispatchers.IO) {
                val original = copy(uri, files.newFile(extension ?: "jpg"))
                photo(if (compress) compressedPhoto(original, type) else original, caption)
            }
            type.startsWith("video/") -> {
                val original = withContext(Dispatchers.IO) { copy(uri, files.newFile(extension ?: "mp4")) }
                // Reading the video's details takes a while for a large one: not on the main thread.
                val compressing = compress && withContext(Dispatchers.IO) { needsCompression(original) }
                val file = if (compressing) compressedVideo(original) else original
                withContext(Dispatchers.IO) { video(file, caption) }
            }
            else -> null
        }
    }

    /**
     * The video re-encoded smaller, or [original] if that fails (some phones' encoders
     * can't take some videos) or doesn't make it smaller: it's sent as it is then.
     */
    @OptIn(UnstableApi::class)
    private suspend fun compressedVideo(original: File): File {
        val compressed = try {
            transcoder.compressVideo(original) // On the main thread.
        } catch (e: ExportException) {
            AppLog.w(TAG, "Compressing a video failed; sending it as it is", e)
            return original
        }
        return if (compressed.length() < original.length()) {
            original.delete()
            compressed
        } else {
            compressed.delete()
            original
        }
    }

    /**
     * How big the picked media is, and about how big it gets compressed: photos by their
     * pixels after scaling, videos by their duration at the compressed bitrate. Nothing is
     * actually compressed here.
     */
    suspend fun estimate(uris: List<Uri>): SizeEstimate = withContext(Dispatchers.IO) {
        var original = 0L
        var compressed = 0L
        uris.forEach { uri ->
            val size = fileSize(uri)
            val type = context.contentResolver.getType(uri).orEmpty()
            original += size
            compressed += try {
                when {
                    type.startsWith("image/") -> estimatePhoto(uri, type, size)
                    type.startsWith("video/") -> estimateVideo(uri, size)
                    else -> size
                }
            } catch (e: Exception) {
                size // Can't read it: assume it stays as it is.
            }
        }
        SizeEstimate(original, compressed)
    }

    private fun estimatePhoto(uri: Uri, type: String, size: Long): Long {
        var width = 0
        var height = 0
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            decoder.setTargetSize(1, 1) // Only the header is needed.
        }
        if (type == "image/jpeg" && max(width, height) <= MAX_PHOTO_SIDE && size <= SMALL_PHOTO_BYTES) return size
        val scale = minOf(1.0, MAX_PHOTO_SIDE.toDouble() / max(width, height))
        return (width * height * scale * scale * JPEG_BYTES_PER_PIXEL).toLong()
    }

    private fun estimateVideo(uri: Uri, size: Long): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
        val shortSide = minOf(int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        val worth = shortSide > MediaTranscoder.COMPRESSED_SHORT_SIDE ||
            int(MediaMetadataRetriever.METADATA_KEY_BITRATE) > MediaTranscoder.COMPRESSED_BITRATE * 3 / 2
        if (!worth) return size
        val seconds = int(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000.0
        minOf(size, (seconds * (MediaTranscoder.COMPRESSED_BITRATE + AUDIO_BITRATE) / 8).toLong())
    }

    private fun fileSize(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L

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
        // JPEG has no transparency: transparent parts (a PNG logo, a sticker-like image) go
        // white rather than black.
        val opaque = if (bitmap.hasAlpha()) {
            createBitmap(bitmap.width, bitmap.height).applyCanvas {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }.also { bitmap.recycle() }
        } else {
            bitmap
        }
        val output = files.newFile("jpg")
        output.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        opaque.recycle()
        file.delete()
        return output
    }

    private fun animation(file: File, caption: String): TdApi.InputMessageContent {
        val (width, height) = imageSize(file)
        val animation = TdApi.InputAnimation(local(file), null, intArrayOf(), 0, width, height)
        return TdApi.InputMessageAnimation(animation, formatted(caption), false, false)
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
            requireNotNull(input) { "Can't read the picked file" }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun local(file: File) = TdApi.InputFileLocal(file.path)

    private fun formatted(text: String) = TdApi.FormattedText(text.trim(), emptyArray())

    private companion object {
        const val TAG = "OutgoingMedia"
        const val MAX_PHOTO_SIDE = 2560
        const val SMALL_PHOTO_BYTES = 1_500_000L
        const val JPEG_QUALITY = 87

        /** Typical for photos at [JPEG_QUALITY]; screenshots and flat images come out smaller. */
        const val JPEG_BYTES_PER_PIXEL = 0.3

        /** Roughly what the transcoder's AAC audio takes. */
        const val AUDIO_BITRATE = 128_000
    }
}

data class VideoInfo(val duration: Int, val width: Int, val height: Int, val bitrate: Int)
