package dev.otmgram.ui.videonote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.media.MediaTranscoder
import dev.otmgram.core.media.OutgoingFiles
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.userMessage
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.OutgoingMedia
import dev.otmgram.data.isSavedMessages
import dev.otmgram.di.ApplicationScope
import dev.otmgram.di.onMain
import dev.otmgram.ui.VideoNoteRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject

enum class VideoNoteState { Recording, Processing, Sent }

@HiltViewModel
class VideoNoteViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val messages: MessageRepository,
    private val transcoder: MediaTranscoder,
    private val outgoing: OutgoingMedia,
    private val files: OutgoingFiles,
    td: TelegramClient,
    private val strings: Strings,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /** Sending finishes even if the camera screen is left meanwhile. */
    private val lasting = appScope.onMain()

    private val chatId: Long = savedState.toRoute<VideoNoteRoute>().chatId

    /** Who the circle goes to, shown above the camera. */
    var chatTitle by mutableStateOf("")
        private set

    init {
        viewModelScope.launchLogged(TAG) {
            val chat = td.send(TdApi.GetChat(chatId))
            chatTitle = if (chat.isSavedMessages(td.send(TdApi.GetMe()).id)) strings.get(R.string.saved_messages) else chat.title
        }
    }

    var state by mutableStateOf(VideoNoteState.Recording)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun newRecordingFile(): File = files.newFile("mp4")

    /** Crops the recording to a square and sends it. */
    fun send(recording: File) {
        state = VideoNoteState.Processing
        lasting.launchLogged(TAG, onError = { e ->
            error = strings.get(R.string.video_note_send_failed, e.userMessage(strings))
            state = VideoNoteState.Recording
        }) {
            val videoNote = transcoder.videoNote(recording)
            val duration = withContext(Dispatchers.IO) { outgoing.videoInfo(videoNote) }.duration.coerceAtLeast(1)
            messages.sendVideoNote(chatId, videoNote, duration, MediaTranscoder.VIDEO_NOTE_SIZE)
            state = VideoNoteState.Sent
        }
    }

    /** A recording nobody wants: closed, left or rotated away. */
    fun drop(recording: File) {
        recording.delete()
    }

    fun discard(recording: File, reason: String?) {
        recording.delete()
        error = strings.get(R.string.video_note_record_failed, reason ?: strings.get(R.string.video_note_unknown_error))
    }
}

private const val TAG = "VideoNoteViewModel"
