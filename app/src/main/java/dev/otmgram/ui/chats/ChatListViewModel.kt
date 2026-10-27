package dev.otmgram.ui.chats

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.userMessage
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.NotificationMode
import dev.otmgram.data.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    /** What's typed but not sent, shown instead of [preview]. */
    val draft: String? = null,
    /** Unix time of the last message, or null if the chat has none. */
    val date: Int?,
    val unreadCount: Int,
    /** Unread messages that mention you or reply to you. */
    val unreadMentions: Int = 0,
    val isSavedMessages: Boolean,
    val isMuted: Boolean,
    val isPinned: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val folders: FolderRepository,
    private val chatRepository: ChatRepository,
    td: TelegramClient,
    private val settings: AppSettings,
    private val strings: Strings,
) : ViewModel() {

    /** The name on top: the folder's, or what the user set instead (possibly nothing). */
    val folderName: StateFlow<String> = folders.displayName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _query = MutableStateFlow("")

    /** Search text; filters chats by title. */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Chats of the folder matching [query], or null while loading. Kept up to date while a
     * chat is open too, so coming back shows the list at once instead of reloading it all
     * during the animation.
     */
    val chats: StateFlow<List<ChatItem>?> = combine(
        folders.selectedFolderId.filterNotNull().flatMapLatest(chatRepository::folderChats),
        _query,
    ) { all, query ->
        all.map { it.toItem(strings) }.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What the connection is doing while it isn't ready, or null when it is. Only after a
     * moment: most reconnects are over before it would be read, and only move the list.
     */
    val connection: StateFlow<String?> = td.connectionState.map { state ->
        when (state) {
            is TdApi.ConnectionStateWaitingForNetwork -> strings.get(R.string.connection_waiting)
            is TdApi.ConnectionStateConnecting, is TdApi.ConnectionStateConnectingToProxy -> strings.get(R.string.connection_connecting)
            else -> null
        }
    }.mapLatest { text ->
        if (text != null) delay(CONNECTION_SHOWN_AFTER_MS)
        text
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether to ask for the notification permission; null until known. */
    val askNotifications: StateFlow<Boolean?> = settings.notificationsAsked.map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the app checks for new messages, so blocked notifications are worth a word. */
    val notifies: StateFlow<Boolean> = settings.notificationMode.map { it != NotificationMode.Off }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun notificationsAsked() {
        viewModelScope.launchLogged(TAG) { settings.setNotificationsAsked() }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    /** Why pinning or muting a chat failed, e.g. too many pinned; null if it didn't. */
    var actionError by mutableStateOf<ChatActionError?>(null)
        private set

    fun setPinned(chat: ChatItem, pinned: Boolean) {
        viewModelScope.launchLogged(TAG, onError = { actionError = ChatActionError(R.string.chat_pin_failed, it.userMessage(strings)) }) {
            val folderId = folders.selectedFolderId.first() ?: return@launchLogged
            chatRepository.setPinned(folderId, chat.id, pinned)
        }
    }

    fun setMuted(chat: ChatItem, muted: Boolean) {
        viewModelScope.launchLogged(TAG, onError = { actionError = ChatActionError(R.string.chat_mute_failed, it.userMessage(strings)) }) {
            chatRepository.setMuted(chat.id, muted)
        }
    }

    fun actionErrorShown() {
        actionError = null
    }
}

/** A chat action that failed: what, and why. */
data class ChatActionError(@StringRes val title: Int, val message: String)

private const val TAG = "ChatList"
private const val CONNECTION_SHOWN_AFTER_MS = 2_000L

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
    draft = (chat.draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text?.takeIf { it.isNotBlank() },
    date = chat.draftMessage?.date?.takeIf { it > 0 } ?: chat.lastMessage?.date,
    unreadCount = chat.unreadCount,
    unreadMentions = chat.unreadMentionCount,
    isSavedMessages = isSavedMessages,
    isMuted = isMuted,
    isPinned = isPinned,
)
