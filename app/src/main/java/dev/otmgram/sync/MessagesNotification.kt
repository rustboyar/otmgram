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
import javax.inject.Inject

/** Where the notifier's decisions end up: Android's notification, or a test's record. */
interface NotificationDisplay {
    /** Shows [news] (replacing what's shown); [withText]: message text in it, not only who wrote. */
    fun show(news: List<ChatNews>, withText: Boolean)

    fun cancel()
}

/** The one notification about new messages: grouped when there's more than one, opening the chat list. */
class MessagesNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: Strings,
) : NotificationDisplay {

    /**
     * Shows [news] as one notification. Without [withText] it says only who wrote, so no
     * message text can reach the lock screen whatever Android's settings; with it, the
     * lock screen gets a text-free version when Android hides sensitive content there.
     */
    override fun show(news: List<ChatNews>, withText: Boolean) {
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
        // A fresh task, whatever screen was open before: the chat when it's about one, else
        // the chat list. Updated, as extras don't tell pending intents apart.
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .apply { news.singleOrNull()?.let { putExtra(MainActivity.EXTRA_OPEN_CHAT, it.chatId) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "messages"
        const val OLD_CHANNEL_ID = "new_messages"
        const val NOTIFICATION_ID = 2

        /** Android shows about this many lines of an expanded notification. */
        const val MAX_LINES = 5
    }
}
