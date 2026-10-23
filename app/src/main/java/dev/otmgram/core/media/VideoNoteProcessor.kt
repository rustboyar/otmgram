package dev.otmgram.core.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.data.OutgoingMedia
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Turns camera recordings into video messages: square H.264, [SIZE] px. */
@Singleton
class VideoNoteProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoing: OutgoingMedia,
) {
    /**
     * Crops [input] to the center square. Must be called on the main thread. Retries with
     * software decoders if the hardware one fails.
     */
    @OptIn(UnstableApi::class)
    suspend fun process(input: File): File {
        val output = try {
            export(input, softwareDecoders = false)
        } catch (e: ExportException) {
            if (e.errorCode != ExportException.ERROR_CODE_DECODING_FAILED) throw e
            export(input, softwareDecoders = true)
        }
        input.delete()
        return output
    }

    @OptIn(UnstableApi::class)
    private suspend fun export(input: File, softwareDecoders: Boolean): File = suspendCancellableCoroutine { continuation ->
        val output = outgoing.newFile("mp4")
        val decoders = DefaultDecoderFactory.Builder(context)
            .apply { if (softwareDecoders) setMediaCodecSelector(SoftwareFirstCodecSelector) }
            .build()
        val assetLoaders = DefaultAssetLoaderFactory(
            context, decoders, Clock.DEFAULT, DefaultMediaSourceFactory(context), DataSourceBitmapLoader.Builder(context).build(),
        )
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(assetLoaders)
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
        val square = Presentation.createForWidthAndHeight(SIZE, SIZE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(square)))
            .build()
        transformer.start(item, output.path)
        continuation.invokeOnCancellation { transformer.cancel() }
    }

    companion object {
        const val SIZE = 480
    }
}
