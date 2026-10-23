package dev.otmgram.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the [AudioPlayer] is playing; [fileId] identifies the voice message's file. */
data class AudioState(val fileId: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long)

/** Plays voice messages, one at a time. Must be used from the main thread. */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<AudioState?>(null)

    /** The current voice message, or null if none was played or it finished. */
    val state: StateFlow<AudioState?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _state.value = null
                        progressJob?.cancel()
                    } else {
                        publish()
                    }
                }
            })
        }
    }

    /** Plays the file, or pauses/resumes it if it's the current one. */
    fun toggle(fileId: Int, path: String) {
        val current = _state.value
        if (current?.fileId == fileId) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        _state.value = AudioState(fileId, isPlaying = false, positionMs = 0, durationMs = 0)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        player.play()
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                publish()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun publish() {
        val current = _state.value ?: return
        _state.value = current.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 100L
    }
}
