package dev.otmgram.ui.chats

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.AppVisibility
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.userMessage
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.NotificationMode
import dev.otmgram.data.senderName
import dev.otmgram.data.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

// Never changed once made: unchanged rows then skip redrawing when the list updates.
@Immutable
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
    private val td: TelegramClient,
    private val messageRepository: MessageRepository,
    visibility: AppVisibility,
    private val settings: AppSettings,
    private val strings: Strings,
) : ViewModel() {

    /** The name on top: the folder's, or what the user set instead (possibly nothing). */
    val folderName: StateFlow<String> = folders.displayName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _query = MutableStateFlow("")

    /** Search text; filters chats by title. */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The folder's chats, or null while loading. Kept up to date while a chat is open too, so
     * coming back shows the list at once instead of reloading it all during the animation.
     */
    val chats: StateFlow<List<ChatItem>?> = visibility.visible
        // Not while the app is in the background: nobody sees it, and every chat's update
        // reloaded it. What was shown stays, and it reloads on coming back.
        .flatMapLatest { shown -> if (shown) folders.selectedFolderId.filterNotNull().flatMapLatest(chatRepository::folderChats) else emptyFlow() }
        .map { all -> all.map { it.toItem(strings) } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Messages found for [query] in the folder's chats; null while there's no query. */
    var found by mutableStateOf<FoundMessages?>(null)
        private set

    /** Where the next page of [found] starts, as TDLib gave it. */
    private var nextOffset = ""
    private var loadingMore: Job? = null

    init {
        // A moment after typing stops; a new query drops what the last one was loading.
        viewModelScope.launch {
            _query.map { it.trim() }.distinctUntilChanged().collectLatest { query ->
                loadingMore?.cancel()
                if (query.isEmpty()) {
                    found = null
                    return@collectLatest
                }
                delay(SEARCH_DELAY_MS)
                nextOffset = ""
                found = FoundMessages(query, emptyList(), loading = true, complete = false)
                loadFound(query)
            }
        }
    }

    /** The next page of [found], once scrolled near its end. */
    fun loadMoreFound() {
        val shown = found ?: return
        if (shown.loading || shown.complete || loadingMore?.isActive == true) return
        loadingMore = viewModelScope.launch { loadFound(shown.query) }
    }

    /**
     * Pages of results for [query] until a few are in the folder's chats: Telegram searches
     * all of them, and TDLib can't search one folder.
     */
    private suspend fun loadFound(query: String) {
        found = found?.copy(loading = true)
        val folder = chats.filterNotNull().first().associateBy { it.id }
        val more = mutableListOf<FoundMessage>()
        var complete = false
        try {
            var pages = 0
            while (more.size < FOUND_PER_LOAD && pages < SEARCH_PAGES_PER_LOAD) {
                val page = messageRepository.searchEverywhere(query, nextOffset)
                pages++
                nextOffset = page.nextOffset
                page.messages.forEach { message ->
                    val chat = folder[message.chatId] ?: return@forEach
                    more += FoundMessage(chat, message.id, foundText(message, chat), message.date)
                }
                if (nextOffset.isEmpty()) {
                    complete = true
                    break
                }
            }
        } catch (e: TdException) {
            AppLog.w(TAG, "Searching messages failed", e)
            complete = true
        }
        val shown = found ?: return
        if (shown.query == query) found = shown.copy(messages = shown.messages + more, loading = false, complete = complete)
    }

    /** "You: …", "Anna: …" in a group, or the message alone in a private chat. */
    private suspend fun foundText(message: TdApi.Message, chat: ChatItem): String {
        val text = message.summary(strings)
        if (chat.isSavedMessages) return text
        if (message.isOutgoing) return strings.get(R.string.preview_you, text)
        val sender = message.senderId
        val fromTheChat = sender is TdApi.MessageSenderUser && sender.userId == chat.id
        if (fromTheChat) return text
        val name = try {
            td.senderName(sender, firstOnly = true)
        } catch (e: TdException) {
            null
        }
        return name?.let { strings.get(R.string.preview_sender, it, text) } ?: text
    }

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

/** Messages found in the folder's chats for [query], newest first. */
data class FoundMessages(val query: String, val messages: List<FoundMessage>, val loading: Boolean, val complete: Boolean)

data class FoundMessage(val chat: ChatItem, val messageId: Long, val text: String, val date: Int)

private const val TAG = "ChatList"
private const val SEARCH_DELAY_MS = 300L

/** At least this many found in the folder per load, if there are, */
private const val FOUND_PER_LOAD = 20

/** looking through at most this many pages of all chats' results for them. */
private const val SEARCH_PAGES_PER_LOAD = 5
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
