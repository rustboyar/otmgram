package dev.otmgram.data

import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads of TDLib files: which download when, see PLAN.md. */
@Singleton
class FileRepository @Inject constructor(
    private val td: TelegramClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Who waits for which file; one collector routes file updates to them. */
    private val listeners = ConcurrentHashMap<Int, MutableSet<(TdApi.File) -> Unit>>()

    init {
        // One pass over all updates, off the main thread, instead of one per visible file.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                if (update is TdApi.UpdateFile) listeners[update.file.id]?.forEach { it(update.file) }
            }
        }
    }

    /**
     * Starts downloading [fileId] (if not done yet) and emits its state until collection
     * stops. Higher [priority] (1..32) downloads first.
     */
    fun download(fileId: Int, priority: Int = PRIORITY_VISIBLE): Flow<TdApi.File> =
        state(fileId) { td.send(TdApi.DownloadFile(fileId, priority, 0, 0, false)) }

    /** Emits the state of [fileId] without downloading it, e.g. files downloaded on demand. */
    fun observe(fileId: Int): Flow<TdApi.File> = state(fileId) { td.send(TdApi.GetFile(fileId)) }

    /** Starts downloading in the background; progress shows up in [observe]. */
    suspend fun startDownload(fileId: Int) {
        td.send(TdApi.DownloadFile(fileId, PRIORITY_VIEWER, 0, 0, false))
    }

    /** Stops a download started with [startDownload]; the part already loaded is kept. */
    suspend fun cancelDownload(fileId: Int) {
        td.send(TdApi.CancelDownloadFile(fileId, false))
    }

    /** Downloads [fileId] completely and returns it. */
    suspend fun downloadNow(fileId: Int): TdApi.File =
        td.send(TdApi.DownloadFile(fileId, PRIORITY_VIEWER, 0, 0, true))

    private fun state(fileId: Int, initial: suspend () -> TdApi.File): Flow<TdApi.File> = channelFlow {
        var completed = false
        val emit: suspend (TdApi.File) -> Unit = { file ->
            // The request's result may arrive after newer updates; never go back from completed.
            if (!completed || file.local.isDownloadingCompleted) {
                completed = file.local.isDownloadingCompleted
                send(file)
            }
        }
        // Listening starts before the request, so no update can slip in between; only the
        // latest progress matters, and the last one (completion) is always kept.
        val latest = Channel<TdApi.File>(Channel.CONFLATED)
        val listener: (TdApi.File) -> Unit = { latest.trySend(it) }
        listeners.compute(fileId) { _, set -> (set ?: CopyOnWriteArraySet()).apply { add(listener) } }
        launch { for (file in latest) emit(file) }
        emit(initial())
        awaitClose {
            listeners.computeIfPresent(fileId) { _, set -> set.apply { remove(listener) }.takeIf { it.isNotEmpty() } }
        }
    }

    companion object {
        const val PRIORITY_BACKGROUND = 1
        const val PRIORITY_VISIBLE = 16
        const val PRIORITY_VIEWER = 32
    }
}
