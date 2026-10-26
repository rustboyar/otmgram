package dev.otmgram.ui.chat

import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.data.ChatHistory
import dev.otmgram.data.isService
import dev.otmgram.data.summary
import org.drinkless.tdlib.TdApi

/** Text messages, and media that can carry a caption. */
internal val MessageItem.hasEditableText: Boolean
    get() = when (media) {
        null -> !isMediaPlaceholder && !isService
        is MessageMedia.Photo, is MessageMedia.Video, is MessageMedia.Animation, is MessageMedia.VoiceNote -> true
        else -> false
    }

internal fun ChatHistory.toItems(isGroup: Boolean, strings: Strings): List<MessageItem> {
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
                // A reply to a message of another chat can't be jumped to from here.
                val here = replyTo.messageId.takeIf { replyTo.chatId == message.chatId }
                when {
                    replied != null -> ReplyPreview(senderNames[replied.senderKey()], replied.summary(strings), here)
                    replyTo.content != null -> ReplyPreview(replyTo.origin?.let(::originName), replyTo.content.summary(strings), here)
                    else -> ReplyPreview(null, strings.get(R.string.reply_deleted))
                }
            },
            text = if (media != null) message.content.caption() else message.summary(strings),
            media = media,
            isMediaPlaceholder = media == null && message.content !is TdApi.MessageText && !message.isService,
            // Only where the text is the message's own; a summary has no formatting.
            spans = if (media != null || message.content is TdApi.MessageText) message.content.formattedText()?.spans().orEmpty() else emptyList(),
            link = if (media == null) (message.content as? TdApi.MessageText)?.linkCard() else null,
            albumId = message.mediaAlbumId,
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
