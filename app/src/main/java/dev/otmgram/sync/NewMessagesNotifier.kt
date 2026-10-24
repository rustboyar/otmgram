package dev.otmgram.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdClient
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
internal class ChatNews(val chatId: Long, val title: String, val lines: List<String>, val newestId: Long)

/**
 * Notifies about messages in the folder's chats that arrived since the app was last open:
 * one notification, grouped when there's more than one message, opening the chat list.
 */
@Singleton
class NewMessagesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val chats: ChatRepository,
    private val state: NotificationState,
    private val settings: AppSettings,
    private val strings: Strings,
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
        val news = folder
            .filter { !it.isMuted && it.chat.unreadCount > 0 }
            .mapNotNull { newMessages(it, after = seen[it.chat.id] ?: 0) }
        val count = news.sumOf { it.lines.size }
        when {
            news.isEmpty() -> cancel().also { AppLog.i(TAG, "Nothing new") } // Read elsewhere, or nothing new.
            news.any { it.newestId > (notified[it.chatId] ?: 0) } ->
                show(news, withText = settings.showMessageText.first()).also { AppLog.i(TAG, "Notified: $count new in ${news.size} chats") }
            else -> AppLog.i(TAG, "Already notified about $count new") // Already notified about all of it.
        }
        state.markNotified(news.associate { it.chatId to it.newestId })
    }

    /** Records the folder's messages as seen: the app is open, so they're on screen or a tap away. */
    suspend fun markSeen(folderId: Int) {
        state.markSeen(newestMessages(chats.folderChats(folderId).first()))
    }

    private fun newestMessages(folder: List<FolderChat>) = folder.associate { it.chat.id to (it.chat.lastMessage?.id ?: 0) }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Unread incoming messages of the chat after message [after], or null if none. */
    private suspend fun newMessages(chat: FolderChat, after: Long): ChatNews? {
        val messages = unreadHistory(chat.chat).filter { !it.isOutgoing && it.id > after }
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

    /**
     * Shows [news] as one notification. Without [withText] it says only who wrote, so no
     * message text can reach the lock screen whatever Android's settings; with it, the
     * lock screen gets a text-free version when Android hides sensitive content there.
     */
    internal fun show(news: List<ChatNews>, withText: Boolean) {
        // Covers the runtime permission (Android 13+) and notifications turned off for the app.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // A new id: lock screen visibility can only be set when a channel is created.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.get(R.string.messages_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        // Always the chat list: a fresh task, whatever screen was open before.
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val count = news.sumOf { it.lines.size }
        val countText = strings.plural(R.plurals.new_messages, count, count)
        // What a locked phone shows instead: no text, no names.
        val locked = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(strings.get(R.string.app_name))
            .setContentText(countText)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setNumber(count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
        if (!withText) {
            if (news.size == 1) {
                builder.setContentTitle(news.single().title).setContentText(countText)
            } else {
                val style = NotificationCompat.InboxStyle()
                news.forEach { chat -> style.addLine(strings.plural(R.plurals.notification_chat_count, chat.lines.size, chat.title, chat.lines.size)) }
                builder.setContentTitle(countText)
                    .setContentText(news.joinToString(", ") { it.title })
                    .setStyle(style)
            }
        } else if (count == 1) {
            val only = news.single()
            builder.setContentTitle(only.title).setContentText(only.lines.single())
        } else {
            val chatsText = if (news.size == 1) news.single().title else strings.plural(R.plurals.chats, news.size, news.size)
            val style = NotificationCompat.InboxStyle()
            if (news.size == 1) {
                // One chat: its messages, oldest first, like in the chat.
                news.single().lines.take(MAX_LINES).reversed().forEach(style::addLine)
            } else {
                // Several chats: one line each, with the newest message.
                news.forEach { chat ->
                    val more = chat.lines.size - 1
                    style.addLine(
                        if (more > 0) strings.plural(R.plurals.notification_chat_newest_more, more, chat.title, chat.lines.first(), more)
                        else strings.get(R.string.notification_chat_newest, chat.title, chat.lines.first()),
                    )
                }
            }
            builder.setContentTitle(countText)
                .setContentText(news.joinToString(", ") { it.title })
                .setStyle(style.setSummaryText(chatsText))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private companion object {
        const val TAG = "Notifier"
        const val CHANNEL_ID = "messages"
        const val OLD_CHANNEL_ID = "new_messages"
        const val NOTIFICATION_ID = 2
        const val MAX_MESSAGES_PER_CHAT = 20

        /** Android shows about this many lines of an expanded notification. */
        const val MAX_LINES = 5
    }
}
