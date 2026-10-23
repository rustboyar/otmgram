package dev.otmgram.ui

import dev.otmgram.R
import dev.otmgram.core.Strings
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
