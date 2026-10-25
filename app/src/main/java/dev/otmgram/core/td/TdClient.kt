package dev.otmgram.core.td

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.BuildConfig
import dev.otmgram.core.log.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An error returned by TDLib in response to a request. */
class TdException(val error: TdApi.Error) : Exception("TDLib error ${error.code}: ${error.message}")

/**
 * The single TDLib instance of the app.
 *
 * Exposes requests as suspend functions and updates as flows. Handles the parts of the
 * TDLib lifecycle that need no user input: sending TDLib parameters, keeping the account
 * offline, and creating a fresh instance after logout closes the old one.
 */
@Singleton
class TdClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseKey: DatabaseKey,
) : TelegramClient {
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * All updates from TDLib. Updates emitted while nobody is subscribed are dropped, so
     * long-lived collectors must subscribe before [start].
     *
     * The buffer is unbounded so no update is ever lost; in return collectors must be quick
     * and never wait for a TDLib request inside collect (launch it separately instead), or
     * updates pile up behind them.
     */
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val _authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(null)

    /** Current authorization state, or null until TDLib reports the first one. */
    override val authorizationState: StateFlow<TdApi.AuthorizationState?> = _authorizationState.asStateFlow()

    private val _chatFolders = MutableStateFlow<List<TdApi.ChatFolderInfo>?>(null)

    /**
     * The user's chat folders, or null until TDLib sends them. TDLib sends them only once
     * after startup and has no request to get them, so they are kept here.
     */
    override val chatFolders: StateFlow<List<TdApi.ChatFolderInfo>?> = _chatFolders.asStateFlow()

    private val _activeLiveLocations = MutableStateFlow<List<TdApi.Message>?>(null)

    /**
     * Live location messages this app sent that are still running, or null until TDLib
     * tells; like [chatFolders], only sent as updates.
     */
    override val activeLiveLocations: StateFlow<List<TdApi.Message>?> = _activeLiveLocations.asStateFlow()

    private val _connectionState = MutableStateFlow<TdApi.ConnectionState?>(null)

    /** Connection to Telegram; ready once TDLib has caught up with what happened offline. */
    override val connectionState: StateFlow<TdApi.ConnectionState?> = _connectionState.asStateFlow()

    @Volatile
    private var client: Client? = null

    private val opener = DatabaseOpener(
        load = databaseKey::load,
        deleteKey = databaseKey::delete,
        send = { key, done -> client?.send(tdlibParameters(key ?: ByteArray(0))) { done(it as? TdApi.Error) } },
        destroy = { client?.send(TdApi.Destroy()) {} },
        later = { delayMs, block -> Handler(Looper.getMainLooper()).postDelayed(block, delayMs) },
    )

    /** Creates the TDLib instance. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (client != null) return
        // Warnings and errors to a file next to the app's log, shared from settings.
        AppLog.folder?.let {
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamFile(File(it, AppLog.TDLIB_FILE).path, TDLIB_LOG_BYTES, false)))
        }
        Client.execute(TdApi.SetLogVerbosityLevel(2))
        client = Client.create(::onUpdate, ::onException, ::onException)
        watchNetwork()
    }

    /** Sends [function] to TDLib and returns its result, throwing [TdException] on error. */
    override suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R {
        val client = checkNotNull(client) { "TdClient is not started" }
        return suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as R)
                }
            }
        }
    }

    /**
     * Makes TDLib reopen its connections now. A process that was frozen between background
     * checks still reports [connectionState] as ready from before, over dead connections.
     */
    override fun reconnect() {
        sendIgnoringResult(TdApi.SetNetworkType(context.tdNetworkType()))
    }

    private var watchingNetwork = false

    /**
     * Tells TDLib when the network changes, so it reconnects right away rather than on a
     * timeout; and after the app was away for a while, as its connections may have died
     * meanwhile (Doze, a frozen process) and TDLib would wait long before trying again.
     */
    private fun watchNetwork() {
        if (watchingNetwork) return
        watchingNetwork = true
        val main = Handler(Looper.getMainLooper())
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var network: Network? = null
            private var type: TdApi.NetworkType = TdApi.NetworkTypeNone()

            // The type comes with the callback: asking ConnectivityManager here can still
            // answer with the previous network or none, which left TDLib waiting for a
            // network that was already there.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val type = capabilities.tdNetworkType()
                // Also called as the signal changes: reconnecting each time would drop
                // connections that work.
                if (network == this.network && type.constructor == this.type.constructor) return
                this.network = network
                this.type = type
                sendIgnoringResult(TdApi.SetNetworkType(type))
            }

            override fun onLost(network: Network) {
                if (network != this.network) return
                this.network = null
                type = TdApi.NetworkTypeNone()
                sendIgnoringResult(TdApi.SetNetworkType(type))
            }

            // Battery or data saver cut the app off while in the background: once it's let
            // through again, there's no point waiting for TDLib's next try.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked && network == this.network) sendIgnoringResult(TdApi.SetNetworkType(type))
            }
        }, main)
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                private var stoppedAt = 0L

                override fun onStop(owner: LifecycleOwner) {
                    stoppedAt = SystemClock.elapsedRealtime()
                }

                override fun onStart(owner: LifecycleOwner) {
                    if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > AWAY_RECONNECT_MS) reconnect()
                }
            })
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateChatFolders -> _chatFolders.value = update.chatFolders.toList()
            is TdApi.UpdateActiveLiveLocationMessages -> _activeLiveLocations.value = update.messages.toList()
            is TdApi.UpdateConnectionState -> {
                AppLog.i(TAG, "Connection: ${update.state.javaClass.simpleName.removePrefix("ConnectionState")}")
                _connectionState.value = update.state
            }
        }
        if (update is TdApi.Update) {
            _updates.tryEmit(update)
        }
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        AppLog.i(TAG, "Authorization: ${state.javaClass.simpleName.removePrefix("AuthorizationState")}")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> opener.open()
            is TdApi.AuthorizationStateReady -> {
                // Never show the account as online, see PLAN.md.
                sendIgnoringResult(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
                encryptDatabase()
            }
            // An instance is closed after logout (its local data is already deleted by
            // TDLib); a new one is needed to log in again.
            is TdApi.AuthorizationStateClosed -> synchronized(this) {
                client = null
                _chatFolders.value = null
                _activeLiveLocations.value = null
                start()
            }
        }
        _authorizationState.value = state
    }

    /** Encrypts an unencrypted database (installs from before, or after a failed switch). */
    private fun encryptDatabase() {
        if (databaseKey.load() != StoredKey.None) return
        val key = try {
            databaseKey.create()
        } catch (e: Exception) {
            AppLog.e(TAG, "Can't make a database key", e)
            return
        }
        val client = synchronized(this) { client } ?: return
        client.send(TdApi.SetDatabaseEncryptionKey(key)) { result ->
            // On failure the key file stays: on the next start the database won't open with
            // it, opens without, and the key is replaced. Deleting it now could lose the
            // database, if the key was applied after all.
            if (result is TdApi.Error) {
                AppLog.e(TAG, "Encrypting the database failed: ${result.message}")
            } else {
                AppLog.i(TAG, "Database encrypted")
            }
        }
    }

    private fun tdlibParameters(key: ByteArray) = TdApi.SetTdlibParameters().apply {
        useTestDc = BuildConfig.TELEGRAM_TEST_DC
        // Separate databases, so switching between test and production servers is safe.
        databaseDirectory = File(context.filesDir, if (useTestDc) "tdlib-test" else "tdlib").absolutePath
        databaseEncryptionKey = key
        useFileDatabase = true
        useChatInfoDatabase = true
        useMessageDatabase = true
        useSecretChats = false
        apiId = BuildConfig.TELEGRAM_API_ID
        apiHash = BuildConfig.TELEGRAM_API_HASH
        systemLanguageCode = Locale.getDefault().toLanguageTag()
        deviceModel = Build.MODEL
        systemVersion = Build.VERSION.RELEASE
        applicationVersion = BuildConfig.VERSION_NAME
    }

    /** Sends [function] without waiting for the result; errors are only logged. */
    override fun sendIgnoringResult(function: TdApi.Function<*>) {
        // The first updates can arrive before Client.create() returns in start();
        // the lock waits for the client to be assigned.
        val client = synchronized(this) { client }
        client?.send(function) { result ->
            if (result is TdApi.Error) AppLog.w(TAG, "${function.javaClass.simpleName} failed: ${result.message}")
        }
    }

    private fun onException(e: Throwable) {
        AppLog.e(TAG, "Exception in TDLib handler", e)
    }

    private companion object {
        const val TAG = "TdClient"
        const val TDLIB_LOG_BYTES = 2L * 1024 * 1024

        /** Away longer than this, the connections are reopened when the app comes back. */
        const val AWAY_RECONNECT_MS = 60_000L
    }
}
