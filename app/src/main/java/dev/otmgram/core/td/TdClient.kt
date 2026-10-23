package dev.otmgram.core.td

import android.content.Context
import android.os.Build
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
    val authorizationState: StateFlow<TdApi.AuthorizationState?> = _authorizationState.asStateFlow()

    private val _chatFolders = MutableStateFlow<List<TdApi.ChatFolderInfo>?>(null)

    /**
     * The user's chat folders, or null until TDLib sends them. TDLib sends them only once
     * after startup and has no request to get them, so they are kept here.
     */
    val chatFolders: StateFlow<List<TdApi.ChatFolderInfo>?> = _chatFolders.asStateFlow()

    private val _connectionState = MutableStateFlow<TdApi.ConnectionState?>(null)

    /** Connection to Telegram; ready once TDLib has caught up with what happened offline. */
    val connectionState: StateFlow<TdApi.ConnectionState?> = _connectionState.asStateFlow()

    @Volatile
    private var client: Client? = null

    /** Creates the TDLib instance. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (client != null) return
        // Warnings and errors to a file next to the app's log, shared from settings.
        AppLog.folder?.let {
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamFile(File(it, "tdlib.log").path, TDLIB_LOG_BYTES, false)))
        }
        Client.execute(TdApi.SetLogVerbosityLevel(2))
        client = Client.create(::onUpdate, ::onException, ::onException)
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

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateChatFolders -> _chatFolders.value = update.chatFolders.toList()
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
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters(databaseKey.load())
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
                start()
            }
        }
        _authorizationState.value = state
    }

    /**
     * Opens the database with [key]. If that fails with a key, the database was never
     * encrypted with it (e.g. the app stopped while switching): open it without one, and
     * [encryptDatabase] tries again once ready.
     */
    private fun sendParameters(key: ByteArray?) {
        val client = synchronized(this) { client } ?: return
        client.send(tdlibParameters(key ?: ByteArray(0))) { result ->
            if (result is TdApi.Error && key != null) {
                AppLog.w(TAG, "Database didn't open with its key (${result.message}), opening without")
                databaseKey.delete()
                sendParameters(null)
            } else if (result is TdApi.Error && "encryption" in result.message) {
                // Encrypted with a key that's gone: nothing can open it again. Start over
                // with a fresh database (a new login); the chats themselves are on Telegram.
                AppLog.e(TAG, "Database key is lost (${result.message}); deleting the local database")
                client.send(TdApi.Destroy()) {}
            } else if (result is TdApi.Error) {
                AppLog.e(TAG, "Database didn't open: ${result.message}")
            }
        }
    }

    /** Encrypts an unencrypted database (installs from before, or after a failed switch). */
    private fun encryptDatabase() {
        if (databaseKey.load() != null) return
        val key = try {
            databaseKey.create()
        } catch (e: Exception) {
            AppLog.e(TAG, "Can't make a database key", e)
            return
        }
        val client = synchronized(this) { client } ?: return
        client.send(TdApi.SetDatabaseEncryptionKey(key)) { result ->
            if (result is TdApi.Error) {
                AppLog.e(TAG, "Encrypting the database failed: ${result.message}")
                databaseKey.delete()
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
    }
}
