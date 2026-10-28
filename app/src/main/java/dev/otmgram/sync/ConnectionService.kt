package dev.otmgram.sync

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/**
 * Keeps TDLib connected while the app is in the background, so new messages come in as
 * they're sent and [NotificationCenter] notifies about them at once. Telegram's push isn't
 * reliable for this: it skips messages for minutes after a client was connected or online.
 *
 * A foreground service, so Android neither freezes the process nor cuts its network. While
 * the phone sleeps nothing runs, and Telegram closes a connection that hasn't pinged for
 * about 2 minutes: [Heartbeat] wakes the phone every minute for a moment, to send one small
 * request, which TDLib sends with its ping.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject lateinit var td: TelegramClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connected: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        running = true
        if (connected == null) {
            AppLog.i(TAG, "Staying connected")
            connected = scope.launch { td.whileConnected { awaitCancellation() } }
        }
        Heartbeat.schedule(this)
        // Restarted by Android if it has to stop it, e.g. short of memory.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Heartbeat.cancel(this)
        scope.cancel() // Ends whileConnected, which disconnects if the app is in the background.
        AppLog.i(TAG, "Stopped")
        super.onDestroy()
    }

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.connection_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(getString(R.string.connection_title))
            .setContentText(getString(R.string.connection_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // Before Android 14 a foreground service needs no type for this.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** Wakes the phone every minute, to keep the connection from timing out. */
    @AndroidEntryPoint
    class Heartbeat : BroadcastReceiver() {

        @Inject lateinit var td: TelegramClient

        @Inject @ApplicationScope lateinit var scope: CoroutineScope

        override fun onReceive(context: Context, intent: Intent) {
            // Left over from a service that's gone (the process was killed, then Instant was
            // turned off or the account logged out): stop waking the phone.
            if (!running) {
                cancel(context)
                return
            }
            schedule(context)
            // Awake anyway while the app is on screen, and TDLib pings by itself; and without a
            // network there's nothing to reach: no request then, only the next alarm.
            val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (onScreen || td.connectionState.value is TdApi.ConnectionStateWaitingForNetwork) return
            // Awake until TDLib has sent it and heard back; an answer after that wakes the phone anyway.
            val wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otmgram:heartbeat")
                .apply { acquire(WAKE_MS) }
            val pending = goAsync()
            scope.launch {
                try {
                    // A tiny request (the nearest data center), which also gets TDLib to ping;
                    // a dead connection is noticed and reopened.
                    withTimeoutOrNull(WAKE_MS - 1_000) { td.send(TdApi.TestNetwork()) }
                } catch (e: TdException) {
                    // No network, for one: TDLib reconnects once there is.
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    pending.finish()
                }
            }
        }

        companion object {
            private const val INTERVAL_MS = 60_000L
            private const val WAKE_MS = 10_000L

            /**
             * Inexact, so Android can line it up with other wake-ups: within 1¾ minutes,
             * under Telegram's timeout. Allowed while idle (Doze), within Android's quota.
             */
            fun schedule(context: Context) {
                context.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, intent(context))
            }

            fun cancel(context: Context) {
                context.getSystemService(AlarmManager::class.java).cancel(intent(context))
            }

            private fun intent(context: Context) = PendingIntent.getBroadcast(
                context, 0, Intent(context, Heartbeat::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    /**
     * Starts the app after the phone starts or the app is updated, which is enough: the
     * app's start starts the service ([CheckScheduler]), within the time Android allows it then.
     */
    class Starter : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> AppLog.i(TAG, "Phone started")
                Intent.ACTION_MY_PACKAGE_REPLACED -> AppLog.i(TAG, "App updated")
            }
        }
    }

    companion object {
        private const val TAG = "Connection"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 5

        /**
         * Starts it, unless it's running. Android allows that only while the app is on screen,
         * or just after the phone started or the app was updated; otherwise the next time.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is one.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    AppLog.i(TAG, "Not started: the app is in the background")
                } else {
                    throw e
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
            // Also when it isn't running in this process, so onDestroy won't cancel it.
            Heartbeat.cancel(context)
        }

        /** Running in this process: the heartbeat only keeps a connection the service holds. */
        @Volatile
        var running = false
            private set
    }
}
