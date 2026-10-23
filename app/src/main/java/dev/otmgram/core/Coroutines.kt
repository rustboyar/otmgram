package dev.otmgram.core

import android.util.Log
import dev.otmgram.core.td.TdException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches [block]; a failure is logged and handed to [onError] instead of crashing the
 * app. For work whose failure the user can live with, e.g. loading a screen's details.
 */
fun CoroutineScope.launchLogged(
    tag: String,
    onError: (Exception) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Failed", e)
        onError(e)
    }
}

/** What to tell the user about [e]: TDLib's message, or the exception's. */
fun Exception.userMessage(): String = when (this) {
    is TdException -> error.message
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
}
