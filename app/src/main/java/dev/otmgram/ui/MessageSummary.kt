package dev.otmgram.ui

import org.drinkless.tdlib.TdApi

/** Events like "group created", shown as a note instead of a message bubble. */
val TdApi.Message.isService: Boolean
    get() = when (content) {
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate,
        is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink, is TdApi.MessageChatDeleteMember,
        is TdApi.MessageChatChangeTitle, is TdApi.MessageChatChangePhoto,
        is TdApi.MessagePinMessage, is TdApi.MessageContactRegistered -> true
        else -> false
    }

/** One-line description of a message: its text, or the kind of media with caption. */
fun TdApi.Message.summary(): String = content.summary()

fun TdApi.MessageContent.summary(): String =
    when (val content = this) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> withCaption("Photo", content.caption)
        is TdApi.MessageVideo -> withCaption("Video", content.caption)
        is TdApi.MessageVoiceNote -> withCaption("Voice message", content.caption)
        is TdApi.MessageVideoNote -> "Video message"
        is TdApi.MessageSticker -> "${content.sticker.emoji} Sticker".trim()
        is TdApi.MessageAnimation -> withCaption("GIF", content.caption)
        is TdApi.MessageDocument -> withCaption(content.document.fileName.ifEmpty { "File" }, content.caption)
        is TdApi.MessageAudio -> withCaption("Audio", content.caption)
        is TdApi.MessageCall -> "Call"
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> "Group created"
        is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink -> "Joined the group"
        is TdApi.MessageChatDeleteMember -> "Left the group"
        is TdApi.MessageChatChangeTitle -> "Group renamed to \"${content.title}\""
        is TdApi.MessageChatChangePhoto -> "Group photo changed"
        is TdApi.MessagePinMessage -> "Pinned a message"
        is TdApi.MessageContactRegistered -> "Joined Telegram"
        else -> "Message"
    }

private fun withCaption(kind: String, caption: TdApi.FormattedText) =
    if (caption.text.isEmpty()) kind else "$kind, ${caption.text}"
