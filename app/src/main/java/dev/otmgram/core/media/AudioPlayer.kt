package dev.otmgram.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the [AudioPlayer] is playing; [fileId] identifies the voice message's file. */
data class AudioState(val fileId: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long, val speed: Float = 1f)

/** Plays voice messages, one at a time. Must be used from the main thread. */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<AudioState?>(null)

    /** The current voice message, or null if none was played or it finished. */
    val state: StateFlow<AudioState?> = _state.asStateFlow()

    private val _failures = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Voice messages, by file id, that couldn't be played (e.g. a broken file). */
    val failures: SharedFlow<Int> = _failures.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().apply {
            mediaAudio(focus = true, content = C.AUDIO_CONTENT_TYPE_SPEECH)
            addListener(object : Player.Listener {
                // The position moves only while playing: the updates stop on a pause.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publish()
                    progressJob?.cancel()
                    if (isPlaying) {
                        progressJob = scope.launch {
                            while (isActive) {
                                delay(PROGRESS_INTERVAL_MS)
                                publish()
                            }
                        }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish() else publish()
                }

                // Back to its play button, and said why, rather than doing nothing on taps.
                override fun onPlayerError(error: PlaybackException) {
                    AppLog.w(TAG, "Playing a voice message failed: ${error.errorCodeName}")
                    _state.value?.let { _failures.tryEmit(it.fileId) }
                    finish()
                }
            })
        }
    }

    /** The speed voice messages play at, kept for the next ones, as in Telegram. */
    private var speed = 1f

    /** Plays the file, or pauses/resumes it if it's the current one. */
    fun toggle(fileId: Int, path: String) {
        val current = _state.value
        if (current?.fileId == fileId) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        start(fileId, path, fromFraction = 0f)
    }

    /**
     * Moves to [fraction] (0..1) of the file: of the current one, playing on as it was; else
     * starting it there.
     */
    fun seek(fileId: Int, path: String, fraction: Float) {
        val current = _state.value
        if (current?.fileId != fileId) {
            start(fileId, path, fraction)
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    /** 1× → 1.5× → 2× → 1×. */
    fun nextSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        player.setPlaybackSpeed(speed)
        publish()
    }

    private fun start(fileId: Int, path: String, fromFraction: Float) {
        _state.value = AudioState(fileId, isPlaying = false, positionMs = 0, durationMs = 0, speed = speed)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.setPlaybackSpeed(speed)
        player.prepare()
        if (fromFraction > 0f) {
            // The length is known once it's prepared.
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_READY) return
                    player.removeListener(this)
                    player.seekTo((player.duration * fromFraction.coerceIn(0f, 1f)).toLong())
                }
            })
        }
        player.play()
    }

    /**
     * Done with the current one: the next play of it starts over. (Not named stop: in the
     * player's listener that meant the player's own stop, so it was never forgotten, and a
     * second play of a message that had played to its end did nothing.)
     */
    private fun finish() {
        progressJob?.cancel()
        _state.value = null
    }

    private fun publish() {
        val current = _state.value ?: return
        _state.value = current.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            speed = speed,
        )
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val PROGRESS_INTERVAL_MS = 100L
        val SPEEDS = listOf(1f, 1.5f, 2f)
    }
}
