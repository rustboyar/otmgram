package dev.otmgram.sync

import dev.otmgram.core.Strings
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MAX_FOLDER_CHATS
import dev.otmgram.data.isGroup
import dev.otmgram.data.isShown
import dev.otmgram.data.senderName
import dev.otmgram.data.summary
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One chat's notification: its messages oldest first. */
data class ChatNotification(
    val chatId: Long,
    val title: String,
    val isGroup: Boolean,
    val messages: List<NotifiedMessage>,
    /** The chat's photo, if it's downloaded. */
    val photoPath: String?,
    /** Message text may show: the user's setting, and the chat's preview setting in Telegram. */
    val withText: Boolean,
)

data class NotifiedMessage(val id: Long, val sender: String, val text: String, val date: Int)

/**
 * Shows notifications as TDLib's Notification API decides them: TDLib applies mute settings
 * and mentions, waits a moment so a message read on another device never alerts, and
 * removes notifications when messages are read anywhere or deleted. This shows only the
 * folder's chats, nothing while the app is open, and nothing that was in the app while it
 * was open ([NotificationState.seen]).
 *
 * Works whenever the process runs: live while it's alive in the background, and during
 * the backup check ([CheckWorker]), which waits for [havePending] to clear.
 */
@Singleton
class NotificationCenter @Inject constructor(
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val state: NotificationState,
    private val settings: AppSettings,
    private val strings: Strings,
    private val display: NotificationDisplay,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Active notification groups, by id, as TDLib last told. */
    private val groups = mutableMapOf<Int, TdApi.NotificationGroup>()

    /** Chats whose notification this process has shown. */
    private val rendered: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Chats whose notifications changed since they were last shown. */
    private val changed = mutableSetOf<Long>()

    @Volatile
    private var foreground = false

    private val _havePending = MutableStateFlow(false)

    /** TDLib holds notifications back for a moment, or can't know them yet without a connection. */
    val havePending: StateFlow<Boolean> = _havePending.asStateFlow()

    /** Subscribes to updates; call before TDLib starts, so the first active notifications aren't missed. */
    fun start() {
        val changes = Channel<TdApi.Update>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> if (update.authorizationState is TdApi.AuthorizationStateReady) enable()
                    is TdApi.UpdateHavePendingNotifications ->
                        _havePending.value = update.haveDelayedNotifications || update.haveUnreceivedNotifications
                    is TdApi.UpdateActiveNotifications, is TdApi.UpdateNotificationGroup, is TdApi.UpdateNotification -> changes.send(update)
                    else -> {}
                }
            }
        }
        scope.launch {
            while (true) {
                apply(changes.receive())
                while (true) apply(changes.tryReceive().getOrNull() ?: break)
                // Any failure, not only TDLib's: this loop is all there is to notifications.
                try {
                    render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(TAG, "Showing notifications failed", e)
                }
            }
        }
    }

    /** The app is on screen (or not): nothing is shown meanwhile, and what was is removed. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) clear()
    }

    /** Removes all notifications, e.g. when they're turned off. */
    fun clear() {
        display.cancelAll()
        rendered.clear()
    }

    /** Records the folder's messages as seen: the app is open, so they're on screen or a tap away. */
    suspend fun markSeen(folderId: Int) {
        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), MAX_FOLDER_CHATS)).chatIds
        state.markSeen(chatIds.associateWith { td.send(TdApi.GetChat(it)).lastMessage?.id ?: 0 })
    }

    private fun enable() {
        td.sendIgnoringResult(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(MAX_GROUPS.toLong())))
        td.sendIgnoringResult(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(MAX_GROUP_SIZE.toLong())))
    }

    private fun apply(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateActiveNotifications -> {
                changed += groups.values.map { it.chatId } + update.groups.map { it.chatId }
                groups.clear()
                update.groups.forEach { groups[it.id] = it }
            }
            is TdApi.UpdateNotificationGroup -> {
                changed += listOfNotNull(update.chatId, groups[update.notificationGroupId]?.chatId)
                val old = groups[update.notificationGroupId]?.notifications.orEmpty()
                val removed = update.removedNotificationIds.toSet()
                val added = update.addedNotifications.map { it.id }.toSet()
                val notifications = (old.filter { it.id !in removed && it.id !in added } + update.addedNotifications).sortedBy { it.id }
                if (notifications.isEmpty()) {
                    groups.remove(update.notificationGroupId)
                } else {
                    groups[update.notificationGroupId] = TdApi.NotificationGroup(
                        update.notificationGroupId, update.type, update.chatId, update.totalCount, notifications.toTypedArray(),
                    )
                }
            }
            is TdApi.UpdateNotification -> groups[update.notificationGroupId]?.let { group ->
                changed += group.chatId
                group.notifications = group.notifications.map { if (it.id == update.notification.id) update.notification else it }.toTypedArray()
            }
            else -> {}
        }
    }

    private suspend fun render() {
        // Alerted once, by any process: also those TDLib got before showing them, which
        // come in its list of active notifications at start.
        val alerting = state.alertedUpTo()
        groups.values.flatMap { it.notifications.toList() }.maxOfOrNull { it.id }?.let { state.setAlertedUpTo(it) }
        val changedChats = changed.toSet()
        changed.clear()
        if (foreground) return
        val folderId = folders.selectedFolderId.first() ?: return
        val folder = td.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), MAX_FOLDER_CHATS)).chatIds.toSet()
        val wanted = notifiedMessages(groups.values, folder, state.seen().orEmpty())
        val withText = settings.showMessageText.first()
        wanted.forEach { (chatId, notifications) ->
            // Unchanged chats stay as they are, also when swiped away.
            if (chatId !in changedChats && chatId in rendered) return@forEach
            val chat = td.send(TdApi.GetChat(chatId))
            if (!chat.isShown) return@forEach
            // Opened meanwhile (the requests above take a moment): it cleared them, and stays so.
            if (foreground) return
            val alert = notifications.any { it.id > alerting && !it.isSilent }
            display.show(chatNotification(chat, notifications, withText), alert)
            rendered += chatId
            AppLog.i(TAG, "Shown ${notifications.size} in a chat${if (alert) ", alerting" else ""}")
        }
        rendered.retainAll(wanted.keys)
        // Gone from TDLib's list: read, deleted, or pushed out by other chats' notifications.
        // Only the first two remove it here; other chats can't crowd out the folder's.
        display.shown().forEach { (chatId, newestMessageId) ->
            if (chatId in wanted) return@forEach
            val chat = try {
                td.send(TdApi.GetChat(chatId))
            } catch (e: TdException) {
                null // Deleted, or left.
            }
            if (chat == null || chat.lastReadInboxMessageId >= newestMessageId || (chat.unreadCount == 0 && chat.unreadMentionCount == 0)) {
                display.cancel(chatId)
                rendered.remove(chatId)
                AppLog.i(TAG, "Removed a chat's notification")
            }
        }
    }

    private suspend fun chatNotification(chat: TdApi.Chat, notifications: List<TdApi.Notification>, withText: Boolean): ChatNotification {
        var preview = true
        val messages = notifications.mapNotNull { notification ->
            when (val type = notification.type) {
                is TdApi.NotificationTypeNewMessage -> {
                    preview = preview && type.showPreview
                    NotifiedMessage(type.message.id, senderName(chat, type.message.senderId), type.message.summary(strings), notification.date)
                }
                is TdApi.NotificationTypeNewPushMessage -> NotifiedMessage(
                    type.messageId,
                    if (chat.isGroup) type.senderName else chat.title,
                    type.content.summary(strings),
                    notification.date,
                )
                else -> null
            }
        }
        val photo = chat.photo?.small?.local?.takeIf { it.isDownloadingCompleted }?.path
        return ChatNotification(chat.id, chat.title, chat.isGroup, messages, photo, withText = withText && preview)
    }

    private suspend fun senderName(chat: TdApi.Chat, sender: TdApi.MessageSender): String {
        if (!chat.isGroup) return chat.title
        return try {
            td.senderName(sender, firstOnly = true) ?: chat.title
        } catch (e: TdException) {
            chat.title
        }
    }

    private companion object {
        const val TAG = "Notifications"

        /** TDLib's most; other chats' groups count too, though only the folder's show. */
        const val MAX_GROUPS = 25
        const val MAX_GROUP_SIZE = 10
    }
}

/**
 * The notifications to show per chat, oldest first: those of the [folder]'s chats, about
 * messages newer than what was [seen] in the app. A chat's mentions and its other messages
 * (two TDLib groups) make one list.
 */
internal fun notifiedMessages(
    groups: Collection<TdApi.NotificationGroup>,
    folder: Set<Long>,
    seen: Map<Long, Long>,
): Map<Long, List<TdApi.Notification>> = groups
    .filter { it.chatId in folder && (it.type is TdApi.NotificationGroupTypeMessages || it.type is TdApi.NotificationGroupTypeMentions) }
    .groupBy { it.chatId }
    .mapValues { (chatId, chatGroups) ->
        chatGroups.flatMap { it.notifications.toList() }
            .filter { (it.messageId ?: 0) > (seen[chatId] ?: 0) }
            .distinctBy { it.messageId }
            .sortedBy { it.id }
    }
    .filterValues { it.isNotEmpty() }

/** The message a notification is about, or null for other kinds (calls, secret chats). */
internal val TdApi.Notification.messageId: Long?
    get() = when (val type = type) {
        is TdApi.NotificationTypeNewMessage -> type.message.id
        is TdApi.NotificationTypeNewPushMessage -> type.messageId
        else -> null
    }
