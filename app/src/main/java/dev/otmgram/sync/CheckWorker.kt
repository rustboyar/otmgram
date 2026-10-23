package dev.otmgram.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/** The periodic check for new messages; TDLib keeps the account offline meanwhile. */
@HiltWorker
class CheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val td: TdClient,
    private val folders: FolderRepository,
    private val notifier: NewMessagesNotifier,
    private val state: NotificationState,
    private val storage: StorageRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) {
            // The user is looking at the app right now.
            state.markSeen()
            return Result.success()
        }
        // The process may have just been started for this check; TDLib starts with it.
        val authorized = withTimeoutOrNull(START_TIMEOUT_MS) {
            td.authorizationState.first { it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters }
        }
        if (authorized !is TdApi.AuthorizationStateReady) return Result.success()
        val folderId = folders.selectedFolderId.first() ?: return Result.success()
        // Let TDLib catch up with what happened while it was offline.
        withTimeoutOrNull(SYNC_TIMEOUT_MS) { td.connectionState.first { it is TdApi.ConnectionStateReady } }
            ?: return Result.success()
        return try {
            notifier.check(folderId)
            storage.applyLimit()
            Result.success()
        } catch (e: TdException) {
            Log.w(TAG, "Check failed", e)
            Result.success()
        }
    }

    private companion object {
        const val TAG = "CheckWorker"
        const val START_TIMEOUT_MS = 20_000L
        const val SYNC_TIMEOUT_MS = 60_000L
    }
}
