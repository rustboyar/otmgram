package dev.otmgram.data

import dev.otmgram.R
import dev.otmgram.core.Strings
import org.drinkless.tdlib.TdApi

/** Events like "group created", shown as a note instead of a message bubble. */
val TdApi.Message.isService: Boolean
    get() = when (content) {
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate,
        is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink, is TdApi.MessageChatDeleteMember,
        is TdApi.MessageChatChangeTitle, is TdApi.MessageChatChangePhoto,
        is TdApi.MessagePinMessage, is TdApi.MessageContactRegistered,
        -> true
        else -> false
    }

/** One-line description of a message: its text, or the kind of media with caption. */
fun TdApi.Message.summary(strings: Strings): String = content.summary(strings)

fun TdApi.MessageContent.summary(strings: Strings): String {
    fun kind(id: Int) = strings.get(id)
    fun withCaption(kind: String, caption: TdApi.FormattedText) =
        if (caption.text.isEmpty()) kind else strings.get(R.string.summary_with_caption, kind, caption.text)
    return when (val content = this) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> withCaption(kind(R.string.summary_photo), content.caption)
        is TdApi.MessageVideo -> withCaption(kind(R.string.summary_video), content.caption)
        is TdApi.MessageVoiceNote -> withCaption(kind(R.string.summary_voice), content.caption)
        is TdApi.MessageVideoNote -> kind(R.string.summary_video_note)
        is TdApi.MessageSticker -> listOf(content.sticker.emoji, kind(R.string.summary_sticker)).filter { it.isNotEmpty() }.joinToString(" ")
        is TdApi.MessageAnimation -> withCaption(kind(R.string.summary_gif), content.caption)
        is TdApi.MessageDocument -> withCaption(content.document.fileName.ifEmpty { kind(R.string.summary_file) }, content.caption)
        is TdApi.MessageAudio -> withCaption(kind(R.string.summary_audio), content.caption)
        is TdApi.MessageLocation -> kind(R.string.summary_location)
        is TdApi.MessageLiveLocation -> kind(R.string.summary_live_location)
        is TdApi.MessageCall -> kind(R.string.summary_call)
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> kind(R.string.summary_group_created)
        is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink -> kind(R.string.summary_joined_group)
        is TdApi.MessageChatDeleteMember -> kind(R.string.summary_left_group)
        is TdApi.MessageChatChangeTitle -> strings.get(R.string.summary_group_renamed, content.title)
        is TdApi.MessageChatChangePhoto -> kind(R.string.summary_group_photo)
        is TdApi.MessagePinMessage -> kind(R.string.summary_pinned)
        is TdApi.MessageContactRegistered -> kind(R.string.summary_joined_telegram)
        else -> kind(R.string.summary_message)
    }
}

/**
 * An event as the chat shows it, saying who: "Anna added Lea and Tom", "You left the group".
 * [name] gives a user's or chat's name, null if not known (yet); then it's the [summary].
 */
fun TdApi.Message.eventNote(strings: Strings, name: (Long) -> String?): String {
    val actor = when {
        isOutgoing -> strings.get(R.string.event_you)
        else -> senderId.id()?.let(name)
    } ?: return summary(strings)
    fun names(ids: List<Long>): String? {
        val known = ids.map { name(it) ?: return null }
        return if (known.size < 2) known.firstOrNull() else strings.get(R.string.names_and, known.dropLast(1).joinToString(", "), known.last())
    }
    return when (val content = content) {
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> strings.get(R.string.event_created, actor)
        is TdApi.MessageChatAddMembers -> {
            val added = content.memberUserIds.toList()
            if (added == listOfNotNull(senderId.id())) {
                strings.get(R.string.event_joined, actor)
            } else {
                names(added)?.let { strings.get(R.string.event_added, actor, it) } ?: summary(strings)
            }
        }
        is TdApi.MessageChatJoinByLink -> strings.get(R.string.event_joined_by_link, actor)
        is TdApi.MessageChatDeleteMember -> if (content.userId == senderId.id()) {
            strings.get(R.string.event_left, actor)
        } else {
            names(listOf(content.userId))?.let { strings.get(R.string.event_removed, actor, it) } ?: summary(strings)
        }
        is TdApi.MessageChatChangeTitle -> strings.get(R.string.event_renamed, actor, content.title)
        is TdApi.MessageChatChangePhoto -> strings.get(R.string.event_photo, actor)
        is TdApi.MessagePinMessage -> strings.get(R.string.event_pinned, actor)
        is TdApi.MessageContactRegistered -> strings.get(R.string.event_joined_telegram, actor)
        else -> summary(strings)
    }
}

/** The user or chat id of a sender. */
private fun TdApi.MessageSender.id(): Long? = when (this) {
    is TdApi.MessageSenderUser -> userId
    is TdApi.MessageSenderChat -> chatId
    else -> null
}

/** What a message from a push notification is, when TDLib knows only that much yet. */
fun TdApi.PushMessageContent.summary(strings: Strings): String {
    fun kind(id: Int) = strings.get(id)
    fun withCaption(kind: String, caption: String) = if (caption.isEmpty()) kind else strings.get(R.string.summary_with_caption, kind, caption)
    return when (val content = this) {
        is TdApi.PushMessageContentText -> content.text
        is TdApi.PushMessageContentPhoto -> withCaption(kind(R.string.summary_photo), content.caption)
        is TdApi.PushMessageContentVideo -> withCaption(kind(R.string.summary_video), content.caption)
        is TdApi.PushMessageContentVoiceNote -> kind(R.string.summary_voice)
        is TdApi.PushMessageContentVideoNote -> kind(R.string.summary_video_note)
        is TdApi.PushMessageContentSticker -> listOf(content.emoji, kind(R.string.summary_sticker)).filter { it.isNotEmpty() }.joinToString(" ")
        is TdApi.PushMessageContentAnimation -> withCaption(kind(R.string.summary_gif), content.caption)
        is TdApi.PushMessageContentDocument -> content.document?.fileName?.takeIf { it.isNotEmpty() } ?: kind(R.string.summary_file)
        is TdApi.PushMessageContentAudio -> kind(R.string.summary_audio)
        is TdApi.PushMessageContentLocation -> kind(if (content.isLive) R.string.summary_live_location else R.string.summary_location)
        else -> kind(R.string.summary_message)
    }
}
