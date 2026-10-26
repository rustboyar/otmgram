package dev.otmgram.data

import dev.otmgram.core.td.TelegramClient
import org.drinkless.tdlib.TdApi

// How people and chats are named across the app. No cache of its own: TDLib keeps users and
// chats in memory, and names change.

/** "Anna Smith", or the part of it there is. */
fun TdApi.User.fullName(): String = listOf(firstName, lastName).filter(String::isNotEmpty).joinToString(" ")

/**
 * What a message's sender is called: a user's full name, or only the first with [firstOnly]
 * (in previews and notifications); a chat's title. Null for a kind of sender the app doesn't
 * know. Throws [dev.otmgram.core.td.TdException] if TDLib can't tell.
 */
suspend fun TelegramClient.senderName(sender: TdApi.MessageSender, firstOnly: Boolean = false): String? = when (sender) {
    is TdApi.MessageSenderUser -> send(TdApi.GetUser(sender.userId)).let { if (firstOnly) it.firstName else it.fullName() }
    is TdApi.MessageSenderChat -> send(TdApi.GetChat(sender.chatId)).title
    else -> null
}
