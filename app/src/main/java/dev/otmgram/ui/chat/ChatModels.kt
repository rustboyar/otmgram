package dev.otmgram.ui.chat

import androidx.compose.runtime.Immutable
import dev.otmgram.ui.ProfileRoute

data class ChatHeader(
    val title: String,
    val isGroup: Boolean,
    /** "3 members", "last seen recently"; null if there's nothing to say. */
    val subtitle: String?,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
    val isSavedMessages: Boolean,
    /** The person's or group's profile, opened from the title; null for Saved Messages. */
    val profile: ProfileRoute? = null,
)

/** Where the unread messages began when the chat was opened. */
data class UnreadMark(val afterMessageId: Long, val count: Int)

enum class MessageStatus { Sending, Sent, Read, Failed }

data class ReplyPreview(val senderName: String?, val text: String, val messageId: Long? = null, val thumbnail: QuoteThumbnail? = null)

/** A reaction under a message. [emoji] is null for kinds Otmgram can't add (custom emoji, paid). */
data class ReactionChip(
    val label: String,
    val emoji: String?,
    val count: Int,
    val isChosen: Boolean,
    /** Who reacted, when they're all known and few: shown instead of the count. */
    val reactors: List<MessageSender> = emptyList(),
)

/** Someone who reacted to a message, and with what. */
data class Reactor(val sender: MessageSender, val emoji: String)

data class SearchResult(val messageId: Long, val senderName: String?, val text: String, val date: Int)

// Never changed once made: each update makes new ones. Marked so, Compose compares them and
// skips the bubbles (and their callbacks) whose message is the same; without it, any update
// to the history recomposed every bubble on screen.
@Immutable
data class MessageItem(
    val id: Long,
    val isOutgoing: Boolean,
    /** An event like "group created" rather than a message. */
    val isService: Boolean,
    /** Shown above the first of consecutive incoming group messages from the same sender. */
    val senderName: String?,
    val forwardedFrom: String?,
    val replyTo: ReplyPreview?,
    /** Message text, or the caption of [media]. */
    val text: String,
    val media: MessageMedia?,
    /** True if [text] describes content that can't be shown, like a document. */
    val isMediaPlaceholder: Boolean,
    /** Unix time. */
    val date: Int,
    val isEdited: Boolean,
    /** Null for incoming messages. */
    val status: MessageStatus?,
    val reactions: List<ReactionChip>,
    /** Links and formatting in [text]. */
    val spans: List<TextSpan> = emptyList(),
    /** A YouTube, Instagram or TikTok link in [text], played in the app. */
    val link: LinkCard? = null,
    /** Shared by the messages of an album, sent together; 0 for none. */
    val albumId: Long = 0,
    /** Who sent an incoming group message, whether or not its name shows; null otherwise. */
    val sender: MessageSender? = null,
    /** The newest of consecutive messages from [sender]: its avatar shows beside it. */
    val showAvatar: Boolean = false,
)

/** The sender of a group message: a person, or a chat posting as itself. */
@Immutable
data class MessageSender(val id: Long, val name: String, val photoFileId: Int?, val minithumbnail: ByteArray?)

/** What the input bar does with the next send. */
sealed interface Composer {
    data object New : Composer
    data class Reply(val message: MessageItem) : Composer
    data class Edit(val message: MessageItem) : Composer
}

/** The menu of a message, opened by a tap, with the actions it allows. */
data class MessageMenu(
    val message: MessageItem,
    /** What forwarding, selecting and deleting act on: [message], or all of its album. */
    val messageIds: List<Long> = listOf(message.id),
    /** Emoji reactions that can be added. */
    val reactions: List<String>,
    val canReply: Boolean,
    val canForward: Boolean,
    val canEdit: Boolean,
    val canDeleteForSelf: Boolean,
    val canDeleteForEveryone: Boolean,
    /** Files can be saved to the device's Downloads folder. */
    val canSave: Boolean,
    val canCopy: Boolean,
    val canPin: Boolean,
    val isPinned: Boolean,
    /** Who reacted, with what; loaded after the menu opens. */
    val reactedBy: List<Reactor> = emptyList(),
)

/** Messages waiting for a delete confirmation, with what the user may choose. */
data class DeleteRequest(
    val messageIds: List<Long>,
    /** Text of a single message, quoted in the dialog. */
    val quote: String?,
    val canDeleteForSelf: Boolean,
    val canDeleteForEveryone: Boolean,
)

data class ForwardTarget(
    val chatId: Long,
    val title: String,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
    val isSavedMessages: Boolean,
    /** The person's or group's profile, opened from the title; null for Saved Messages. */
    val profile: ProfileRoute? = null,
)
