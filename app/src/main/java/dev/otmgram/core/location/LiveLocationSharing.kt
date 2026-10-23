package dev.otmgram.core.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** An active live location share. [endsAt] is a Unix time. */
data class LiveShare(val chatId: Long, val chatTitle: String, val endsAt: Int)

/** Starts and stops sharing live location (one chat at a time) via [LiveLocationService]. */
@Singleton
class LiveLocationSharing @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _share = MutableStateFlow<LiveShare?>(null)

    /** The current share, or null when not sharing. */
    val share: StateFlow<LiveShare?> = _share.asStateFlow()

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

    internal fun update(share: LiveShare?) {
        _share.value = share
    }

    companion object {
        /** Durations offered for sharing, in seconds. */
        val PERIODS = listOf(15 * 60 to R.string.live_15_minutes, 60 * 60 to R.string.live_1_hour, 8 * 60 * 60 to R.string.live_8_hours)
    }
}
