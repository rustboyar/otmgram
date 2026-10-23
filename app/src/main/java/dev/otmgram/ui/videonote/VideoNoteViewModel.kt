package dev.otmgram.ui.videonote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.launchLogged
import dev.otmgram.core.media.MediaTranscoder
import dev.otmgram.core.td.TdClient
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.OutgoingFiles
import dev.otmgram.data.OutgoingMedia
import dev.otmgram.data.isSavedMessages
import kotlinx.coroutines.launch
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
    td: TdClient,
) : ViewModel() {

    private val chatId: Long = checkNotNull(savedState["chatId"])

    /** Who the circle goes to, shown above the camera. */
    var chatTitle by mutableStateOf("")
        private set

    init {
        viewModelScope.launchLogged("VideoNoteViewModel") {
            val chat = td.send(TdApi.GetChat(chatId))
            chatTitle = if (chat.isSavedMessages(td.send(TdApi.GetMe()).id)) "Saved Messages" else chat.title
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
        viewModelScope.launch {
            try {
                val videoNote = transcoder.videoNote(recording)
                val duration = outgoing.videoInfo(videoNote).duration.coerceAtLeast(1)
                messages.sendVideoNote(chatId, videoNote, duration, MediaTranscoder.VIDEO_NOTE_SIZE)
                state = VideoNoteState.Sent
            } catch (e: Exception) {
                error = "Couldn't send: ${e.message}"
                state = VideoNoteState.Recording
            }
        }
    }

    fun discard(recording: File, reason: String?) {
        recording.delete()
        error = "Recording failed: ${reason ?: "unknown error"}"
    }
}
