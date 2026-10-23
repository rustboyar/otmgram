package dev.otmgram.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationState by preferencesDataStore("notifications")
private val SEEN_UP_TO = intPreferencesKey("seen_up_to")
private val NOTIFIED_UP_TO = intPreferencesKey("notified_up_to")

/** What the user has already seen or been notified about, as Unix times. */
@Singleton
class NotificationState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Messages up to this time were visible to the user: the app was open then. */
    suspend fun seenUpTo(): Int = context.notificationState.data.first()[SEEN_UP_TO] ?: now()

    /** Messages up to this time were already included in a notification. */
    suspend fun notifiedUpTo(): Int = context.notificationState.data.first()[NOTIFIED_UP_TO] ?: 0

    suspend fun markSeen(time: Int = now()) {
        context.notificationState.edit { it[SEEN_UP_TO] = time }
    }

    suspend fun markNotified(time: Int = now()) {
        context.notificationState.edit { it[NOTIFIED_UP_TO] = time }
    }
}

fun now(): Int = (System.currentTimeMillis() / 1000).toInt()
