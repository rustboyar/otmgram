package dev.otmgram.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.ui.chat.MessageMedia
import dev.otmgram.ui.chat.toMedia
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class MapSender(val id: Long, val name: String, val photoFileId: Int?, val minithumbnail: TdApi.Minithumbnail?)

@HiltViewModel
class MapViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val td: TdClient,
) : ViewModel() {

    private val chatId: Long = checkNotNull(savedState["chatId"])
    private val messageId: Long = checkNotNull(savedState["messageId"])

    private val _location = MutableStateFlow<MessageMedia.Location?>(null)

    /** The message's location; live locations update as the sender moves. */
    val location: StateFlow<MessageMedia.Location?> = _location.asStateFlow()

    /** Who shared the location, for the card under the map. */
    var sender by mutableStateOf<MapSender?>(null)
        private set

    init {
        // Subscribe before loading, so no update is missed in between.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                val changed = when (update) {
                    is TdApi.UpdateMessageContent -> update.chatId == chatId && update.messageId == messageId
                    is TdApi.UpdateMessageEdited -> update.chatId == chatId && update.messageId == messageId
                    else -> false
                }
                if (changed) reload()
            }
        }
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val message = td.send(TdApi.GetMessage(chatId, messageId))
        _location.value = message.toMedia() as? MessageMedia.Location
        if (sender == null) sender = senderOf(message)
    }

    private suspend fun senderOf(message: TdApi.Message): MapSender? = when (val id = message.senderId) {
        is TdApi.MessageSenderUser -> td.send(TdApi.GetUser(id.userId)).let { user ->
            MapSender(user.id, listOf(user.firstName, user.lastName).filter(String::isNotEmpty).joinToString(" "), user.profilePhoto?.small?.id, user.profilePhoto?.minithumbnail)
        }
        is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(id.chatId)).let { chat ->
            MapSender(chat.id, chat.title, chat.photo?.small?.id, chat.photo?.minithumbnail)
        }
        else -> null
    }
}
