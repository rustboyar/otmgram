package dev.otmgram.ui.chat

import dev.otmgram.data.ChatHistory
import dev.otmgram.ui.isService
import dev.otmgram.ui.summary
import org.drinkless.tdlib.TdApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Text messages, and media that can carry a caption. */
internal val MessageItem.hasEditableText: Boolean
    get() = when (media) {
        null -> !isMediaPlaceholder && !isService
        is MessageMedia.Photo, is MessageMedia.Video, is MessageMedia.Animation, is MessageMedia.VoiceNote -> true
        else -> false
    }

internal fun ChatHistory.toItems(isGroup: Boolean): List<MessageItem> {
    val byId = messages.associateBy { it.id }
    return messages.mapIndexed { index, message ->
        val media = message.toMedia()
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
            text = if (media != null) message.content.caption() else message.summary(),
            media = media,
            isMediaPlaceholder = media == null && message.content !is TdApi.MessageText && !message.isService,
            date = message.date,
            isEdited = message.editDate > 0,
            status = if (!message.isOutgoing) null else when {
                message.sendingState is TdApi.MessageSendingStateFailed -> MessageStatus.Failed
                message.sendingState is TdApi.MessageSendingStatePending -> MessageStatus.Sending
                message.id <= lastReadOutboxMessageId -> MessageStatus.Read
                else -> MessageStatus.Sent
            },
            reactions = message.interactionInfo?.reactions?.reactions.orEmpty().map { reaction ->
                when (val type = reaction.type) {
                    is TdApi.ReactionTypeEmoji -> ReactionChip(type.emoji, type.emoji, reaction.totalCount, reaction.isChosen)
                    is TdApi.ReactionTypePaid -> ReactionChip("⭐", null, reaction.totalCount, reaction.isChosen)
                    else -> ReactionChip("✨", null, reaction.totalCount, reaction.isChosen)
                }
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

internal fun TdApi.Message.senderKey(): Long = when (val sender = senderId) {
    is TdApi.MessageSenderUser -> sender.userId
    is TdApi.MessageSenderChat -> sender.chatId
    else -> 0
}

/** "online", "last seen at 19:01", "last seen recently". */
internal fun lastSeen(status: TdApi.UserStatus?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? = when (status) {
    is TdApi.UserStatusOnline -> "online"
    is TdApi.UserStatusOffline -> {
        val time = Instant.ofEpochSecond(status.wasOnline.toLong()).atZone(zone)
        if (time.toLocalDate() == today) "last seen at ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
        else "last seen ${time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
    }
    is TdApi.UserStatusRecently -> "last seen recently"
    is TdApi.UserStatusLastWeek -> "last seen within a week"
    is TdApi.UserStatusLastMonth -> "last seen within a month"
    else -> null
}
