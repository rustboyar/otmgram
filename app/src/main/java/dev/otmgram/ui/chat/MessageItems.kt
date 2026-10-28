package dev.otmgram.ui.chat

import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.data.ChatHistory
import dev.otmgram.data.eventNote
import dev.otmgram.data.isService
import dev.otmgram.data.summary
import dev.otmgram.ui.format.localDay
import org.drinkless.tdlib.TdApi
import java.time.ZoneId
import java.util.IdentityHashMap

/** Text messages, and media that can carry a caption. */
internal val MessageItem.hasEditableText: Boolean
    get() = when (media) {
        null -> !isMediaPlaceholder && !isService
        is MessageMedia.Photo, is MessageMedia.Video, is MessageMedia.Animation, is MessageMedia.VoiceNote -> true
        else -> false
    }

/**
 * What a message's content makes of it: its media, text, formatting and link card. Kept per
 * content object across updates ([ItemCache]): TDLib gives a message a new content object
 * when it changes, so the same object means the same parts.
 */
internal class ContentParts(val media: MessageMedia?, val text: String?, val spans: List<TextSpan>, val link: LinkCard?)

/**
 * The parts worked out on the last pass, by content object: a new message or reaction then
 * costs that message's parts, not every loaded message's. Only those still loaded are kept.
 */
internal class ItemCache {
    private var parts = IdentityHashMap<TdApi.MessageContent, ContentParts>()
    private var next = IdentityHashMap<TdApi.MessageContent, ContentParts>()

    fun begin() {
        next = IdentityHashMap()
    }

    fun get(content: TdApi.MessageContent, make: () -> ContentParts): ContentParts = (parts[content] ?: make()).also { next[content] = it }

    fun end() {
        parts = next
    }
}

internal fun ChatHistory.toItems(isGroup: Boolean, strings: Strings, cache: ItemCache = ItemCache()): List<MessageItem> {
    val byId = messages.associateBy { it.id }
    // Each message's day, once: it's asked about up to three times per message.
    val zone = ZoneId.systemDefault()
    val days = messages.map { localDay(it.date, zone) }
    cache.begin()
    return messages.mapIndexed { index, message ->
        val parts = cache.get(message.content) {
            val media = message.toMedia()
            ContentParts(
                media = media,
                text = when {
                    message.isService -> null // Names who did it: not by content alone.
                    media != null -> message.content.caption()
                    else -> message.summary(strings)
                },
                // Only where the text is the message's own; a summary has no formatting.
                spans = if (media != null || message.content is TdApi.MessageText) message.content.formattedText()?.spans().orEmpty() else emptyList(),
                link = if (media == null) (message.content as? TdApi.MessageText)?.linkCard() else null,
            )
        }
        val media = parts.media.let { media ->
            // A contact's face, if they're on Telegram and it's loaded.
            if (media is MessageMedia.Contact) senderPhotos[media.userId]?.let { media.copy(photoFileId = it.fileId, minithumbnail = it.minithumbnail) } ?: media else media
        }
        val fromOthers = isGroup && !message.isOutgoing && !message.isService

        // A run of messages from one sender ends at a day's line too.
        fun sameSender(other: Int): Boolean {
            val o = messages.getOrNull(other) ?: return false
            return !o.isOutgoing && !o.isService && o.senderKey() == message.senderKey() && days[other] == days[index]
        }
        val showSender = fromOthers && !sameSender(index + 1)
        val key = message.senderKey()
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
                    replied != null -> ReplyPreview(senderNames[replied.senderKey()], replied.summary(strings), here, replied.content.quoteThumbnail())
                    replyTo.content != null -> ReplyPreview(replyTo.origin?.let(::originName), replyTo.content.summary(strings), here, replyTo.content.quoteThumbnail())
                    else -> ReplyPreview(null, strings.get(R.string.reply_deleted))
                }
            },
            text = parts.text ?: message.eventNote(strings) { senderNames[it] },
            media = media,
            isMediaPlaceholder = media == null && message.content !is TdApi.MessageText && !message.isService,
            spans = parts.spans,
            link = parts.link,
            albumId = message.mediaAlbumId,
            sender = if (fromOthers) {
                senderPhotos[key].let { photo -> MessageSender(key, senderNames[key].orEmpty(), photo?.fileId, photo?.minithumbnail) }
            } else {
                null
            },
            showAvatar = fromOthers && !sameSender(index - 1),
            date = message.date,
            isEdited = message.editDate > 0,
            status = if (!message.isOutgoing) null else when {
                message.sendingState is TdApi.MessageSendingStateFailed -> MessageStatus.Failed
                message.sendingState is TdApi.MessageSendingStatePending -> MessageStatus.Sending
                message.id <= lastReadOutboxMessageId -> MessageStatus.Read
                else -> MessageStatus.Sent
            },
            reactions = message.interactionInfo?.reactions?.reactions.orEmpty().map { reaction ->
                // Faces instead of a number, as in Telegram: when all who reacted are known, and few.
                val reactors = if (reaction.totalCount <= MAX_REACTOR_FACES && reaction.recentSenderIds.size == reaction.totalCount) {
                    reaction.recentSenderIds.map { sender ->
                        val key = sender.key()
                        senderPhotos[key].let { MessageSender(key, senderNames[key].orEmpty(), it?.fileId, it?.minithumbnail) }
                    }
                } else {
                    emptyList()
                }
                when (val type = reaction.type) {
                    is TdApi.ReactionTypeEmoji -> ReactionChip(type.emoji, type.emoji, reaction.totalCount, reaction.isChosen, reactors)
                    is TdApi.ReactionTypePaid -> ReactionChip("⭐", null, reaction.totalCount, reaction.isChosen)
                    else -> ReactionChip("✨", null, reaction.totalCount, reaction.isChosen, reactors)
                }
            },
        )
    }.also { cache.end() }
}

private fun ChatHistory.originName(origin: TdApi.MessageOrigin): String? = when (origin) {
    is TdApi.MessageOriginUser -> senderNames[origin.senderUserId]
    is TdApi.MessageOriginHiddenUser -> origin.senderName
    is TdApi.MessageOriginChat -> senderNames[origin.senderChatId]
    is TdApi.MessageOriginChannel -> senderNames[origin.chatId]
    else -> null
}

/** At most this many who reacted show as faces; more, as a count. */
private const val MAX_REACTOR_FACES = 3

internal fun TdApi.Message.senderKey(): Long = senderId.key()

/** The user or chat id of a sender, as names and photos are kept by. */
internal fun TdApi.MessageSender.key(): Long = when (val sender = this) {
    is TdApi.MessageSenderUser -> sender.userId
    is TdApi.MessageSenderChat -> sender.chatId
    else -> 0
}
