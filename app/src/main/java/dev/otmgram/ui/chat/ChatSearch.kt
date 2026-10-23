package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Find in chat: the query, its matches (newest first) and the one shown. Searches shortly
 * after typing stops, and steps through matches with [show].
 */
class ChatSearch(
    scope: CoroutineScope,
    private val search: suspend (query: String) -> List<SearchResult>,
    private val show: (messageId: Long) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    /** The search text, or null when not searching. */
    var query by mutableStateOf<String?>(null)
        private set

    /** Matches of [query], newest first; null while searching or not searching. */
    var results by mutableStateOf<List<SearchResult>?>(null)
        private set

    /** Which of [results] is shown. */
    var index by mutableIntStateOf(0)
        private set

    val isOpen: Boolean get() = query != null

    private val queries = MutableStateFlow("")

    init {
        scope.launch {
            queries.collectLatest { text ->
                results = null
                if (text.isBlank()) return@collectLatest
                delay(DELAY_MS)
                try {
                    val found = search(text.trim())
                    results = found
                    index = 0
                    found.firstOrNull()?.let { show(it.messageId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    fun open() {
        query = ""
    }

    fun type(text: String) {
        query = text
        queries.value = text
    }

    fun close() {
        query = null
        results = null
        queries.value = ""
    }

    /** Steps to an older (+1) or newer (-1) match. */
    fun step(direction: Int) {
        val found = results ?: return
        val next = (index + direction).coerceIn(0, found.lastIndex)
        if (next == index) return
        index = next
        show(found[next].messageId)
    }

    companion object {
        /** Waits this long after the last keystroke before searching. */
        const val DELAY_MS = 300L
    }
}
