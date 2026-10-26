package dev.otmgram.sync

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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
 * The account stays offline meanwhile: it's online only while the app is on screen.
 */
@HiltWorker
class CheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val notifications: NotificationCenter,
    private val push: PushRegistration,
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
        // Let TDLib catch up with what happened while it was offline. Reconnected first, as
        // "ready" may be left over from before the process was frozen; the reconnect shows
        // as another state, unless it's over before this looks. Disconnected after, so that
        // the next message comes by push.
        td.whileConnected {
            withTimeoutOrNull(RECONNECT_TIMEOUT_MS) { td.connectionState.first { it !is TdApi.ConnectionStateReady } }
            if (withTimeoutOrNull(SYNC_TIMEOUT_MS) { td.connectionState.first { it is TdApi.ConnectionStateReady } } == null) {
                AppLog.w(TAG, "Skipped: no connection within ${SYNC_TIMEOUT_MS / 1000} s")
                return@whileConnected
            }
            // TDLib holds new notifications back for a moment, in case they're read elsewhere.
            delay(PENDING_START_MS)
            withTimeoutOrNull(PENDING_TIMEOUT_MS) { notifications.havePending.first { !it } }
                ?: AppLog.w(TAG, "Notifications still pending after ${PENDING_TIMEOUT_MS / 1000} s")
            push.tellTelegram() // Once a day.
            try {
                storage.applyLimit()
            } catch (e: TdException) {
                AppLog.w(TAG, "Storage limit failed", e)
            }
            AppLog.i(TAG, "Check done")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "CheckWorker"
        private const val NOW_WORK_NAME = "check_now"

        /**
         * Checks right away, e.g. after a push: expedited, so it runs even when the app may
         * not use the network in the background. One at a time; a running check gets what
         * a second push was about too.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // Before Android 12 expedited work needs a notification of its own.
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val START_TIMEOUT_MS = 20_000L
        private const val RECONNECT_TIMEOUT_MS = 5_000L
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val PENDING_START_MS = 2_000L
        private const val PENDING_TIMEOUT_MS = 45_000L
    }
}
