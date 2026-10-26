package dev.otmgram.core.td

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import org.drinkless.tdlib.TdApi

/**
 * The [TelegramClient.updates] that [keep] keeps, sorted out on TDLib's thread as they come,
 * so that only those reach the collector's: a collector on the main thread otherwise gets
 * every update, file download progress included, many times a second. Subscribed as soon as
 * collected, like [TelegramClient.updates] (the filtering runs unconfined).
 */
fun TelegramClient.updates(keep: (TdApi.Update) -> Boolean): Flow<TdApi.Update> =
    updates.filter(keep).flowOn(Dispatchers.Unconfined)
