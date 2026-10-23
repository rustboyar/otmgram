package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads of TDLib files. Media is always downloaded automatically, see PLAN.md. */
@Singleton
class FileRepository @Inject constructor(
    private val td: TdClient,
) {
    /**
     * Starts downloading [fileId] (if not done yet) and emits its state until collection
     * stops. Higher [priority] (1..32) downloads first.
     */
    fun download(fileId: Int, priority: Int = PRIORITY_VISIBLE): Flow<TdApi.File> = channelFlow {
        var completed = false
        val emit: suspend (TdApi.File) -> Unit = { file ->
            // The request's result may arrive after newer updates; never go back from completed.
            if (!completed || file.local.isDownloadingCompleted) {
                completed = file.local.isDownloadingCompleted
                send(file)
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.filterIsInstance<TdApi.UpdateFile>()
                .filter { it.file.id == fileId }
                .collect { emit(it.file) }
        }
        emit(td.send(TdApi.DownloadFile(fileId, priority, 0, 0, false)))
    }

    companion object {
        const val PRIORITY_BACKGROUND = 1
        const val PRIORITY_VISIBLE = 16
        const val PRIORITY_VIEWER = 32
    }
}
