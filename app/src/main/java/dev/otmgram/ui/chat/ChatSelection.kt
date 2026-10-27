package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.drinkless.tdlib.TdApi

/** Selecting several messages to copy, forward or delete. */
class ChatSelection(
    private val properties: suspend (messageId: Long) -> TdApi.MessageProperties,
    private val messages: () -> List<MessageItem>,
) {
    /** Selected message ids; selection mode is on while it isn't empty. */
    var selected by mutableStateOf<Set<Long>>(emptySet())
        private set

    val isActive: Boolean get() = selected.isNotEmpty()

    /** Starts selecting, with [message] selected. */
    fun start(message: MessageItem) = start(listOf(message.id))

    /** Starts selecting, with [messageIds] (a message, or an album's) selected. */
    fun start(messageIds: List<Long>) {
        selected = messageIds.toSet()
    }

    fun toggle(message: MessageItem) = toggle(listOf(message))

    /** Selects [messages] (an album's) together, or unselects them if they all are. */
    fun toggle(messages: List<MessageItem>) {
        val ids = messages.filterNot { it.isService }.map { it.id }
        selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
    }

    fun clear() {
        selected = emptySet()
    }

    /** Text of the selected messages, oldest first, for the clipboard. */
    fun text(): String {
        val byId = messages().associateBy { it.id }
        return selected.sorted().mapNotNull { byId[it] }
            .filter { it.text.isNotEmpty() && !it.isMediaPlaceholder }
            .joinToString("\n\n") { message -> listOfNotNull((message.sender?.name ?: message.senderName)?.takeIf { it.isNotEmpty() }?.let { "$it:" }, message.text).joinToString(" ") }
    }

    /**
     * What deleting the selection would do: only messages that may be deleted, and "for
     * everyone" only if all of them allow it. Null if none may be deleted.
     */
    suspend fun deleteRequest(): DeleteRequest? {
        val ids = selected.toList()
        val allowed = ids.map { properties(it) }
        val deletable = ids.filterIndexed { i, _ -> allowed[i].canBeDeletedOnlyForSelf || allowed[i].canBeDeletedForAllUsers }
        if (deletable.isEmpty()) return null
        val ofDeletable = allowed.filter { it.canBeDeletedOnlyForSelf || it.canBeDeletedForAllUsers }
        return DeleteRequest(
            messageIds = deletable,
            quote = null,
            canDeleteForSelf = ofDeletable.any { it.canBeDeletedOnlyForSelf },
            canDeleteForEveryone = ofDeletable.all { it.canBeDeletedForAllUsers },
        )
    }
}
