package dev.otmgram.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.Strings
import javax.inject.Inject

/** Where [NotificationCenter]'s decisions end up: Android's notifications, or a test's record. */
interface NotificationDisplay {
    /** Shows or replaces [chat]'s notification; [alert]: with sound, for a new message. */
    fun show(chat: ChatNotification, alert: Boolean)

    fun cancel(chatId: Long)

    fun cancelAll()

    /** Chats with a notification shown now, each with the newest message in it. */
    fun shown(): Map<Long, Long>
}

/**
 * A notification per chat, grouped. Without message text it says only how many messages
 * arrived, so no text can reach the lock screen whatever Android's settings; with it, the
 * lock screen still gets a version without text or names.
 */
class MessagesNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: Strings,
) : NotificationDisplay {

    private val manager = NotificationManagerCompat.from(context)

    override fun show(chat: ChatNotification, alert: Boolean) {
        // Covers the runtime permission (Android 13+) and notifications turned off for the app.
        if (!manager.areNotificationsEnabled()) return
        createChannel()
        val count = chat.messages.size
        val countText = strings.plural(R.plurals.new_messages, count, count)
        val newest = chat.messages.last()
        // What a locked phone shows instead: no text, no names.
        val locked = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(strings.get(R.string.app_name))
            .setContentText(countText)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(chat.chatId))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setWhen(newest.date * 1000L)
            .setShowWhen(true)
            .setNumber(count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setSilent(!alert)
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_NEWEST, newest.id) })
        chat.photoPath?.let(::circle)?.let(builder::setLargeIcon)
        if (chat.withText) {
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName(strings.get(R.string.notification_you)).build())
                .setGroupConversation(chat.isGroup)
            if (chat.isGroup) style.setConversationTitle(chat.title)
            chat.messages.forEach { message ->
                style.addMessage(message.text, message.date * 1000L, Person.Builder().setName(message.sender).setKey(message.sender).build())
            }
            builder.setStyle(style).setContentTitle(chat.title).setContentText(newest.text)
        } else {
            builder.setContentTitle(chat.title).setContentText(countText)
        }
        manager.notify(chat.chatId.toString(), CHAT_NOTIFICATION_ID, builder.build())
        showSummary()
    }

    override fun cancel(chatId: Long) {
        manager.cancel(chatId.toString(), CHAT_NOTIFICATION_ID)
        if (shown().keys.none { it != chatId }) manager.cancel(SUMMARY_ID)
    }

    override fun cancelAll() {
        shown().keys.forEach { manager.cancel(it.toString(), CHAT_NOTIFICATION_ID) }
        manager.cancel(SUMMARY_ID)
        manager.cancel(OLD_NOTIFICATION_ID) // The one summary of earlier versions.
    }

    override fun shown(): Map<Long, Long> = context.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.id == CHAT_NOTIFICATION_ID && it.tag?.toLongOrNull() != null }
        .associate { it.tag.toLong() to it.notification.extras.getLong(EXTRA_NEWEST) }

    /** Bundles the chats' notifications; Android shows it only once there are two or more. */
    private fun showSummary() {
        if (!manager.areNotificationsEnabled()) return
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(strings.get(R.string.app_name))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openChat(null))
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    private fun createChannel() {
        val system = context.getSystemService(NotificationManager::class.java)
        // New ids: importance and lock screen visibility can only be set when a channel is created.
        OLD_CHANNEL_IDS.forEach(system::deleteNotificationChannel)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.get(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * A fresh task, whatever screen was open before: the chat over the chat list, or only
     * the list. One per chat, as extras don't tell pending intents apart.
     */
    private fun openChat(chatId: Long?): PendingIntent = PendingIntent.getActivity(
        context,
        chatId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .apply { chatId?.let { putExtra(MainActivity.EXTRA_OPEN_CHAT, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The chat photo as a circle, as chats show it; null if it can't be read. */
    private fun circle(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val photo = BitmapFactory.decodeFile(path, options) ?: return null
        val side = minOf(photo.width, photo.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        return createBitmap(side, side).applyCanvas { drawCircle(side / 2f, side / 2f, side / 2f, paint) }
    }

    companion object {
        private const val CHANNEL_ID = "chats"
        private val OLD_CHANNEL_IDS = listOf("new_messages", "messages")
        private const val GROUP = "messages"
        private const val CHAT_NOTIFICATION_ID = 3
        private const val SUMMARY_ID = 4
        private const val OLD_NOTIFICATION_ID = 2
        private const val EXTRA_NEWEST = "dev.otmgram.extra.NEWEST_MESSAGE"

        /** Whether these notifications can show: not refused, turned off, or their channel blocked. */
        fun allowed(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannelCompat(CHANNEL_ID)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }
    }
}
