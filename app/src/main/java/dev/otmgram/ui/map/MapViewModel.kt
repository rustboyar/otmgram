package dev.otmgram.ui.map

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.launchLogged
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.LocationSource
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.ui.chat.MessageMedia
import dev.otmgram.ui.chat.toMedia
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class MapSender(val id: Long, val name: String, val photoFileId: Int?, val minithumbnail: ByteArray?)

@HiltViewModel
class MapViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val td: TelegramClient,
    private val locationSource: LocationSource,
    private val liveLocation: LiveLocationSharing,
) : ViewModel() {

    private val chatId: Long = checkNotNull(savedState["chatId"])
    private val messageId: Long = checkNotNull(savedState["messageId"])

    private val _location = MutableStateFlow<MessageMedia.Location?>(null)

    /** The message's location; live locations update as the sender moves. */
    val location: StateFlow<MessageMedia.Location?> = _location.asStateFlow()

    /** Your own position, for the distance; null until known or without permission. */
    var myLocation by mutableStateOf<android.location.Location?>(null)
        private set

    /** The location is your own message: no distance to it. */
    var isMine by mutableStateOf(false)
        private set

    /** The location permission must be granted. */
    @SuppressLint("MissingPermission")
    fun locateMe() {
        myLocation = locationSource.lastKnown()
        viewModelScope.launchLogged(TAG) { locationSource.current()?.let { myLocation = it } }
    }

    /** True while this is your live location and it's still being shared. */
    val sharing: StateFlow<Boolean> = td.activeLiveLocations
        .map { messages -> messages.orEmpty().any { it.chatId == chatId && it.id == messageId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Stops sharing: through the service if this phone shares it now, otherwise (left
     * running after the app was killed) by ending the message directly.
     */
    fun stopSharing() {
        if (liveLocation.share.value?.chatId == chatId) {
            liveLocation.stop()
        } else {
            viewModelScope.launchLogged(TAG) { td.send(TdApi.EditMessageLiveLocation(chatId, messageId, null, null)) }
        }
    }

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
                // Reloading waits for TDLib: not inside the update collector.
                if (changed) viewModelScope.launch { reloadLogged() }
            }
        }
        viewModelScope.launch { reloadLogged() }
    }

    /** A failed reload keeps what's shown; the message may have been deleted. */
    private suspend fun reloadLogged() {
        try {
            reload()
        } catch (e: TdException) {
            AppLog.w(TAG, "Reload failed", e)
        }
    }

    private suspend fun reload() {
        val message = td.send(TdApi.GetMessage(chatId, messageId))
        isMine = message.isOutgoing
        _location.value = message.toMedia() as? MessageMedia.Location
        if (sender == null) sender = senderOf(message)
    }

    private suspend fun senderOf(message: TdApi.Message): MapSender? = when (val id = message.senderId) {
        is TdApi.MessageSenderUser -> td.send(TdApi.GetUser(id.userId)).let { user ->
            MapSender(user.id, listOf(user.firstName, user.lastName).filter(String::isNotEmpty).joinToString(" "), user.profilePhoto?.small?.id, user.profilePhoto?.minithumbnail?.data)
        }
        is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(id.chatId)).let { chat ->
            MapSender(chat.id, chat.title, chat.photo?.small?.id, chat.photo?.minithumbnail?.data)
        }
        else -> null
    }
}

private const val TAG = "MapViewModel"
