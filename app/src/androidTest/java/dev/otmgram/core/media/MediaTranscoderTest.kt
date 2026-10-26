package dev.otmgram.core.media

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sent videos are stored upright. A rotation flag instead (as phone cameras record, landscape
 * frames turned by the player) shows them sideways in Telegram for iOS.
 */
@RunWith(AndroidJUnit4::class)
class MediaTranscoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val transcoder = MediaTranscoder(context, OutgoingFiles(context))

    /** A 1 s clip of 1280×720 frames, flagged to be shown turned by 90°: 720×1280. */
    private fun portraitClip(): File = File(context.cacheDir, "portrait.mp4").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("portrait.mp4").use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }

    private fun File.frames(): Triple<Int, Int, Int> = MediaMetadataRetriever().run {
        setDataSource(path)
        fun int(key: Int) = extractMetadata(key)!!.toInt()
        Triple(
            int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
        ).also { release() }
    }

    @Test
    fun aCompressedPortraitVideoIsStoredUpright() {
        val input = portraitClip()
        val output = runBlocking(Dispatchers.Main) { transcoder.compressVideo(input) }
        try {
            assertEquals(Triple(720, 1280, 0), output.frames())
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun aVideoMessageIsStoredUpright() {
        val output = runBlocking(Dispatchers.Main) { transcoder.videoNote(portraitClip()) }
        try {
            assertEquals(Triple(MediaTranscoder.VIDEO_NOTE_SIZE, MediaTranscoder.VIDEO_NOTE_SIZE, 0), output.frames())
        } finally {
            output.delete()
        }
    }
}
