package dev.otmgram.testing

import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi
import kotlin.reflect.KClass

/**
 * A TDLib stand-in: answers requests with handlers set by the test, records every request,
 * and emits updates on demand. A handler may return [TdApi.Error] to make the request fail.
 */
class FakeTelegram : TelegramClient {
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    // States as the test sets them; ready and connected unless it says otherwise.
    override val authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(TdApi.AuthorizationStateReady())
    override val connectionState = MutableStateFlow<TdApi.ConnectionState?>(TdApi.ConnectionStateReady())
    override val chatFolders = MutableStateFlow<List<TdApi.ChatFolderInfo>?>(emptyList())
    override val activeLiveLocations = MutableStateFlow<List<TdApi.Message>?>(emptyList())

    /** How often [reconnect] was called. */
    var reconnects = 0

    override fun reconnect() {
        reconnects++
    }

    /** Every request sent, in order. */
    val requests = mutableListOf<TdApi.Function<*>>()

    private val handlers = mutableListOf<Pair<KClass<*>, (TdApi.Function<*>) -> TdApi.Object>>()

    /** Answers requests of type [F]; a later handler for the same type wins. */
    inline fun <reified F : TdApi.Function<*>> on(noinline answer: (F) -> TdApi.Object) {
        @Suppress("UNCHECKED_CAST")
        add(F::class, answer as (TdApi.Function<*>) -> TdApi.Object)
    }

    fun add(type: KClass<*>, answer: (TdApi.Function<*>) -> TdApi.Object) {
        handlers += type to answer
    }

    inline fun <reified F : TdApi.Function<*>> sent(): List<F> = requests.filterIsInstance<F>()

    override suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R {
        requests += function
        val handler = handlers.lastOrNull { it.first.isInstance(function) }
            ?: error("No answer for ${function.javaClass.simpleName}")
        val result = handler.second(function)
        if (result is TdApi.Error) throw TdException(result)
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    override fun sendIgnoringResult(function: TdApi.Function<*>) {
        requests += function
    }

    suspend fun emit(update: TdApi.Update) = _updates.emit(update)
}

fun error(code: Int = 400, message: String = "BAD_REQUEST") = TdApi.Error(code, message)
