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
import dev.otmgram.core.media.FilmLook
import dev.otmgram.core.media.MediaTranscoder
import dev.otmgram.core.media.OutgoingFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Total size of picked media as it is, and roughly in [SendQuality.SD] and [SendQuality.HD]. */
data class SizeEstimate(val original: Long, val sd: Long, val hd: Long) {
    fun of(quality: SendQuality): Long = when (quality) {
        SendQuality.SD -> sd
        SendQuality.HD -> hd
        SendQuality.Original -> original
    }
}

/** What a quality makes of media: photos at most [photoSide] px, videos [videoSide] px on the short side at [videoBitrate] bits/s. */
private class Target(val photoSide: Int, val videoSide: Int, val videoBitrate: Int) {
    /** A JPEG within [photoSide] and this small is sent as it is. */
    val smallJpegBytes: Long get() = (photoSide.toLong() * photoSide * SMALL_JPEG_BYTES_PER_PIXEL).toLong()
}

// Telegram keeps photos up to 2560 px, the size its apps send as HD, and 1280 px otherwise.
private val SendQuality.target: Target?
    get() = when (this) {
        SendQuality.SD -> Target(photoSide = 1280, videoSide = 480, videoBitrate = 1_200_000)
        SendQuality.HD -> Target(photoSide = 2560, videoSide = 720, videoBitrate = 2_500_000)
        SendQuality.Original -> null
    }

private const val SMALL_JPEG_BYTES_PER_PIXEL = 0.23

/** Turns picked or recorded media into message content TDLib can send. */
@Singleton
class OutgoingMedia @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: OutgoingFiles,
    private val transcoder: MediaTranscoder,
) {
    /**
     * A photo or video picked from the gallery, with an optional caption; null for other
     * types. Large photos and videos are made smaller first, to [quality]; a photo is
     * developed in [film], if set.
     */
    suspend fun fromGallery(uri: Uri, caption: String = "", quality: SendQuality = SendQuality.HD, film: FilmLook.Stock? = null): TdApi.InputMessageContent? {
        val target = quality.target
        val type = context.contentResolver.getType(uri).orEmpty()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        return when {
            // A GIF goes as an animation, which plays; as a photo it would be its first frame.
            type == "image/gif" -> withContext(Dispatchers.IO) { animation(copy(uri, files.newFile("gif")), caption) }
            type.startsWith("image/") -> withContext(Dispatchers.IO) {
                val original = copy(uri, files.newFile(extension ?: "jpg"))
                when {
                    film != null -> photo(filmedPhoto(original, film, target?.photoSide ?: FilmLook.MAX_SIDE), caption)
                    target != null -> photo(compressedPhoto(original, type, target), caption)
                    else -> photo(original, caption)
                }
            }
            type.startsWith("video/") -> {
                val original = withContext(Dispatchers.IO) { copy(uri, files.newFile(extension ?: "mp4")) }
                // Reading the video's details takes a while for a large one: not on the main thread.
                val compressing = target != null && withContext(Dispatchers.IO) { videoInfo(original).worthCompressing(target) }
                val file = if (target != null && compressing) compressedVideo(original, target) else original
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
    private suspend fun compressedVideo(original: File, target: Target): File {
        val compressed = try {
            transcoder.compressVideo(original, target.videoSide, target.videoBitrate) // On the main thread.
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
     * How big the picked media is, and about how big it gets in each quality: photos by their
     * pixels after scaling, videos by their duration at the bitrate. Nothing is actually
     * compressed here.
     */
    suspend fun estimate(uris: List<Uri>): SizeEstimate = withContext(Dispatchers.IO) {
        var original = 0L
        var sd = 0L
        var hd = 0L
        uris.forEach { uri ->
            val size = fileSize(uri)
            val type = context.contentResolver.getType(uri).orEmpty()
            val sizeFor: (Target) -> Long = try {
                when {
                    type.startsWith("image/") -> imageSize(ImageDecoder.createSource(context.contentResolver, uri)).let { (width, height) ->
                        { target -> estimatePhoto(width, height, type, size, target) }
                    }
                    type.startsWith("video/") -> videoInfo { setDataSource(context, uri) }.let { info -> { target -> estimateVideo(info, size, target) } }
                    else -> { _ -> size }
                }
            } catch (e: Exception) {
                { _ -> size } // Can't read it: assume it stays as it is.
            }
            original += size
            sd += sizeFor(SendQuality.SD.target!!)
            hd += sizeFor(SendQuality.HD.target!!)
        }
        SizeEstimate(original, sd, hd)
    }

    private fun estimatePhoto(width: Int, height: Int, type: String, size: Long, target: Target): Long {
        if (type == "image/jpeg" && max(width, height) <= target.photoSide && size <= target.smallJpegBytes) return size
        val scale = minOf(1.0, target.photoSide.toDouble() / max(width, height))
        return minOf(size, (width * height * scale * scale * JPEG_BYTES_PER_PIXEL).toLong())
    }

    private fun estimateVideo(info: VideoInfo, size: Long, target: Target): Long {
        if (!info.worthCompressing(target)) return size
        return minOf(size, (info.duration * (target.videoBitrate.toLong() + AUDIO_BITRATE) / 8))
    }

    /** Larger than [target], or a high bitrate: worth re-encoding. */
    private fun VideoInfo.worthCompressing(target: Target): Boolean =
        minOf(width, height) > target.videoSide || bitrate > target.videoBitrate * 3 / 2

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
    fun videoInfo(file: File): VideoInfo = videoInfo { setDataSource(file.path) }

    private fun videoInfo(source: MediaMetadataRetriever.() -> Unit): VideoInfo = MediaMetadataRetriever().use { retriever ->
        retriever.source()
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
     * At most the [target]'s side as JPEG, rotated upright; Telegram's servers recompress
     * photos to 2560 px anyway. Small JPEGs stay as they are.
     */
    private fun compressedPhoto(file: File, type: String, target: Target): File {
        val (width, height) = imageSize(file)
        val small = max(width, height) <= target.photoSide && file.length() <= target.smallJpegBytes
        if (type == "image/jpeg" && small) return file
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val scale = minOf(1f, target.photoSide.toFloat() / max(info.size.width, info.size.height))
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

    /**
     * The photo developed in [film], at most [side] px, as JPEG. Its glow comes from it at
     * [FilmLook.MAX_SIDE], as in the preview; Telegram keeps photos no larger anyway, so
     * Original with a film is that size too.
     */
    private fun filmedPhoto(file: File, film: FilmLook.Stock, side: Int): File {
        val full = FilmLook.decode(ImageDecoder.createSource(file), FilmLook.MAX_SIDE)
        val photo = if (side >= FilmLook.MAX_SIDE) full else FilmLook.decode(ImageDecoder.createSource(file), side)
        FilmLook.mask(full).use { mask -> FilmLook.develop(mask, photo, film) }
        if (photo !== full) full.recycle()
        val output = files.newFile("jpg")
        output.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        photo.recycle()
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
            local(file), videoThumbnail(file), null, 0, intArrayOf(), info.duration, info.width, info.height, true,
        )
        return TdApi.InputMessageVideo(video, formatted(caption), false, null, false)
    }

    /**
     * The first frame, at most [THUMBNAIL_SIDE] px, as Telegram's apps send with a video:
     * without it, a video sent as it is may show blank until downloaded. Null if none can be read.
     */
    private fun videoThumbnail(file: File): TdApi.InputThumbnail? = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.path)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIDE, THUMBNAIL_SIDE)?.let { frame ->
                val output = files.newFile("jpg")
                output.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                TdApi.InputThumbnail(local(output), frame.width, frame.height).also { frame.recycle() }
            }
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Reading a video's first frame failed", e)
        null
    }

    /** Width and height as displayed, with the orientation applied. */
    private fun imageSize(file: File): Pair<Int, Int> = imageSize(ImageDecoder.createSource(file))

    private fun imageSize(source: ImageDecoder.Source): Pair<Int, Int> {
        var size = 0 to 0
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
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
        const val JPEG_QUALITY = 87

        /** Telegram's limit for thumbnails. */
        const val THUMBNAIL_SIDE = 320

        /** Typical for photos at [JPEG_QUALITY]; screenshots and flat images come out smaller. */
        const val JPEG_BYTES_PER_PIXEL = 0.3

        /** Roughly what the transcoder's AAC audio takes. */
        const val AUDIO_BITRATE = 128_000
    }
}

data class VideoInfo(val duration: Int, val width: Int, val height: Int, val bitrate: Int)
