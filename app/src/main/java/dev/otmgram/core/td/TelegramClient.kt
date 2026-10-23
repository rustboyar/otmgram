package dev.otmgram.core.td

import kotlinx.coroutines.flow.SharedFlow
import org.drinkless.tdlib.TdApi

/**
 * What repositories need from TDLib: requests and updates. [TdClient] is the real one;
 * tests use a fake that answers requests from a script.
 */
interface TelegramClient {
    /** All updates from TDLib. */
    val updates: SharedFlow<TdApi.Update>

    /** Sends [function] and returns its result, throwing [TdException] on error. */
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R

    /** Sends [function] without waiting for the result; errors are only logged. */
    fun sendIgnoringResult(function: TdApi.Function<*>)
}
