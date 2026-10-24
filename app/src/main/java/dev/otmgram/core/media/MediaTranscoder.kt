@file:OptIn(UnstableApi::class)

package dev.otmgram.core.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.data.OutgoingFiles
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Re-encodes videos before sending, with Media3 Transformer. Must be used from the main thread. */
@Singleton
class MediaTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoing: OutgoingFiles,
) {
    /** A video message: the center square, [VIDEO_NOTE_SIZE] px. */
    suspend fun videoNote(input: File): File =
        transcode(input, Presentation.createForWidthAndHeight(VIDEO_NOTE_SIZE, VIDEO_NOTE_SIZE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

    /**
     * A smaller video: [COMPRESSED_SHORT_SIDE] px on the short side at [COMPRESSED_BITRATE].
     * Keeps [input], to fall back on.
     */
    suspend fun compressVideo(input: File): File =
        transcode(input, Presentation.createForShortSide(COMPRESSED_SHORT_SIDE), bitrate = COMPRESSED_BITRATE, keepInput = true)

    /**
     * Transcodes to H.264/AAC, deleting [input] unless [keepInput]. Retries with software
     * decoders if the hardware one fails.
     */
    @OptIn(UnstableApi::class)
    private suspend fun transcode(input: File, effect: Effect, bitrate: Int? = null, keepInput: Boolean = false): File {
        val output = try {
            export(input, effect, bitrate, softwareDecoders = false)
        } catch (e: ExportException) {
            if (e.errorCode != ExportException.ERROR_CODE_DECODING_FAILED) throw e
            export(input, effect, bitrate, softwareDecoders = true)
        }
        if (!keepInput) input.delete()
        return output
    }

    @OptIn(UnstableApi::class)
    private suspend fun export(input: File, effect: Effect, bitrate: Int?, softwareDecoders: Boolean): File =
        suspendCancellableCoroutine { continuation ->
            val output = outgoing.newFile("mp4")
            val decoders = DefaultDecoderFactory.Builder(context)
                .apply { if (softwareDecoders) setMediaCodecSelector(SoftwareFirstCodecSelector) }
                .build()
            val encoders = DefaultEncoderFactory.Builder(context)
                .apply { if (bitrate != null) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()) }
                .build()
            val assetLoaders = DefaultAssetLoaderFactory(
                context, decoders, Clock.DEFAULT, DefaultMediaSourceFactory(context), DataSourceBitmapLoader.Builder(context).build(),
            )
            val transformer = Transformer.Builder(context)
                .setAssetLoaderFactory(assetLoaders)
                .setEncoderFactory(encoders)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuation.resume(output)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        output.delete()
                        continuation.resumeWithException(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(emptyList(), listOf(effect)))
                .build()
            transformer.start(item, output.path)
            continuation.invokeOnCancellation { transformer.cancel() }
        }

    companion object {
        const val VIDEO_NOTE_SIZE = 480
        const val COMPRESSED_SHORT_SIDE = 720
        const val COMPRESSED_BITRATE = 2_500_000
    }
}
