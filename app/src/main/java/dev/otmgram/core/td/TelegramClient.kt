package dev.otmgram.core.td

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

/**
 * What the app needs from TDLib: requests, updates, and the few states it keeps track of.
 * [TdClient] is the real one; tests use a fake that answers requests from a script.
 */
interface TelegramClient {
    /** All updates from TDLib. */
    val updates: SharedFlow<TdApi.Update>

    /** Current authorization state, or null until TDLib reports the first one. */
    val authorizationState: StateFlow<TdApi.AuthorizationState?>

    /** Connection to Telegram; ready once TDLib has caught up with what happened offline. */
    val connectionState: StateFlow<TdApi.ConnectionState?>

    /** The user's chat folders, or null until TDLib sends them. */
    val chatFolders: StateFlow<List<TdApi.ChatFolderInfo>?>

    /** Live location messages this app sent that are still running, or null until known. */
    val activeLiveLocations: StateFlow<List<TdApi.Message>?>

    /**
     * Runs [block] with TDLib's connections open, even in the background (reopened first, as
     * they may be dead after the process was frozen). Afterwards they close again if the app
     * is still in the background and nothing else needs them.
     */
    suspend fun <T> whileConnected(block: suspend () -> T): T

    /** Sends [function] and returns its result, throwing [TdException] on error. */
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R

    /** Sends [function] without waiting for the result; errors are only logged. */
    fun sendIgnoringResult(function: TdApi.Function<*>)
}
