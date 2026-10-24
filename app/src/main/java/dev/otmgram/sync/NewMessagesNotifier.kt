package dev.otmgram.sync

import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.isGroup
import dev.otmgram.ui.summary
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** New messages of one chat: its title, message lines newest first, the newest one's id. */
data class ChatNews(val chatId: Long, val title: String, val lines: List<String>, val newestId: Long)

/**
 * Decides what to notify about: messages in the folder's chats that arrived since the app
 * was last open, and not notified about yet; in muted chats, only mentions of you and
 * replies to you. [NotificationDisplay] shows it.
 */
@Singleton
class NewMessagesNotifier @Inject constructor(
    private val td: TelegramClient,
    private val chats: ChatRepository,
    private val state: NotificationState,
    private val settings: AppSettings,
    private val strings: Strings,
    private val display: NotificationDisplay,
) {
    suspend fun check(folderId: Int) {
        val folder = chats.folderChats(folderId).first()
        val seen = state.seen()
        if (seen == null) {
            // First check ever: what's there now counts as seen, rather than one big notification.
            state.markSeen(newestMessages(folder))
            AppLog.i(TAG, "First check: noted what's there")
            return
        }
        val notified = state.notified()
        val news = folder.mapNotNull { chat ->
            val after = seen[chat.chat.id] ?: 0
            when {
                !chat.isMuted && chat.chat.unreadCount > 0 -> newMessages(chat, unreadHistory(chat.chat), after)
                chat.isMuted && chat.notifiesMentions && chat.chat.unreadMentionCount > 0 -> newMessages(chat, unreadMentions(chat.chat), after)
                else -> null
            }
        }
        val count = news.sumOf { it.lines.size }
        when {
            news.isEmpty() -> cancel().also { AppLog.i(TAG, "Nothing new") } // Read elsewhere, or nothing new.
            news.any { it.newestId > (notified[it.chatId] ?: 0) } ->
                display.show(news, withText = settings.showMessageText.first()).also { AppLog.i(TAG, "Notified: $count new in ${news.size} chats") }
            else -> AppLog.i(TAG, "Already notified about $count new") // Already notified about all of it.
        }
        state.markNotified(news.associate { it.chatId to it.newestId })
    }

    /** Records the folder's messages as seen: the app is open, so they're on screen or a tap away. */
    suspend fun markSeen(folderId: Int) {
        state.markSeen(newestMessages(chats.folderChats(folderId).first()))
    }

    private fun newestMessages(folder: List<FolderChat>) = folder.associate { it.chat.id to (it.chat.lastMessage?.id ?: 0) }

    fun cancel() = display.cancel()

    /** The [unread] messages that are incoming and after message [after], or null if none. */
    private suspend fun newMessages(chat: FolderChat, unread: List<TdApi.Message>, after: Long): ChatNews? {
        val messages = unread.filter { !it.isOutgoing && it.id > after }
        if (messages.isEmpty()) return null
        val lines = messages.map { message ->
            val sender = (message.senderId as? TdApi.MessageSenderUser)
                ?.takeIf { chat.chat.isGroup }
                ?.let { td.send(TdApi.GetUser(it.userId)).firstName }
            if (sender != null) strings.get(R.string.notification_sender_line, sender, message.summary(strings)) else message.summary(strings)
        }
        val title = if (chat.isSavedMessages) strings.get(R.string.saved_messages) else chat.chat.title
        return ChatNews(chat.chat.id, title, lines, newestId = messages.maxOf { it.id })
    }

    /**
     * Messages after the last read one, newest first. TDLib often returns fewer messages than
     * asked for, so keep asking until reaching read ones.
     */
    private suspend fun unreadHistory(chat: TdApi.Chat): List<TdApi.Message> {
        val limit = chat.unreadCount.coerceAtMost(MAX_MESSAGES_PER_CHAT)
        val unread = mutableListOf<TdApi.Message>()
        var from = 0L
        while (unread.size < limit) {
            val batch = td.send(TdApi.GetChatHistory(chat.id, from, 0, limit, false)).messages
                .filter { it.id != from }
            val fresh = batch.filter { it.id > chat.lastReadInboxMessageId }
            unread += fresh
            if (batch.isEmpty() || fresh.size < batch.size) break // Reached read messages or the start.
            from = batch.last().id
        }
        return unread.take(limit)
    }

    /** Unread messages that mention you or reply to you, newest first. */
    private suspend fun unreadMentions(chat: TdApi.Chat): List<TdApi.Message> {
        val limit = chat.unreadMentionCount.coerceAtMost(MAX_MESSAGES_PER_CHAT)
        return td.send(TdApi.SearchChatMessages(chat.id, null, "", null, 0, 0, limit, TdApi.SearchMessagesFilterUnreadMention()))
            .messages.toList()
    }

    private companion object {
        const val TAG = "Notifier"
        const val MAX_MESSAGES_PER_CHAT = 20
    }
}
