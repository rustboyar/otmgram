package dev.otmgram.testing

import org.drinkless.tdlib.TdApi

/** Small builders for the TDLib objects tests need; only the fields the app reads are set. */

const val CHAT_ID = 100L
const val ME = 1L

fun text(value: String) = TdApi.MessageText().apply { text = TdApi.FormattedText(value, emptyArray()) }

fun message(
    id: Long,
    content: TdApi.MessageContent = text("message $id"),
    from: Long = 2,
    outgoing: Boolean = false,
    date: Int = NOON_2026_10_23,
    chatId: Long = CHAT_ID,
    sending: TdApi.MessageSendingState? = null,
    replyTo: Long? = null,
    reactions: List<TdApi.MessageReaction> = emptyList(),
) = TdApi.Message().apply {
    this.id = id
    this.chatId = chatId
    senderId = TdApi.MessageSenderUser(from)
    isOutgoing = outgoing
    this.date = date
    this.content = content
    sendingState = sending
    this.replyTo = replyTo?.let { TdApi.MessageReplyToMessage().apply { this.chatId = chatId; messageId = it } }
    if (reactions.isNotEmpty()) {
        interactionInfo = TdApi.MessageInteractionInfo().apply {
            this.reactions = TdApi.MessageReactions().apply { this.reactions = reactions.toTypedArray() }
        }
    }
}

fun user(id: Long, first: String, last: String = "") = TdApi.User().apply {
    this.id = id
    firstName = first
    lastName = last
    type = TdApi.UserTypeRegular()
}

fun chat(
    id: Long,
    title: String,
    type: TdApi.ChatType = TdApi.ChatTypePrivate(id),
    lastMessage: TdApi.Message? = null,
    muteFor: Int? = null,
    unread: Int = 0,
) = TdApi.Chat().apply {
    this.id = id
    this.title = title
    this.type = type
    this.lastMessage = lastMessage
    unreadCount = unread
    notificationSettings = TdApi.ChatNotificationSettings().apply {
        useDefaultMuteFor = muteFor == null
        this.muteFor = muteFor ?: 0
    }
}

fun messages(vararg items: TdApi.Message) = TdApi.Messages(items.size, arrayOf(*items))

fun file(id: Int, size: Long = 1000) = TdApi.File().apply {
    this.id = id
    this.size = size
    local = TdApi.LocalFile()
    remote = TdApi.RemoteFile()
}

/** 2026-10-23 12:00 UTC: noon, so the date is the same in every time zone that matters here. */
const val NOON_2026_10_23 = 1_792_756_800
const val DAY = 86_400
