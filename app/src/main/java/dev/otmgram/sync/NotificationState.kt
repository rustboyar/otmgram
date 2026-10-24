package dev.otmgram.sync

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationState by preferencesDataStore("notifications")
private val SEEN = stringPreferencesKey("seen_messages")
private val NOTIFIED = stringPreferencesKey("notified_messages")

/**
 * What the user has already seen or been notified about: per chat, the newest message id.
 * Ids only grow within a chat, so a message that arrives late is still newer, whatever its
 * date or the phone's clock.
 */
@Singleton
class NotificationState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Newest message per chat that was in the app while it was open; null if never recorded. */
    suspend fun seen(): Map<Long, Long>? = context.notificationState.data.first()[SEEN]?.let(::decode)

    /** Newest message per chat that a notification already included. */
    suspend fun notified(): Map<Long, Long> = context.notificationState.data.first()[NOTIFIED]?.let(::decode).orEmpty()

    /** Records [messages] as seen; only ever moves forward. */
    suspend fun markSeen(messages: Map<Long, Long>) = merge(SEEN, messages)

    suspend fun markNotified(messages: Map<Long, Long>) = merge(NOTIFIED, messages)

    /** Forgets what was notified, so the next check notifies again (for testing). */
    suspend fun forgetNotified() {
        context.notificationState.edit { it.remove(NOTIFIED) }
    }

    private suspend fun merge(key: Preferences.Key<String>, messages: Map<Long, Long>) {
        context.notificationState.edit { prefs ->
            val merged = prefs[key]?.let(::decode).orEmpty().toMutableMap()
            messages.forEach { (chat, id) -> merged[chat] = maxOf(merged[chat] ?: 0, id) }
            prefs[key] = encode(merged)
        }
    }
}

internal fun encode(messages: Map<Long, Long>): String = messages.entries.joinToString(",") { "${it.key}:${it.value}" }

internal fun decode(text: String): Map<Long, Long> = text.split(',').filter { it.isNotEmpty() }.associate {
    val (chat, id) = it.split(':')
    chat.toLong() to id.toLong()
}
