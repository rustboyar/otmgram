package dev.otmgram.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.FolderRepository
import dev.otmgram.ui.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class ChatItem(
    val id: Long,
    val title: String,
    /** Small chat photo, null if the chat has none. */
    val photoFileId: Int?,
    val photoMinithumbnail: ByteArray?,
    val preview: String,
    /** Unix time of the last message, or null if the chat has none. */
    val date: Int?,
    val unreadCount: Int,
    val isSavedMessages: Boolean,
    val isMuted: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatListViewModel @Inject constructor(
    folders: FolderRepository,
    chats: ChatRepository,
    td: TelegramClient,
    private val strings: Strings,
) : ViewModel() {

    /** The name on top: the folder's, or what the user set instead (possibly nothing). */
    val folderName: StateFlow<String> = folders.displayName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _query = MutableStateFlow("")

    /** Search text; filters chats by title. */
    val query: StateFlow<String> = _query.asStateFlow()

    /** Chats of the folder matching [query], or null while loading. */
    val chats: StateFlow<List<ChatItem>?> = combine(
        folders.selectedFolderId.filterNotNull().flatMapLatest(chats::folderChats),
        _query,
    ) { all, query ->
        all.map { it.toItem(strings) }.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What the connection is doing while it isn't ready, or null when it is. */
    val connection: StateFlow<String?> = td.connectionState.map { state ->
        when (state) {
            is TdApi.ConnectionStateWaitingForNetwork -> strings.get(R.string.connection_waiting)
            is TdApi.ConnectionStateConnecting, is TdApi.ConnectionStateConnectingToProxy -> strings.get(R.string.connection_connecting)
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(query: String) {
        _query.value = query
    }
}

private fun FolderChat.toItem(strings: Strings) = ChatItem(
    id = chat.id,
    title = if (isSavedMessages) strings.get(R.string.saved_messages) else chat.title,
    photoFileId = chat.photo?.small?.id,
    photoMinithumbnail = chat.photo?.minithumbnail?.data,
    preview = chat.lastMessage?.let { message ->
        val text = message.summary(strings)
        when {
            isSavedMessages -> text
            message.isOutgoing -> strings.get(R.string.preview_you, text)
            lastSenderName != null -> strings.get(R.string.preview_sender, lastSenderName, text)
            else -> text
        }
    }.orEmpty(),
    date = chat.lastMessage?.date,
    unreadCount = chat.unreadCount,
    isSavedMessages = isSavedMessages,
    isMuted = isMuted,
)
