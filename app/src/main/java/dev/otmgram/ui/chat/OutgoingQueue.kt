package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.otmgram.data.SendQuality
import dev.otmgram.data.SizeEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Picked photos, videos and files waiting in the send preview, and sending them: several
 * go as one album, captioned on the first. [T] is what the picker returns (a Uri).
 *
 * Preparing and sending run in [sendScope], which should outlive the screen: leaving the
 * chat while media is being compressed must still send it.
 */
class OutgoingQueue<T>(
    private val scope: CoroutineScope,
    private val sendScope: CoroutineScope,
    private val estimate: suspend (List<T>) -> SizeEstimate,
    private val prepareMedia: suspend (item: T, caption: String, quality: SendQuality) -> TdApi.InputMessageContent?,
    private val prepareFile: suspend (item: T, caption: String) -> TdApi.InputMessageContent,
    private val send: suspend (List<TdApi.InputMessageContent>) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    /** Photos and videos waiting for a caption. */
    var media by mutableStateOf<List<T>>(emptyList())
        private set

    /** Size of [media] as it is and in each quality, once estimated. */
    var sizes by mutableStateOf<SizeEstimate?>(null)
        private set

    /** Files waiting for a caption; sent as they are. */
    var files by mutableStateOf<List<T>>(emptyList())
        private set

    /** True while picked media is being compressed for sending. */
    var preparing by mutableStateOf(false)
        private set

    fun pickMedia(items: List<T>) {
        media = items
        sizes = null
        if (items.isNotEmpty()) scope.launch { guarded { sizes = estimate(items) } }
    }

    fun pickFiles(items: List<T>) {
        files = items
    }

    fun sendMedia(caption: String, quality: SendQuality) {
        val items = media
        media = emptyList()
        preparing = true
        sendScope.launch {
            try {
                guarded {
                    send(items.mapIndexedNotNull { i, item -> prepareMedia(item, if (i == 0) caption else "", quality) })
                }
            } finally {
                preparing = false
            }
        }
    }

    fun sendFiles(caption: String) {
        val items = files
        files = emptyList()
        sendScope.launch { guarded { send(items.mapIndexed { i, item -> prepareFile(item, if (i == 0) caption else "") }) } }
    }

    fun cancelMedia() {
        media = emptyList()
    }

    fun cancelFiles() {
        files = emptyList()
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
