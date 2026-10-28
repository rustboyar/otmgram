package dev.otmgram.data

import androidx.collection.LruCache
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
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

/** A file's download and upload progress, as the app shows it. */
data class FileState(
    val id: Int,
    /** Size in bytes, TDLib's estimate while it isn't known yet, 0 if neither. */
    val size: Long,
    val downloaded: Long,
    val isDownloading: Boolean,
    /** Local path once fully downloaded. */
    val path: String?,
    val uploaded: Long,
    val isUploading: Boolean,
) {
    val isDownloaded: Boolean get() = path != null
    val downloadProgress: Float get() = if (size > 0) downloaded.toFloat() / size else 0f
    val uploadProgress: Float get() = if (size > 0) uploaded.toFloat() / size else 0f
}

fun TdApi.File.toState() = FileState(
    id = id,
    size = size.takeIf { it > 0 } ?: expectedSize,
    downloaded = local.downloadedSize,
    isDownloading = local.isDownloadingActive,
    path = local.path.takeIf { local.isDownloadingCompleted && it.isNotEmpty() },
    uploaded = remote.uploadedSize,
    isUploading = remote.isUploadingActive,
)

/** Downloads of TDLib files: which download when, see docs/ARCHITECTURE.md. */
@Singleton
class FileRepository @Inject constructor(
    private val td: TelegramClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Who waits for which file; one collector routes file updates to them. */
    private val listeners = ConcurrentHashMap<Int, MutableSet<(TdApi.File) -> Unit>>()

    /**
     * Files seen fully downloaded, see [lastKnown]: the most recent ones, as the process
     * lives for days and sees every avatar and sticker.
     */
    private val downloaded = object {
        private val map = LruCache<Int, FileState>(KNOWN_FILES)

        operator fun get(id: Int): FileState? = map.get(id)

        operator fun set(id: Int, state: FileState) {
            map.put(id, state)
        }

        fun remove(id: Int) {
            map.remove(id)
        }

        fun clear() = map.evictAll()
    }

    init {
        // One pass over all updates, off the main thread, instead of one per visible file.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                if (update is TdApi.UpdateFile) listeners[update.file.id]?.forEach { it(update.file) }
                // File ids belong to the account's TDLib instance; another login reuses them.
                if (update is TdApi.UpdateAuthorizationState && update.authorizationState !is TdApi.AuthorizationStateReady) {
                    downloaded.clear()
                }
            }
        }
    }

    /**
     * The file's state if it was seen downloaded, for drawing it at once when a screen is
     * shown again, instead of its placeholder until TDLib answers; null otherwise.
     */
    fun lastKnown(fileId: Int): FileState? = downloaded[fileId]

    /**
     * Starts downloading [fileId] (if not done yet) and emits its state until collection
     * stops. Higher [priority] (1..32) downloads first.
     */
    fun download(fileId: Int, priority: Int = PRIORITY_VISIBLE): Flow<FileState> =
        state(fileId, cancelUnfinished = true) { td.send(TdApi.DownloadFile(fileId, priority, 0, 0, false)) }

    /** Emits the state of [fileId] without downloading it, e.g. files downloaded on demand. */
    fun observe(fileId: Int): Flow<FileState> = state(fileId) { td.send(TdApi.GetFile(fileId)) }

    /** Starts downloading in the background; progress shows up in [observe]. */
    suspend fun startDownload(fileId: Int) {
        td.send(TdApi.DownloadFile(fileId, PRIORITY_VIEWER, 0, 0, false))
    }

    /** Stops a download started with [startDownload]; the part already loaded is kept. */
    suspend fun cancelDownload(fileId: Int) {
        td.send(TdApi.CancelDownloadFile(fileId, false))
    }

    /** Downloads [fileId] completely and returns its local path. */
    suspend fun downloadNow(fileId: Int): String =
        td.send(TdApi.DownloadFile(fileId, PRIORITY_VIEWER, 0, 0, true)).local.path

    /**
     * [cancelUnfinished]: when the last one watching stops (scrolled away), a download still
     * waiting its turn is called off, so a fling through a chat doesn't queue every photo it
     * passed ahead of those on screen. One already under way goes on.
     */
    private fun state(fileId: Int, cancelUnfinished: Boolean = false, initial: suspend () -> TdApi.File): Flow<FileState> = channelFlow {
        var completed = false
        val emit: suspend (TdApi.File) -> Unit = { file ->
            // The request's result may arrive after newer updates; never go back from completed.
            if (!completed || file.local.isDownloadingCompleted) {
                completed = file.local.isDownloadingCompleted
                val state = file.toState()
                if (state.isDownloaded) downloaded[fileId] = state else downloaded.remove(fileId)
                send(state)
            }
        }
        // Listening starts before the request, so no update can slip in between; only the
        // latest progress matters, and the last one (completion) is always kept.
        val latest = Channel<TdApi.File>(Channel.CONFLATED)
        val listener: (TdApi.File) -> Unit = { latest.trySend(it) }
        listeners.compute(fileId) { _, set -> (set ?: CopyOnWriteArraySet()).apply { add(listener) } }
        launch { for (file in latest) emit(file) }
        try {
            emit(initial())
        } catch (e: TdException) {
            // E.g. a file that can't be downloaded (no copy left anywhere). Shown as unknown
            // rather than crashing the screen; later updates still come through.
            AppLog.w(TAG, "File $fileId: ${e.error.message}")
        }
        awaitClose {
            val left = listeners.computeIfPresent(fileId) { _, set -> set.apply { remove(listener) }.takeIf { it.isNotEmpty() } }
            if (cancelUnfinished && !completed && left == null) td.sendIgnoringResult(TdApi.CancelDownloadFile(fileId, true))
        }
    }

    companion object {
        private const val TAG = "FileRepository"
        private const val KNOWN_FILES = 1000
        const val PRIORITY_VISIBLE = 16
        const val PRIORITY_VIEWER = 32
    }
}
