package dev.otmgram.sync

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.ResolvedDistributor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push through UnifiedPush: a push app on the phone (a "distributor", such as ntfy) gives an
 * endpoint, Telegram is told to ping it on each new message ("Simple Push"), and each ping
 * wakes the app to fetch what's new ([CheckWorker]). A ping carries nothing but a number, so
 * the push app and its server only see that something arrived. (Telegram's Web Push would
 * carry the message, but in an old encryption whose keys travel in HTTP headers that push
 * apps don't pass on.)
 *
 * Telegram pushes to only one of a session's registrations (in testing, the oldest), so
 * there must never be more than this one. And it doesn't push while it thinks the app is
 * connected, so TDLib disconnects in the background ([TelegramClient.whileConnected]).
 */
@Singleton
class PushRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val state: PushState,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Push apps installed, by package name. */
    fun distributors(): List<String> = UnifiedPush.getDistributors(context).filter { it != context.packageName }

    /** The push app in use, once it has answered; null if none. */
    fun distributor(): String? = UnifiedPush.getAckDistributor(context)

    /** The push app picked, whether it has answered yet or not. */
    fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    /**
     * Asks the push app for an endpoint, which [onNewEndpoint] then gets: the app picked
     * before, or the only (or default) one installed. Safe to repeat: the push app answers
     * with the same endpoint. False if the user has to [pick] one first.
     */
    fun register(): Boolean {
        if (UnifiedPush.getSavedDistributor(context) == null) {
            val found = UnifiedPush.resolveDefaultDistributor(context) as? ResolvedDistributor.Found ?: return false
            UnifiedPush.saveDistributor(context, found.packageName)
        }
        UnifiedPush.register(context, messageForDistributor = context.getString(R.string.app_name))
        return true
    }

    /** Lets the user pick among the push apps (Android's chooser), then registers with it. */
    fun pick(activity: Activity) {
        UnifiedPush.tryPickDistributor(activity) { picked -> if (picked) register() }
    }

    /** Stops push: the push app drops the endpoint, and Telegram stops sending (if still logged in). */
    suspend fun unregister() {
        val endpoint = state.endpoint()
        if (UnifiedPush.getSavedDistributor(context) != null) UnifiedPush.unregister(context)
        state.clear()
        if (endpoint == null || td.authorizationState.value !is TdApi.AuthorizationStateReady) return
        try {
            // An empty endpoint unregisters.
            td.send(TdApi.RegisterDevice(TdApi.DeviceTokenSimplePush(""), LongArray(0)))
            AppLog.i(TAG, "Unregistered from push")
        } catch (e: TdException) {
            AppLog.w(TAG, "Unregistering from push failed", e)
        }
    }

    suspend fun onNewEndpoint(endpoint: PushEndpoint) {
        state.setEndpoint(endpoint.url)
        AppLog.i(TAG, "New endpoint from ${distributor()}${if (endpoint.temporary) " (temporary)" else ""}")
        // In the background TDLib is disconnected; the check connects, and tells Telegram too.
        CheckWorker.runNow(context)
        tellTelegram()
    }

    /**
     * Tells Telegram where to push: after a new endpoint, and again once a day, as Telegram
     * asks of clients. Waits for TDLib, as a push app may start the process for this.
     */
    suspend fun tellTelegram() {
        val endpoint = state.endpoint() ?: return
        val registeredAt = state.registeredAt()
        if (registeredAt != null && System.currentTimeMillis() - registeredAt < REREGISTER_MS) return
        withTimeoutOrNull(READY_TIMEOUT_MS) { td.authorizationState.first { it is TdApi.AuthorizationStateReady } } ?: return
        try {
            td.send(TdApi.RegisterDevice(TdApi.DeviceTokenSimplePush(endpoint), LongArray(0)))
            state.setRegistered(System.currentTimeMillis())
            AppLog.i(TAG, "Registered for push with Telegram")
        } catch (e: TdException) {
            AppLog.w(TAG, "Registering for push with Telegram failed", e)
            state.setFailed(e.error.message)
        }
    }

    /** Something new: fetch it. */
    fun onMessage() {
        CheckWorker.runNow(context)
        scope.launch {
            state.setLastPush(System.currentTimeMillis())
            AppLog.i(TAG, "Push received")
        }
    }

    suspend fun onRegistrationFailed(reason: FailedReason) {
        AppLog.w(TAG, "The push app couldn't register: $reason")
        state.setFailed(reason.name)
    }

    /** The push app dropped the registration (e.g. it was uninstalled, or the user removed it there). */
    suspend fun onUnregistered() {
        AppLog.w(TAG, "The push app ended the registration")
        state.clear()
    }

    private companion object {
        const val TAG = "Push"
        const val REREGISTER_MS = 24 * 60 * 60 * 1000L
        const val READY_TIMEOUT_MS = 20_000L
    }
}
