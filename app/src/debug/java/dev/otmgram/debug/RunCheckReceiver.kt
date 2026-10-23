package dev.otmgram.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.sync.CheckWorker
import dev.otmgram.sync.NotificationState
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Runs [CheckWorker] once right away; periodic work can't be forced before it's due:
 * `adb shell am broadcast -n dev.otmgram/.debug.RunCheckReceiver`.
 * With `--ez renotify true`, messages already notified about are notified again.
 */
@AndroidEntryPoint
class RunCheckReceiver : BroadcastReceiver() {

    @Inject lateinit var state: NotificationState

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("renotify", false)) runBlocking { state.markNotified(0) }
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<CheckWorker>().build())
    }
}
