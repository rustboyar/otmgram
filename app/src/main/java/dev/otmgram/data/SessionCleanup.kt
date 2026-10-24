package dev.otmgram.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.di.ApplicationScope
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
 * deletes its database and downloads itself. The app's log stays: it has no message text
 * or names, and tells what happened.
 */
@Singleton
class SessionCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val outgoing: OutgoingFiles,
    private val notifications: NotificationState,
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
        }
        notifications.clear()
        withContext(Dispatchers.Main) { MapSnapshots.clearTiles(context) }
        AppLog.i(TAG, "Removed the account's files after logout")
    }

    private companion object {
        const val TAG = "SessionCleanup"
    }
}
