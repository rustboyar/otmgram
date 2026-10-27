package dev.otmgram.ui.chat

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a chat was scrolled to: the message at the bottom of the screen, and [offset] px
 * of it scrolled past the bottom (or, negative, how far above the bottom it sits).
 */
data class ChatPosition(val messageId: Long, val offset: Int)

/**
 * Each chat's place in its history, while the app runs: a chat left mid-history opens
 * there again, rather than at the newest messages. Not kept for chats left at the newest.
 */
@Singleton
class ChatPositions @Inject constructor() {
    private val positions = ConcurrentHashMap<Long, ChatPosition>()

    operator fun get(chatId: Long): ChatPosition? = positions[chatId]

    operator fun set(chatId: Long, position: ChatPosition?) {
        if (position == null) positions.remove(chatId) else positions[chatId] = position
    }
}
