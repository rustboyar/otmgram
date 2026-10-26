package dev.otmgram.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import dev.otmgram.data.NotificationMode
import dev.otmgram.data.StorageRepository
import dev.otmgram.di.ApplicationScope
import dev.otmgram.di.NotificationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets up how messages arrive while logged in with a folder picked, as the settings say:
 * a connection kept open ([ConnectionService]) with an hourly [CheckWorker] as backup, or
 * only the check at its interval, or nothing. Also records when the app is open, so
 * messages seen there aren't notified about.
 */
@Singleton
class CheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val notifications: NotificationCenter,
    private val settings: AppSettings,
    private val storage: StorageRepository,
    @NotificationStore private val store: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Messages come over a connection kept open. */
    @Volatile
    private var live = false

    fun start() {
        scope.launch {
            combine(
                td.authorizationState, folders.selectedFolderId, settings.notificationMode, settings.checkInterval,
            ) { auth, folder, mode, interval ->
                when (auth) {
                    is TdApi.AuthorizationStateReady -> when {
                        folder == null -> OFF
                        mode == NotificationMode.Instant -> Delivery(live = true, checkMinutes = BACKUP_MINUTES)
                        mode == NotificationMode.Interval -> Delivery(live = false, checkMinutes = interval.minutes)
                        else -> OFF
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber, is TdApi.AuthorizationStateLoggingOut -> OFF
                    // Starting up (possibly for a check right now): don't decide yet.
                    else -> null
                }
            }.filterNotNull().distinctUntilChanged().collect { delivery ->
                live = delivery.live
                // A failure here stays here: this collector is what keeps checks scheduled.
                try {
                    delivery.checkMinutes?.let(::schedule) ?: unschedule()
                    if (delivery.live) ConnectionService.start(context) else ConnectionService.stop(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("CheckScheduler", "Setting up notifications failed", e)
                }
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
            forgetPush()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            /** Leaving, until notifications are on again; called off by coming back meanwhile. */
            private var leaving: Job? = null

            override fun onStart(owner: LifecycleOwner) {
                leaving?.cancel()
                leaving = null
                // Again, if Android stopped it meanwhile (it can be started only now).
                if (live) ConnectionService.start(context)
                notifications.setForeground(true)
                scope.launch { markSeen() }
            }

            // Notifying again only once what was on screen counts as seen, which can take a
            // while: back on screen by then, notifications stay off. On the main thread, as
            // onStart, so the two can't interleave.
            override fun onStop(owner: LifecycleOwner) {
                leaving = scope.launch {
                    markSeen()
                    withContext(Dispatchers.Main) { if (isActive) notifications.setForeground(false) }
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

    /**
     * Earlier versions had Telegram push through a push app (UnifiedPush), which skipped
     * messages for minutes after the app was used. Telegram stops pushing once told an empty
     * endpoint; the push app's subscription stays until removed there.
     */
    private suspend fun forgetPush() {
        val endpoint = store.data.first()[OLD_PUSH_ENDPOINT] ?: return
        try {
            if (endpoint.isNotEmpty()) td.send(TdApi.RegisterDevice(TdApi.DeviceTokenSimplePush(""), LongArray(0)))
            store.edit { prefs -> OLD_PUSH_KEYS.forEach { prefs.remove(it) } }
            AppLog.i("CheckScheduler", "Unregistered the old push")
        } catch (e: TdException) {
            AppLog.w("CheckScheduler", "Unregistering the old push failed", e)
        }
    }

    /** How messages arrive: live, by checking every [checkMinutes] (null: never), or both. */
    private data class Delivery(val live: Boolean, val checkMinutes: Long?)

    private companion object {
        const val WORK_NAME = "check_new_messages"
        const val MARK_SEEN_TIMEOUT_MS = 10_000L

        /** Live, a check now and then still catches what a lost connection missed. */
        const val BACKUP_MINUTES = 60L

        /** Logged out, no folder, or notifications turned off. */
        val OFF = Delivery(live = false, checkMinutes = null)

        val OLD_PUSH_ENDPOINT = stringPreferencesKey("push_endpoint")
        val OLD_PUSH_KEYS = listOf(
            OLD_PUSH_ENDPOINT, longPreferencesKey("push_registered_at"), stringPreferencesKey("push_failed"), longPreferencesKey("push_last_at"),
        )
    }
}
