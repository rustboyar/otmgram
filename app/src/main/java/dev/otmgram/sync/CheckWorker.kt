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
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * The periodic check for new messages, a backup for when the process isn't running: it
 * lets TDLib catch up, and [NotificationCenter] shows what TDLib then notifies about.
 * TDLib keeps the account offline meanwhile.
 */
@HiltWorker
class CheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val notifications: NotificationCenter,
    private val storage: StorageRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "Check started (attempt ${runAttemptCount + 1})")
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) {
            // The user is looking at the app right now; leaving it records what was seen.
            AppLog.i(TAG, "Skipped: the app is open")
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
        folders.selectedFolderId.first() ?: return Result.success().also { AppLog.i(TAG, "Skipped: no folder") }
        // Let TDLib catch up with what happened while it was offline. Reconnecting first, as
        // "ready" may be left over from before the process was frozen; the reconnect shows
        // as another state, unless it's over before this looks.
        td.reconnect()
        withTimeoutOrNull(RECONNECT_TIMEOUT_MS) { td.connectionState.first { it !is TdApi.ConnectionStateReady } }
        withTimeoutOrNull(SYNC_TIMEOUT_MS) { td.connectionState.first { it is TdApi.ConnectionStateReady } }
            ?: return Result.success().also { AppLog.w(TAG, "Skipped: no connection within ${SYNC_TIMEOUT_MS / 1000} s") }
        // TDLib holds new notifications back for a moment, in case they're read elsewhere.
        delay(PENDING_START_MS)
        withTimeoutOrNull(PENDING_TIMEOUT_MS) { notifications.havePending.first { !it } }
            ?: AppLog.w(TAG, "Notifications still pending after ${PENDING_TIMEOUT_MS / 1000} s")
        try {
            storage.applyLimit()
        } catch (e: TdException) {
            AppLog.w(TAG, "Storage limit failed", e)
        }
        AppLog.i(TAG, "Check done")
        return Result.success()
    }

    private companion object {
        const val TAG = "CheckWorker"
        const val START_TIMEOUT_MS = 20_000L
        const val RECONNECT_TIMEOUT_MS = 5_000L
        const val SYNC_TIMEOUT_MS = 60_000L
        const val PENDING_START_MS = 2_000L
        const val PENDING_TIMEOUT_MS = 45_000L
    }
}
