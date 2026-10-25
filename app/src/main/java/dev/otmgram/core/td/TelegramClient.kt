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

    /** Makes TDLib reopen its connections now, e.g. after the process was frozen. */
    fun reconnect()

    /** Closes the connections if the app is in the background, so that Telegram pushes. */
    fun disconnectInBackground()

    /** Sends [function] and returns its result, throwing [TdException] on error. */
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R

    /** Sends [function] without waiting for the result; errors are only logged. */
    fun sendIgnoringResult(function: TdApi.Function<*>)
}
