package dev.otmgram.ui.map

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
        _location.value = td.send(TdApi.GetMessage(chatId, messageId)).toMedia() as? MessageMedia.Location
    }
}
