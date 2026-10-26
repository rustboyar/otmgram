package dev.otmgram.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
import dev.otmgram.sync.NotificationDisplay
import dev.otmgram.sync.NotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes what the app keeps besides TDLib's own data: old copies of sent files, and on
 * logout (here or from another device) everything that belonged to the account. TDLib
 * deletes its database and downloads itself, but not its log, which can hold message text
 * and names: that goes too. The app's own log stays: it has none, and tells what happened.
 */
@Singleton
class SessionCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val outgoing: OutgoingFiles,
    private val notifications: NotificationState,
    private val shownNotifications: NotificationDisplay,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch(Dispatchers.IO) { outgoing.removeOld() }
        scope.launch {
            // Closed: TDLib is done logging out and has deleted its data.
            td.authorizationState.collect { if (it is TdApi.AuthorizationStateClosed) afterLogout() }
        }
    }

    private suspend fun afterLogout() {
        withContext(Dispatchers.IO) {
            outgoing.removeAll()
            File(context.cacheDir, "logs").deleteRecursively() // Shared log archives.
            AppLog.folder?.let { folder ->
                // Emptied rather than deleted: the next TDLib instance already writes to it.
                File(folder, AppLog.TDLIB_FILE).takeIf { it.exists() }?.writeBytes(ByteArray(0))
                // TDLib's previous part, once the log reached its size limit.
                File(folder, AppLog.TDLIB_FILE + ".old").delete()
            }
        }
        notifications.clear()
        shownNotifications.cancelAll()
        withContext(Dispatchers.Main) { MapSnapshots.clearTiles(context) }
        AppLog.i(TAG, "Removed the account's files after logout")
    }

    private companion object {
        const val TAG = "SessionCleanup"
    }
}
