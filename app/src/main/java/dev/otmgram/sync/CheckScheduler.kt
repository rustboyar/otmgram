package dev.otmgram.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AppSettings
import dev.otmgram.data.StorageRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val td: TdClient,
    private val folders: FolderRepository,
    private val notifier: NewMessagesNotifier,
    private val state: NotificationState,
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
                Log.w("CheckScheduler", "Storage limit failed", e)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                notifier.cancel()
                scope.launch { state.markSeen() }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { state.markSeen() }
            }
        })
    }

    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun unschedule() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        notifier.cancel()
    }

    private companion object {
        const val WORK_NAME = "check_new_messages"

        /** Not checking: logged out, no folder, or checks turned off. */
        const val OFF = 0L
    }
}
