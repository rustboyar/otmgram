package dev.otmgram.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.otmgram.core.log.AppLog
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
        AppLog.i(TAG, "Check started (attempt ${runAttemptCount + 1})")
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) {
            // The user is looking at the app right now.
            AppLog.i(TAG, "Skipped: the app is open")
            state.markSeen()
            return Result.success()
        }
        // The process may have just been started for this check; TDLib starts with it.
        val authorized = withTimeoutOrNull(START_TIMEOUT_MS) {
            td.authorizationState.first { it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters }
        }
        if (authorized !is TdApi.AuthorizationStateReady) {
            AppLog.i(TAG, "Skipped: not logged in (${authorized?.javaClass?.simpleName ?: "TDLib didn't start in time"})")
            return Result.success()
        }
        val folderId = folders.selectedFolderId.first() ?: return Result.success().also { AppLog.i(TAG, "Skipped: no folder") }
        // Let TDLib catch up with what happened while it was offline.
        withTimeoutOrNull(SYNC_TIMEOUT_MS) { td.connectionState.first { it is TdApi.ConnectionStateReady } }
            ?: return Result.success().also { AppLog.w(TAG, "Skipped: no connection within ${SYNC_TIMEOUT_MS / 1000} s") }
        return try {
            notifier.check(folderId)
            storage.applyLimit()
            AppLog.i(TAG, "Check done")
            Result.success()
        } catch (e: TdException) {
            AppLog.w(TAG, "Check failed", e)
            Result.success()
        }
    }

    private companion object {
        const val TAG = "CheckWorker"
        const val START_TIMEOUT_MS = 20_000L
        const val SYNC_TIMEOUT_MS = 60_000L
    }
}
