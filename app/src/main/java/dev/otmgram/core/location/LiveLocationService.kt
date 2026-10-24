package dev.otmgram.core.location

import android.app.AlarmManager
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationListenerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Shares the device's position as a live location message: sends it, then edits it as the
 * position changes, until the period ends or sharing is stopped.
 *
 * A foreground service of type location, started while the app is visible, so it keeps
 * receiving positions in the background with only the "while using the app" permission.
 */
@AndroidEntryPoint
class LiveLocationService : Service() {

    @Inject lateinit var td: TdClient

    @Inject lateinit var locations: LocationSource

    @Inject lateinit var sharing: LiveLocationSharing

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shareJob: Job? = null
    private var listener: LocationListenerCompat? = null

    /** The live location message being updated, once Telegram has confirmed it. */
    private var chatId = 0L
    private var messageId = 0L
    private var period = 0
    private var endsAt = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Sharing ends with the session: logging out, or the account being logged out elsewhere.
        scope.launch {
            td.authorizationState.collect {
                if (it != null && it !is TdApi.AuthorizationStateReady) quit()
            }
        }
    }

    @SuppressLint("MissingPermission") // Checked by the UI before starting.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
                val title = intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty()
                val period = intent.getIntExtra(EXTRA_PERIOD, 0)
                val share = LiveShare(chatId, title, endsAt = now() + period)
                AppLog.i(TAG, "Sharing for ${period / 60} min")
                startForeground(share)
                sharing.update(share)
                shareJob?.cancel()
                shareJob = scope.launch {
                    stopCurrent()
                    share(chatId, period, share.endsAt)
                }
            }
            ACTION_STOP -> {
                AppLog.i(TAG, "Stopped by the user")
                scope.launch { stopAndQuit() }
            }
            ACTION_END -> {
                AppLog.i(TAG, "Period over (alarm)")
                scope.launch { stopAndQuit() }
            }
        }
        return START_NOT_STICKY
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun share(chatId: Long, period: Int, endsAt: Int) {
        try {
            val first = locations.current() ?: throw IllegalStateException("Location is off")
            this.messageId = sendLiveLocation(chatId, period, first)
            AppLog.i(TAG, "Live location message confirmed")
            this.chatId = chatId
            this.period = period
            this.endsAt = endsAt
            listenForUpdates()
            // The alarm ends it even if the phone sleeps through the period, which pauses this.
            scheduleEnd(endsAt)
            delay(period * 1000L)
            AppLog.i(TAG, "Period over")
            stopAndQuit()
        } catch (e: CancellationException) {
            throw e // Replaced by a new share.
        } catch (e: Exception) {
            AppLog.w(TAG, "Live location sharing failed", e)
            quit()
        }
    }

    /**
     * Sends the message and returns its final id, known only once Telegram confirms it.
     * Throws if Telegram rejects it or doesn't answer within [SEND_TIMEOUT_MS].
     */
    private suspend fun sendLiveLocation(chatId: Long, period: Int, location: Location): Long {
        // Results by the message's temporary id: its final id, or why it failed.
        val results = ConcurrentHashMap<Long, Result<Long>>()
        var pendingId: Long? = null
        var confirmed = false
        val finalId = CompletableDeferred<Long>()
        // Called on the main thread, from the collector and after sending.
        fun settle() {
            val result = pendingId?.let(results::get) ?: return
            result.fold(finalId::complete, finalId::completeExceptionally)
        }
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateMessageSendSucceeded -> results[update.oldMessageId] = Result.success(update.message.id)
                    is TdApi.UpdateMessageSendFailed ->
                        results[update.oldMessageId] = Result.failure(IllegalStateException("Telegram rejected it: ${update.error.message}"))
                    else -> return@collect
                }
                settle()
            }
        }
        try {
            val content = TdApi.InputMessageLiveLocation(liveLocation(location, period))
            val sent = td.send(TdApi.SendMessage(chatId, null, null, null, null, content))
            pendingId = sent.id
            settle()
            val id = withTimeoutOrNull(SEND_TIMEOUT_MS) { finalId.await() }
                ?: throw IllegalStateException("No confirmation from Telegram")
            confirmed = true
            return id
        } finally {
            collector.cancel()
            // Failed, timed out, stopped or replaced before Telegram confirmed it: TDLib would
            // still send it later, a live location nobody updates or ends. Possibly cancelled,
            // so without suspending.
            val temporaryId = pendingId
            if (!confirmed && temporaryId != null) {
                val sentAs = results[temporaryId]?.getOrNull()
                if (sentAs != null) {
                    td.sendIgnoringResult(TdApi.EditMessageLiveLocation(chatId, sentAs, null, null))
                } else {
                    td.sendIgnoringResult(TdApi.DeleteMessages(chatId, longArrayOf(temporaryId), true))
                }
                AppLog.i(TAG, "Unconfirmed live location withdrawn")
            }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun listenForUpdates() {
        val listener = LocationListenerCompat { location ->
            if (now() >= endsAt) {
                AppLog.i(TAG, "Period over (late)")
                scope.launch { stopAndQuit() }
                return@LocationListenerCompat
            }
            scope.launch {
                try {
                    td.send(TdApi.EditMessageLiveLocation(chatId, messageId, null, liveLocation(location, period)))
                } catch (e: TdException) {
                    AppLog.w(TAG, "Live location update failed", e)
                }
            }
        }
        if (locations.requestUpdates(MIN_INTERVAL_MS, MIN_DISTANCE_M, listener)) this.listener = listener
    }

    /** Ends the share in the chat (best effort) and stops listening. */
    private suspend fun stopCurrent() {
        listener?.let(locations::removeUpdates)
        listener = null
        if (messageId != 0L) {
            try {
                td.send(TdApi.EditMessageLiveLocation(chatId, messageId, null, null))
            } catch (e: TdException) {
                AppLog.w(TAG, "Stopping live location failed", e)
            }
        }
        messageId = 0
    }

    private suspend fun stopAndQuit() {
        stopCurrent()
        quit()
    }

    /** Ends sharing at [endsAt] (a Unix time), whether or not the phone is asleep then. */
    private fun scheduleEnd(endsAt: Int) {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt * 1000L, endIntent())
    }

    private fun endIntent() = PendingIntent.getService(
        this, REQUEST_END, Intent(this, LiveLocationService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun quit() {
        getSystemService(AlarmManager::class.java).cancel(endIntent())
        listener?.let(locations::removeUpdates)
        listener = null
        sharing.update(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed while sharing: end it in the chat rather than leave it hanging until expiry.
        if (messageId != 0L) td.sendIgnoringResult(TdApi.EditMessageLiveLocation(chatId, messageId, null, null))
        listener?.let(locations::removeUpdates)
        sharing.update(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground(share: LiveShare) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(share.endsAt * 1000L))
        val stop = PendingIntent.getService(
            this, REQUEST_STOP, Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(getString(R.string.live_title))
            .setContentText(getString(R.string.live_text, share.chatTitle, until))
            .setContentIntent(open)
            .addAction(0, getString(R.string.live_stop), stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun liveLocation(location: Location, period: Int) = TdApi.LiveLocation(
        TdApi.Location(location.latitude, location.longitude, location.accuracy.toDouble()), period, 0, 0,
    )

    private fun now() = (System.currentTimeMillis() / 1000).toInt()

    companion object {
        const val ACTION_START = "dev.otmgram.action.START_LIVE_LOCATION"
        const val ACTION_STOP = "dev.otmgram.action.STOP_LIVE_LOCATION"
        private const val ACTION_END = "dev.otmgram.action.END_LIVE_LOCATION"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_CHAT_TITLE = "chatTitle"
        const val EXTRA_PERIOD = "period"

        private const val TAG = "LiveLocationService"
        private const val CHANNEL_ID = "live_location"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_STOP = 1
        private const val REQUEST_END = 2
        private const val REQUEST_OPEN = 3
        private const val MIN_INTERVAL_MS = 30_000L
        private const val MIN_DISTANCE_M = 25f
        private const val SEND_TIMEOUT_MS = 60_000L
    }
}
