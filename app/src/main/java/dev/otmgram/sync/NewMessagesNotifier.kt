package dev.otmgram.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.isGroup
import dev.otmgram.ui.summary
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** New messages of one chat, newest first. */
private class ChatNews(val title: String, val lines: List<String>, val newest: Int)

/**
 * Notifies about messages in the folder's chats that arrived since the app was last open:
 * one notification, grouped when there's more than one message, opening the chat list.
 */
@Singleton
class NewMessagesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val chats: ChatRepository,
    private val folders: FolderRepository,
    private val state: NotificationState,
) {
    suspend fun check(folderId: Int) {
        val seenUpTo = state.seenUpTo()
        val notifiedUpTo = state.notifiedUpTo()
        val news = chats.folderChats(folderId).first()
            .filter { !it.isMuted && it.chat.unreadCount > 0 }
            .mapNotNull { newMessages(it, since = seenUpTo) }
        when {
            news.isEmpty() -> cancel() // Read elsewhere, or nothing new.
            news.any { it.newest > notifiedUpTo } -> show(news, folderName(folderId))
            else -> Unit // Already notified about all of it.
        }
        state.markNotified()
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Unread incoming messages of the chat newer than [since], or null if none. */
    private suspend fun newMessages(chat: FolderChat, since: Int): ChatNews? {
        val messages = unreadHistory(chat.chat).filter { !it.isOutgoing && it.date > since }
        if (messages.isEmpty()) return null
        val lines = messages.map { message ->
            val sender = (message.senderId as? TdApi.MessageSenderUser)
                ?.takeIf { chat.chat.isGroup }
                ?.let { td.send(TdApi.GetUser(it.userId)).firstName }
            if (sender != null) "$sender: ${message.summary()}" else message.summary()
        }
        val title = if (chat.isSavedMessages) "Saved Messages" else chat.chat.title
        return ChatNews(title, lines, newest = messages.maxOf { it.date })
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

    /** The name shown over the chat list; null if it's set to show none. */
    private suspend fun folderName(folderId: Int): String? {
        folders.headerName.first()?.let { return it.ifBlank { null } }
        return try {
            td.send(TdApi.GetChatFolder(folderId)).name.text.text
        } catch (e: TdException) {
            null
        }
    }

    private fun show(news: List<ChatNews>, folderName: String?) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Always the chat list: a fresh task, whatever screen was open before.
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val count = news.sumOf { it.lines.size }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setNumber(count)
        if (count == 1) {
            val only = news.single()
            builder.setContentTitle(only.title).setContentText(only.lines.single())
        } else {
            val chatsText = if (news.size == 1) news.single().title else "${news.size} chats"
            val style = NotificationCompat.InboxStyle()
            if (news.size == 1) {
                // One chat: its messages, oldest first, like in the chat.
                news.single().lines.take(MAX_LINES).reversed().forEach(style::addLine)
            } else {
                // Several chats: one line each, with the newest message.
                news.forEach { chat ->
                    val more = if (chat.lines.size > 1) " (+${chat.lines.size - 1} more)" else ""
                    style.addLine("${chat.title}: ${chat.lines.first()}$more")
                }
            }
            builder.setContentTitle(if (folderName != null) "$count new in $folderName" else "$count new messages")
                .setContentText(news.joinToString(", ") { it.title })
                .setStyle(style.setSummaryText(chatsText))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private companion object {
        const val CHANNEL_ID = "new_messages"
        const val NOTIFICATION_ID = 2
        const val MAX_MESSAGES_PER_CHAT = 20

        /** Android shows about this many lines of an expanded notification. */
        const val MAX_LINES = 5
    }
}
