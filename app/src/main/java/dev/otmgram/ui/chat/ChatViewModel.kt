package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.ChatHistory
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.isGroup
import dev.otmgram.data.isSavedMessages
import dev.otmgram.ui.isService
import dev.otmgram.ui.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class ChatHeader(val title: String, val isGroup: Boolean)

enum class MessageStatus { Sending, Sent, Read, Failed }

data class ReplyPreview(val senderName: String?, val text: String)

data class MessageItem(
    val id: Long,
    val isOutgoing: Boolean,
    /** An event like "group created" rather than a message. */
    val isService: Boolean,
    /** Shown above the first of consecutive incoming group messages from the same sender. */
    val senderName: String?,
    val forwardedFrom: String?,
    val replyTo: ReplyPreview?,
    val text: String,
    /** True if [text] describes media that can't be shown yet. */
    val isMediaPlaceholder: Boolean,
    /** Unix time. */
    val date: Int,
    val isEdited: Boolean,
    /** Null for incoming messages. */
    val status: MessageStatus?,
)

/** What the input bar does with the next send. */
sealed interface Composer {
    data object New : Composer
    data class Reply(val message: MessageItem) : Composer
    data class Edit(val message: MessageItem) : Composer
}

/** Long-press menu of a message, with the actions it allows. */
data class MessageMenu(
    val message: MessageItem,
    val canReply: Boolean,
    val canForward: Boolean,
    val canEdit: Boolean,
    val canDeleteForSelf: Boolean,
    val canDeleteForEveryone: Boolean,
)

data class ForwardTarget(val chatId: Long, val title: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    messages: MessageRepository,
    private val chats: ChatRepository,
    private val folders: FolderRepository,
    private val td: TdClient,
) : ViewModel() {

    private val chatId: Long = checkNotNull(savedState["chatId"])
    private val session = messages.openChat(chatId, viewModelScope)

    private val _header = MutableStateFlow<ChatHeader?>(null)
    val header: StateFlow<ChatHeader?> = _header.asStateFlow()

    /** Messages, newest first. */
    val messages: StateFlow<List<MessageItem>> = combine(session.history, _header) { history, header ->
        history.toItems(isGroup = header?.isGroup == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var composer by mutableStateOf<Composer>(Composer.New)
        private set

    var menu by mutableStateOf<MessageMenu?>(null)
        private set

    /** Message waiting for a delete confirmation. */
    var deleting by mutableStateOf<MessageMenu?>(null)
        private set

    /** Message to forward, with the folder chats it can go to. */
    var forwarding by mutableStateOf<Pair<MessageItem, List<ForwardTarget>>?>(null)
        private set

    /** One-time message: an error or a confirmation. */
    var notice by mutableStateOf<String?>(null)
        private set

    private val viewedIds = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            val chat = td.send(TdApi.GetChat(chatId))
            val myId = td.send(TdApi.GetMe()).id
            _header.value = ChatHeader(
                title = if (chat.isSavedMessages(myId)) "Saved Messages" else chat.title,
                isGroup = chat.isGroup,
            )
        }
    }

    fun loadOlder() = action { session.loadOlder() }

    fun send(text: String) {
        if (text.isBlank()) return
        val mode = composer
        composer = Composer.New
        action {
            when (mode) {
                Composer.New -> session.sendText(text.trim())
                is Composer.Reply -> session.sendText(text.trim(), replyToMessageId = mode.message.id)
                is Composer.Edit -> session.editText(mode.message.id, text.trim())
            }
        }
    }

    /** Called with the ids of messages currently on screen. */
    fun onMessagesVisible(ids: List<Long>) {
        val new = ids.filter { viewedIds.add(it) }
        if (new.isNotEmpty()) action { session.markViewed(new) }
    }

    fun openMenu(message: MessageItem) {
        if (message.isService) return
        action {
            val properties = session.properties(message.id)
            menu = MessageMenu(
                message = message,
                canReply = properties.canBeReplied,
                canForward = properties.canBeForwarded,
                canEdit = properties.canBeEdited && !message.isMediaPlaceholder,
                canDeleteForSelf = properties.canBeDeletedOnlyForSelf,
                canDeleteForEveryone = properties.canBeDeletedForAllUsers,
            )
        }
    }

    fun closeMenu() {
        menu = null
    }

    fun reply(message: MessageItem) {
        menu = null
        composer = Composer.Reply(message)
    }

    fun edit(message: MessageItem) {
        menu = null
        composer = Composer.Edit(message)
    }

    fun cancelComposer() {
        composer = Composer.New
    }

    fun askDelete(menu: MessageMenu) {
        this.menu = null
        deleting = menu
    }

    fun delete(forEveryone: Boolean) {
        val message = deleting?.message ?: return
        deleting = null
        action { session.delete(message.id, forEveryone) }
    }

    fun cancelDelete() {
        deleting = null
    }

    fun startForward(message: MessageItem) {
        menu = null
        action {
            val folderId = folders.selectedFolderId.filterNotNull().first()
            val targets = chats.folderChats(folderId).first().map {
                ForwardTarget(it.chat.id, if (it.isSavedMessages) "Saved Messages" else it.chat.title)
            }
            forwarding = message to targets
        }
    }

    fun forward(target: ForwardTarget) {
        val message = forwarding?.first ?: return
        forwarding = null
        action {
            session.forward(message.id, target.chatId)
            notice = "Forwarded to ${target.title}"
        }
    }

    fun cancelForward() {
        forwarding = null
    }

    fun noticeShown() {
        notice = null
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: TdException) {
                notice = e.error.message
            }
        }
    }
}

private fun ChatHistory.toItems(isGroup: Boolean): List<MessageItem> {
    val byId = messages.associateBy { it.id }
    return messages.mapIndexed { index, message ->
        val older = messages.getOrNull(index + 1)
        val showSender = isGroup && !message.isOutgoing && !message.isService &&
            (older == null || older.isOutgoing || older.isService || older.senderKey() != message.senderKey())
        MessageItem(
            id = message.id,
            isOutgoing = message.isOutgoing,
            isService = message.isService,
            senderName = if (showSender) senderNames[message.senderKey()] else null,
            forwardedFrom = message.forwardInfo?.origin?.let(::originName),
            replyTo = (message.replyTo as? TdApi.MessageReplyToMessage)?.let { replyTo ->
                val replied = byId[replyTo.messageId] ?: repliedMessages[replyTo.messageId]
                when {
                    replied != null -> ReplyPreview(senderNames[replied.senderKey()], replied.summary())
                    replyTo.content != null -> ReplyPreview(replyTo.origin?.let(::originName), replyTo.content.summary())
                    else -> ReplyPreview(null, "Deleted message")
                }
            },
            text = message.summary(),
            isMediaPlaceholder = message.content !is TdApi.MessageText && !message.isService,
            date = message.date,
            isEdited = message.editDate > 0,
            status = if (!message.isOutgoing) null else when {
                message.sendingState is TdApi.MessageSendingStateFailed -> MessageStatus.Failed
                message.sendingState is TdApi.MessageSendingStatePending -> MessageStatus.Sending
                message.id <= lastReadOutboxMessageId -> MessageStatus.Read
                else -> MessageStatus.Sent
            },
        )
    }
}

private fun ChatHistory.originName(origin: TdApi.MessageOrigin): String? = when (origin) {
    is TdApi.MessageOriginUser -> senderNames[origin.senderUserId]
    is TdApi.MessageOriginHiddenUser -> origin.senderName
    is TdApi.MessageOriginChat -> senderNames[origin.senderChatId]
    is TdApi.MessageOriginChannel -> senderNames[origin.chatId]
    else -> null
}

private fun TdApi.Message.senderKey(): Long = when (val sender = senderId) {
    is TdApi.MessageSenderUser -> sender.userId
    is TdApi.MessageSenderChat -> sender.chatId
    else -> 0
}
