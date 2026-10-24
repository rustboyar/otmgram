package dev.otmgram.data

import dev.otmgram.core.launchLogged
import dev.otmgram.core.media.VoiceRecording
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val td: TelegramClient,
) {
    /** Sends a square video message of [length] px from a local file. */
    suspend fun sendVideoNote(chatId: Long, file: File, duration: Int, length: Int) {
        val videoNote = TdApi.InputVideoNote(TdApi.InputFileLocal(file.path), null, duration, length)
        td.send(TdApi.SendMessage(chatId, null, null, null, null, TdApi.InputMessageVideoNote(videoNote, null)))
    }

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
    /**
     * Incremented on every change. Messages are TDLib objects updated in place, so without
     * it a changed history could equal the previous one and StateFlow would drop it.
     */
    val version: Long = 0,
)

/**
 * An open chat: its history, kept up to date from TDLib updates, and actions on it.
 *
 * Opening the chat in TDLib is needed to mark messages as read; it's closed when [scope]
 * ends. Must be used from a single thread, like a ViewModel's scope.
 */
class ChatSession internal constructor(
    private val td: TelegramClient,
    val chatId: Long,
    private val scope: CoroutineScope,
) {
    private val messages = TreeMap<Long, TdApi.Message>(reverseOrder())
    private val senderNames = mutableMapOf<Long, String>()
    private val repliedMessages = mutableMapOf<Long, TdApi.Message>()
    private var lastReadOutboxMessageId = 0L
    private var reachedStart = false
    private var loading = false
    private var version = 0L

    private val _history = MutableStateFlow(ChatHistory())
    val history: StateFlow<ChatHistory> = _history.asStateFlow()

    init {
        // Subscribe before loading, so no update is missed in between.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect(::onUpdate)
        }
        scope.launchLogged(TAG) {
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
        send(textContent(text), replyToMessageId)
    }

    suspend fun sendSticker(sticker: Sticker) {
        val input = TdApi.InputSticker(TdApi.InputFileId(sticker.fileId), null, sticker.width, sticker.height)
        send(TdApi.InputMessageSticker(input, sticker.emoji))
    }

    suspend fun sendLocation(latitude: Double, longitude: Double, accuracy: Double) {
        send(TdApi.InputMessageLocation(TdApi.Location(latitude, longitude, accuracy)))
    }

    suspend fun sendVoice(recording: VoiceRecording) {
        val voice = TdApi.InputVoiceNote(TdApi.InputFileLocal(recording.file.path), recording.duration, recording.waveform)
        send(TdApi.InputMessageVoiceNote(voice, null, null))
    }

    /** Sends media; several items go as one album (up to 10, Telegram's limit). */
    suspend fun sendMedia(contents: List<TdApi.InputMessageContent>) {
        for (album in contents.chunked(ALBUM_SIZE)) {
            if (album.size == 1) {
                send(album.single())
            } else {
                td.send(TdApi.SendMessageAlbum(chatId, null, null, null, album.toTypedArray()))
            }
        }
    }

    private suspend fun send(content: TdApi.InputMessageContent, replyToMessageId: Long? = null) {
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        td.send(TdApi.SendMessage(chatId, null, replyTo, null, null, content))
    }

    suspend fun editText(messageId: Long, text: String) {
        td.send(TdApi.EditMessageText(chatId, messageId, null, textContent(text)))
    }

    suspend fun editCaption(messageId: Long, caption: String) {
        td.send(TdApi.EditMessageCaption(chatId, messageId, null, TdApi.FormattedText(caption, emptyArray()), false))
    }

    /** [forEveryone] deletes the messages for all chat members, not just for you. */
    suspend fun delete(messageIds: List<Long>, forEveryone: Boolean) {
        td.send(TdApi.DeleteMessages(chatId, messageIds.toLongArray(), forEveryone))
    }

    /** Forwards messages in chat order. */
    suspend fun forward(messageIds: List<Long>, toChatId: Long) {
        td.send(TdApi.ForwardMessages(toChatId, null, chatId, messageIds.sorted().toLongArray(), null, false, false))
    }

    /** Sends failed messages again. */
    suspend fun resend(messageIds: List<Long>) {
        td.send(TdApi.ResendMessages(chatId, messageIds.toLongArray(), null, 0))
    }

    suspend fun properties(messageId: Long): TdApi.MessageProperties =
        td.send(TdApi.GetMessageProperties(chatId, messageId))

    /** Emoji reactions that can be added to the message, most used first. */
    suspend fun availableReactions(messageId: Long): List<String> {
        val available = td.send(TdApi.GetMessageAvailableReactions(chatId, messageId, REACTION_ROW_SIZE))
        return (available.topReactions + available.recentReactions + available.popularReactions)
            .filter { !it.needsPremium }
            .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
            .distinct()
    }

    suspend fun setReaction(messageId: Long, emoji: String, chosen: Boolean) {
        val type = TdApi.ReactionTypeEmoji(emoji)
        if (chosen) {
            td.send(TdApi.AddMessageReaction(chatId, messageId, type, false, true))
        } else {
            td.send(TdApi.RemoveMessageReaction(chatId, messageId, type))
        }
    }

    /** Messages of this chat containing [query], newest first. */
    suspend fun search(query: String): List<TdApi.Message> =
        td.send(TdApi.SearchChatMessages(chatId, null, query, null, 0, 0, SEARCH_LIMIT, null)).messages.toList()

    /** Loads older pages until [messageId] is loaded; false if it couldn't be reached. */
    suspend fun loadUntil(messageId: Long): Boolean {
        while (messageId !in messages && !reachedStart) {
            val before = messages.size
            loadOlder()
            if (messages.size == before) break // Another load is running, or nothing new.
        }
        return messageId in messages
    }

    /** Marks messages shown on screen as read. */
    suspend fun markViewed(messageIds: List<Long>) {
        td.send(TdApi.ViewMessages(chatId, messageIds.toLongArray(), TdApi.MessageSourceChatHistory(), false))
    }

    /** Applies [update] right away; names it refers to are looked up separately. */
    private fun onUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                if (update.message.chatId != chatId) return
                messages[update.message.id] = update.message
                resolveSoon()
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

    private var resolving: Job? = null
    private var resolveAgain = false

    /**
     * Looks up names and replied messages outside the update collector, which must never
     * wait for TDLib (updates would pile up behind it); runs once more if asked meanwhile.
     */
    private fun resolveSoon() {
        if (resolving?.isActive == true) {
            resolveAgain = true
            return
        }
        resolving = scope.launchLogged(TAG) {
            do {
                resolveAgain = false
                resolveReferences()
                publish()
            } while (resolveAgain)
        }
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

    // A name that can't be loaded stays unknown; the message still shows.
    private suspend fun resolveUserName(userId: Long) {
        if (userId in senderNames) return
        val user = try {
            td.send(TdApi.GetUser(userId))
        } catch (e: TdException) {
            return
        }
        senderNames[userId] = "${user.firstName} ${user.lastName}".trim()
    }

    private suspend fun resolveChatName(chatId: Long) {
        if (chatId in senderNames) return
        senderNames[chatId] = try {
            td.send(TdApi.GetChat(chatId)).title
        } catch (e: TdException) {
            return
        }
    }

    private fun publish() {
        _history.value = ChatHistory(
            messages = messages.values.toList(),
            senderNames = senderNames.toMap(),
            repliedMessages = repliedMessages.toMap(),
            lastReadOutboxMessageId = lastReadOutboxMessageId,
            reachedStart = reachedStart,
            version = ++version,
        )
    }

    private fun textContent(text: String) =
        TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)

    private companion object {
        const val TAG = "ChatSession"
        const val PAGE_SIZE = 50
        const val SEARCH_LIMIT = 100
        const val REACTION_ROW_SIZE = 8
        const val ALBUM_SIZE = 10
    }
}
