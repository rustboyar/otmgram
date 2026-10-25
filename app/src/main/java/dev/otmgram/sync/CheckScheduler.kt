package dev.otmgram.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.StorageRepository
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [CheckWorker] periodically while logged in with a folder picked, and records when
 * the app is open, so messages seen there aren't notified about.
 */
@Singleton
class CheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val notifications: NotificationCenter,
    private val settings: AppSettings,
    private val storage: StorageRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            combine(td.authorizationState, folders.selectedFolderId, settings.checkInterval) { auth, folder, interval ->
                when (auth) {
                    is TdApi.AuthorizationStateReady -> interval.minutes.takeIf { folder != null } ?: OFF
                    is TdApi.AuthorizationStateWaitPhoneNumber, is TdApi.AuthorizationStateLoggingOut -> OFF
                    // Starting up (possibly for a check right now): don't decide yet.
                    else -> null
                }
            }.filterNotNull().distinctUntilChanged().collect { minutes ->
                if (minutes == OFF) unschedule() else schedule(minutes)
            }
        }
        // Keep downloads within the limit: once per start, and after each check.
        scope.launch {
            td.authorizationState.first { it is TdApi.AuthorizationStateReady }
            try {
                storage.applyLimit()
            } catch (e: TdException) {
                AppLog.w("CheckScheduler", "Storage limit failed", e)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                notifications.setForeground(true)
                scope.launch { markSeen() }
            }

            // Notifying again only once what was on screen counts as seen.
            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    markSeen()
                    notifications.setForeground(false)
                }
            }
        })
    }

    /** Records what's in the folder now as seen; only once TDLib is ready (not yet on a cold start). */
    private suspend fun markSeen() {
        if (td.authorizationState.value !is TdApi.AuthorizationStateReady) return
        val folderId = folders.selectedFolderId.first() ?: return
        try {
            withTimeoutOrNull(MARK_SEEN_TIMEOUT_MS) { notifications.markSeen(folderId) }
                ?: AppLog.w("CheckScheduler", "Recording what was seen timed out")
        } catch (e: TdException) {
            AppLog.w("CheckScheduler", "Couldn't record what was seen", e)
        }
    }

    private fun schedule(minutes: Long) {
        AppLog.i("CheckScheduler", "Checking every $minutes min")
        val request = PeriodicWorkRequestBuilder<CheckWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun unschedule() {
        AppLog.i("CheckScheduler", "Not checking")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        notifications.clear()
    }

    private companion object {
        const val WORK_NAME = "check_new_messages"
        const val MARK_SEEN_TIMEOUT_MS = 10_000L

        /** Not checking: logged out, no folder, or checks turned off. */
        const val OFF = 0L
    }
}
