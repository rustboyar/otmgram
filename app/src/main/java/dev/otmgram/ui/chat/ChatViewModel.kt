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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class ChatHeader(val title: String, val isGroup: Boolean)

enum class MessageStatus { Sending, Sent, Read, Failed }

data class MessageItem(
    val id: Long,
    val isOutgoing: Boolean,
    /** An event like "group created" rather than a message. */
    val isService: Boolean,
    /** Shown above the first of consecutive incoming group messages from the same sender. */
    val senderName: String?,
    val text: String,
    /** True if [text] describes media that can't be shown yet. */
    val isMediaPlaceholder: Boolean,
    /** Unix time. */
    val date: Int,
    val isEdited: Boolean,
    /** Null for incoming messages. */
    val status: MessageStatus?,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    messages: MessageRepository,
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

    val reachedStart: Boolean get() = session.history.value.reachedStart

    /** Error of the last failed action, for a one-time message. */
    var error by mutableStateOf<String?>(null)
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
        action { session.sendText(text.trim()) }
    }

    /** Called with the ids of messages currently on screen. */
    fun onMessagesVisible(ids: List<Long>) {
        val new = ids.filter { viewedIds.add(it) }
        if (new.isNotEmpty()) action { session.markViewed(new) }
    }

    fun errorShown() {
        error = null
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: TdException) {
                error = e.error.message
            }
        }
    }
}

private fun ChatHistory.toItems(isGroup: Boolean): List<MessageItem> =
    messages.mapIndexed { index, message ->
        val older = messages.getOrNull(index + 1)
        val showSender = isGroup && !message.isOutgoing && !message.isService &&
            (older == null || older.isOutgoing || older.isService || older.senderKey() != message.senderKey())
        MessageItem(
            id = message.id,
            isOutgoing = message.isOutgoing,
            isService = message.isService,
            senderName = if (showSender) senderNames[message.senderKey()] else null,
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

private fun TdApi.Message.senderKey(): Long = when (val sender = senderId) {
    is TdApi.MessageSenderUser -> sender.userId
    is TdApi.MessageSenderChat -> sender.chatId
    else -> 0
}
