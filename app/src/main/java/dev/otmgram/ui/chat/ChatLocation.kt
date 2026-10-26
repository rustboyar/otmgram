package dev.otmgram.ui.chat

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.location.LiveFailure
import dev.otmgram.core.location.LiveShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where you are, sent from the chat: the current position once, or live for a while. Live
 * sharing runs beyond the chat (`LiveLocationSharing`); this starts it here, shows it while
 * it's here, and says why when it can't start. Positions need the location permission, which
 * the screen asks for first.
 */
class ChatLocation(
    private val scope: CoroutineScope,
    private val chatId: Long,
    /** A fresh position, or null if none comes. */
    private val current: suspend () -> Location?,
    /** The last position the device knows, at once; can be old, or null. */
    private val lastKnown: () -> Location?,
    private val send: suspend (Location) -> Unit,
    /** The live share, in whichever chat. */
    liveShare: StateFlow<LiveShare?>,
    liveFailures: Flow<LiveFailure>,
    private val startSharing: (periodSeconds: Int) -> Unit,
    private val stopSharing: () -> Unit,
    private val strings: Strings,
    private val notify: (String) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    /** True while waiting for a position to send; the chat shows where the message will be. */
    var locating by mutableStateOf(false)
        private set

    /** Where you are, for the map in the location sheet; null until known. */
    var here by mutableStateOf<Location?>(null)
        private set

    /** The live share if it's in this chat. */
    val live: StateFlow<LiveShare?> = liveShare.map { it?.takeIf { share -> share.chatId == chatId } }
        .stateIn(scope, SharingStarted.Eagerly, liveShare.value?.takeIf { it.chatId == chatId })

    init {
        scope.launch {
            liveFailures.collect { failure ->
                if (failure.chatId != chatId) return@collect
                notify(
                    if (failure.locationOff) {
                        strings.get(R.string.notice_no_location)
                    } else {
                        strings.get(R.string.notice_live_failed, failure.reason ?: strings.get(R.string.something_went_wrong))
                    },
                )
            }
        }
    }

    /** Sends the current position; failing, [onError] says why. */
    fun sendCurrent() {
        scope.launchLogged(TAG, onError) {
            locating = true
            val location = try {
                current()
            } finally {
                locating = false
            }
            if (location == null) notify(strings.get(R.string.notice_no_location)) else send(location)
        }
    }

    /** Shows the last known position at once, then a fresh one. */
    fun locate() {
        here = lastKnown()
        // Failing, the last known one stays: the sheet can still send or share.
        scope.launchLogged(TAG) { current()?.let { here = it } }
    }

    /** Shares live location here, for [periodSeconds]. */
    fun startLive(periodSeconds: Int) = startSharing(periodSeconds)

    fun stopLive() = stopSharing()
}

private const val TAG = "ChatLocation"
