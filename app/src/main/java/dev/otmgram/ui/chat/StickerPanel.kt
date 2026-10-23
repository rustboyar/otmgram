package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The sticker picker: its tabs, the chosen one, and that tab's stickers. */
class StickerPanel(
    private val scope: CoroutineScope,
    private val loadTabs: suspend () -> List<StickerTab>,
    private val loadStickers: suspend (StickerTab) -> List<Sticker>,
    private val onError: (Exception) -> Unit,
) {
    /** Tabs of the picker, or null while it's closed. */
    var tabs by mutableStateOf<List<StickerTab>?>(null)
        private set

    var selected by mutableStateOf<StickerTab?>(null)
        private set

    /** Stickers of [selected], or null while loading. */
    var stickers by mutableStateOf<List<Sticker>?>(null)
        private set

    val isOpen: Boolean get() = tabs != null

    private var loading: Job? = null

    /** Opens the picker on its first tab, or closes it. */
    fun toggle() {
        if (isOpen) {
            close()
            return
        }
        scope.launch {
            guarded {
                val loaded = loadTabs()
                tabs = loaded
                loaded.firstOrNull()?.let(::select)
            }
        }
    }

    fun select(tab: StickerTab) {
        selected = tab
        stickers = null
        loading?.cancel()
        loading = scope.launch { guarded { stickers = loadStickers(tab) } }
    }

    fun close() {
        tabs = null
        loading?.cancel()
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }
}
