package dev.otmgram.core.td

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.BuildConfig
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
) {
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * All updates from TDLib. Updates emitted while nobody is subscribed are dropped, so
     * long-lived collectors must subscribe before [start].
     */
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val _authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(null)

    /** Current authorization state, or null until TDLib reports the first one. */
    val authorizationState: StateFlow<TdApi.AuthorizationState?> = _authorizationState.asStateFlow()

    private val _chatFolders = MutableStateFlow<List<TdApi.ChatFolderInfo>?>(null)

    /**
     * The user's chat folders, or null until TDLib sends them. TDLib sends them only once
     * after startup and has no request to get them, so they are kept here.
     */
    val chatFolders: StateFlow<List<TdApi.ChatFolderInfo>?> = _chatFolders.asStateFlow()

    @Volatile
    private var client: Client? = null

    /** Creates the TDLib instance. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (client != null) return
        Client.execute(TdApi.SetLogVerbosityLevel(if (BuildConfig.DEBUG) 2 else 1))
        client = Client.create(::onUpdate, ::onException, ::onException)
    }

    /** Sends [function] to TDLib and returns its result, throwing [TdException] on error. */
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R {
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
        }
        if (update is TdApi.Update) {
            _updates.tryEmit(update)
        }
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendIgnoringResult(tdlibParameters())
            // Never show the account as online, see PLAN.md.
            is TdApi.AuthorizationStateReady ->
                sendIgnoringResult(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
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

    private fun tdlibParameters() = TdApi.SetTdlibParameters().apply {
        useTestDc = BuildConfig.TELEGRAM_TEST_DC
        // Separate databases, so switching between test and production servers is safe.
        databaseDirectory = File(context.filesDir, if (useTestDc) "tdlib-test" else "tdlib").absolutePath
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

    private fun sendIgnoringResult(function: TdApi.Function<*>) {
        // The first updates can arrive before Client.create() returns in start();
        // the lock waits for the client to be assigned.
        val client = synchronized(this) { client }
        client?.send(function) { result ->
            if (result is TdApi.Error) Log.w(TAG, "${function.javaClass.simpleName} failed: ${result.message}")
        }
    }

    private fun onException(e: Throwable) {
        Log.e(TAG, "Exception in TDLib handler", e)
    }

    private companion object {
        const val TAG = "TdClient"
    }
}
