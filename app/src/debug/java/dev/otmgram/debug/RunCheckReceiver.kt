package dev.otmgram.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.otmgram.sync.CheckWorker

/**
 * Runs [CheckWorker] once right away; periodic work can't be forced before it's due:
 * `adb shell am broadcast -n dev.otmgram/.debug.RunCheckReceiver`. Only the shell may
 * send it (it holds DUMP), not other apps.
 */
class RunCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<CheckWorker>().build())
    }
}
