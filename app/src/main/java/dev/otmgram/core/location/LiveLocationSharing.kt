package dev.otmgram.core.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** An active live location share. [endsAt] is a Unix time. */
data class LiveShare(
    val chatId: Long,
    val chatTitle: String,
    val endsAt: Int,
    /** False until the first position is known; the message is sent only then. */
    val located: Boolean = false,
)

/** A share that couldn't start in [chatId]: [locationOff], or Telegram's [reason]. */
data class LiveFailure(val chatId: Long, val locationOff: Boolean, val reason: String?)

/** Starts and stops sharing live location (one chat at a time) via [LiveLocationService]. */
@Singleton
class LiveLocationSharing @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _share = MutableStateFlow<LiveShare?>(null)

    /** The current share, or null when not sharing. */
    val share: StateFlow<LiveShare?> = _share.asStateFlow()

    private val _failures = MutableSharedFlow<LiveFailure>(extraBufferCapacity = 1)

    /** Shares that couldn't start, to tell the user why rather than just ending. */
    val failures: SharedFlow<LiveFailure> = _failures.asSharedFlow()

    /** Needs location permission; must be called while the app is in the foreground. */
    fun start(chatId: Long, chatTitle: String, periodSeconds: Int) {
        val intent = Intent(context, LiveLocationService::class.java)
            .setAction(LiveLocationService.ACTION_START)
            .putExtra(LiveLocationService.EXTRA_CHAT_ID, chatId)
            .putExtra(LiveLocationService.EXTRA_CHAT_TITLE, chatTitle)
            .putExtra(LiveLocationService.EXTRA_PERIOD, periodSeconds)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        if (_share.value == null) return
        context.startService(Intent(context, LiveLocationService::class.java).setAction(LiveLocationService.ACTION_STOP))
    }

    /**
     * Ends live locations this app sent that are still running, though nothing is shared:
     * the process was killed while sharing, so the service couldn't end them. Call at start.
     */
    fun endLeftovers() {
        scope.launch {
            td.authorizationState.first { it is TdApi.AuthorizationStateReady }
            // TDLib tells shortly after starting, if there are any.
            delay(LEFTOVER_WAIT_MS)
            if (_share.value == null) endAll()
        }
    }

    /** Ends every live location this app is sharing, in the chats (e.g. before logging out). */
    suspend fun endAll() {
        td.activeLiveLocations.value.orEmpty().forEach { message ->
            try {
                td.send(TdApi.EditMessageLiveLocation(message.chatId, message.id, null, null))
                AppLog.i(TAG, "Ended a live location left running")
            } catch (e: TdException) {
                AppLog.w(TAG, "Ending a live location failed", e)
            }
        }
    }

    internal fun update(share: LiveShare?) {
        _share.value = share
    }

    internal fun failed(failure: LiveFailure) {
        _failures.tryEmit(failure)
    }

    companion object {
        /** Durations offered for sharing, in seconds. */
        private const val TAG = "LiveLocation"
        private const val LEFTOVER_WAIT_MS = 10_000L

        val PERIODS = listOf(15 * 60 to R.string.live_15_minutes, 60 * 60 to R.string.live_1_hour, 8 * 60 * 60 to R.string.live_8_hours)
    }
}
