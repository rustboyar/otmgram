package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val td: TdClient,
) {
    /** Opens [chatId] for as long as [scope] lives; see [ChatSession]. */
    fun openChat(chatId: Long, scope: CoroutineScope): ChatSession = ChatSession(td, chatId, scope)
}

/** Loaded history of a chat, newest message first. */
data class ChatHistory(
    val messages: List<TdApi.Message> = emptyList(),
    /** Display names of message senders and forward origins, by user or chat id. */
    val senderNames: Map<Long, String> = emptyMap(),
    /** Messages replied to by loaded messages, by id. */
    val repliedMessages: Map<Long, TdApi.Message> = emptyMap(),
    /** Outgoing messages up to this id have been read by the other side. */
    val lastReadOutboxMessageId: Long = 0,
    /** True once the beginning of the chat is loaded. */
    val reachedStart: Boolean = false,
)

/**
 * An open chat: its history, kept up to date from TDLib updates, and actions on it.
 *
 * Opening the chat in TDLib is needed to mark messages as read; it's closed when [scope]
 * ends. Must be used from a single thread, like a ViewModel's scope.
 */
class ChatSession internal constructor(
    private val td: TdClient,
    val chatId: Long,
    private val scope: CoroutineScope,
) {
    private val messages = TreeMap<Long, TdApi.Message>(reverseOrder())
    private val senderNames = mutableMapOf<Long, String>()
    private val repliedMessages = mutableMapOf<Long, TdApi.Message>()
    private var lastReadOutboxMessageId = 0L
    private var reachedStart = false
    private var loading = false

    private val _history = MutableStateFlow(ChatHistory())
    val history: StateFlow<ChatHistory> = _history.asStateFlow()

    init {
        // Subscribe before loading, so no update is missed in between.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect(::onUpdate)
        }
        scope.launch {
            td.send(TdApi.OpenChat(chatId))
            lastReadOutboxMessageId = td.send(TdApi.GetChat(chatId)).lastReadOutboxMessageId
            loadOlder()
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            td.sendIgnoringResult(TdApi.CloseChat(chatId))
        }
    }

    /** Loads the next page of older messages, unless already loading or at the start. */
    suspend fun loadOlder() {
        if (loading || reachedStart) return
        loading = true
        try {
            var received = 0
            // TDLib often returns fewer messages than asked for; keep asking.
            while (received < PAGE_SIZE) {
                val from = if (messages.isEmpty()) 0 else messages.lastKey()
                val batch = td.send(TdApi.GetChatHistory(chatId, from, 0, PAGE_SIZE, false)).messages
                    .filter { it.id !in messages }
                if (batch.isEmpty()) {
                    reachedStart = true
                    break
                }
                batch.forEach { messages[it.id] = it }
                received += batch.size
            }
            resolveReferences()
        } finally {
            loading = false
            publish()
        }
    }

    suspend fun sendText(text: String, replyToMessageId: Long? = null) {
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        td.send(TdApi.SendMessage(chatId, null, replyTo, null, null, textContent(text)))
    }

    suspend fun editText(messageId: Long, text: String) {
        td.send(TdApi.EditMessageText(chatId, messageId, null, textContent(text)))
    }

    /** [forEveryone] deletes the message for all chat members, not just for you. */
    suspend fun delete(messageId: Long, forEveryone: Boolean) {
        td.send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), forEveryone))
    }

    suspend fun forward(messageId: Long, toChatId: Long) {
        td.send(TdApi.ForwardMessages(toChatId, null, chatId, longArrayOf(messageId), null, false, false))
    }

    suspend fun properties(messageId: Long): TdApi.MessageProperties =
        td.send(TdApi.GetMessageProperties(chatId, messageId))

    /** Marks messages shown on screen as read. */
    suspend fun markViewed(messageIds: List<Long>) {
        td.send(TdApi.ViewMessages(chatId, messageIds.toLongArray(), TdApi.MessageSourceChatHistory(), false))
    }

    private suspend fun onUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                if (update.message.chatId != chatId) return
                messages[update.message.id] = update.message
                resolveReferences()
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                if (update.message.chatId != chatId) return
                messages.remove(update.oldMessageId)
                messages[update.message.id] = update.message
            }
            is TdApi.UpdateMessageSendFailed -> {
                if (update.message.chatId != chatId) return
                messages.remove(update.oldMessageId)
                messages[update.message.id] = update.message
            }
            is TdApi.UpdateMessageContent -> {
                if (update.chatId != chatId) return
                messages[update.messageId]?.content = update.newContent
            }
            is TdApi.UpdateMessageEdited -> {
                if (update.chatId != chatId) return
                messages[update.messageId]?.editDate = update.editDate
            }
            is TdApi.UpdateMessageInteractionInfo -> {
                if (update.chatId != chatId) return
                messages[update.messageId]?.interactionInfo = update.interactionInfo
            }
            is TdApi.UpdateDeleteMessages -> {
                // Messages only evicted from TDLib's cache still exist.
                if (update.chatId != chatId || !update.isPermanent) return
                update.messageIds.forEach { messages.remove(it) }
            }
            is TdApi.UpdateChatReadOutbox -> {
                if (update.chatId != chatId) return
                lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }
            else -> return
        }
        publish()
    }

    /** Loads what loaded messages refer to: sender and forward origin names, replied messages. */
    private suspend fun resolveReferences() {
        for (message in messages.values.toList()) {
            when (val sender = message.senderId) {
                is TdApi.MessageSenderUser -> resolveUserName(sender.userId)
                is TdApi.MessageSenderChat -> resolveChatName(sender.chatId)
            }
            when (val origin = message.forwardInfo?.origin) {
                is TdApi.MessageOriginUser -> resolveUserName(origin.senderUserId)
                is TdApi.MessageOriginChat -> resolveChatName(origin.senderChatId)
                is TdApi.MessageOriginChannel -> resolveChatName(origin.chatId)
            }
            val replyTo = message.replyTo as? TdApi.MessageReplyToMessage ?: continue
            val known = replyTo.messageId in messages || replyTo.messageId in repliedMessages
            if (replyTo.chatId == chatId && !known) {
                val replied = try {
                    td.send(TdApi.GetRepliedMessage(chatId, message.id))
                } catch (e: TdException) {
                    continue // Deleted, or not accessible.
                }
                repliedMessages[replied.id] = replied
                (replied.senderId as? TdApi.MessageSenderUser)?.let { resolveUserName(it.userId) }
            }
        }
    }

    private suspend fun resolveUserName(userId: Long) {
        if (userId in senderNames) return
        val user = td.send(TdApi.GetUser(userId))
        senderNames[userId] = "${user.firstName} ${user.lastName}".trim()
    }

    private suspend fun resolveChatName(chatId: Long) {
        if (chatId in senderNames) return
        senderNames[chatId] = td.send(TdApi.GetChat(chatId)).title
    }

    private fun publish() {
        _history.value = ChatHistory(
            messages = messages.values.toList(),
            senderNames = senderNames.toMap(),
            repliedMessages = repliedMessages.toMap(),
            lastReadOutboxMessageId = lastReadOutboxMessageId,
            reachedStart = reachedStart,
        )
    }

    private fun textContent(text: String) =
        TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)

    private companion object {
        const val PAGE_SIZE = 50
    }
}
