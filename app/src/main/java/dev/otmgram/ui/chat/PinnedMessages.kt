package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The chat's pinned messages, newest first, for the bar above the messages and its list. A
 * tap on the bar shows the message it names, then the bar moves on to the next older one, as
 * in Telegram. Loaded at once, and again on each of [changes].
 */
class PinnedMessages(
    scope: CoroutineScope,
    private val load: suspend () -> List<SearchResult>,
    changes: Flow<Unit>,
    private val show: (messageId: Long) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    var messages by mutableStateOf<List<SearchResult>>(emptyList())
        private set

    /** Which of [messages] the bar shows: the next one a tap goes to. */
    var shown by mutableIntStateOf(0)
        private set

    var listOpen by mutableStateOf(false)
        private set

    init {
        scope.launch {
            reload()
            changes.collect { reload() }
        }
    }

    private suspend fun reload() {
        try {
            messages = load()
            shown = shown.coerceIn(0, (messages.size - 1).coerceAtLeast(0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    /** A tap on the bar. */
    fun showNext() {
        val message = messages.getOrNull(shown) ?: return
        show(message.messageId)
        shown = (shown + 1) % messages.size
    }

    fun openList() {
        listOpen = true
    }

    fun closeList() {
        listOpen = false
    }

    /** One picked from the list: the bar then names the next older one. */
    fun goTo(messageId: Long) {
        listOpen = false
        show(messageId)
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index >= 0) shown = (index + 1) % messages.size
    }
}
