package dev.otmgram.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.otmgram.di.NotificationStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val SEEN = stringPreferencesKey("seen_messages")
private val ALERTED = intPreferencesKey("alerted_notification")

/** Kept by earlier versions, which decided what to notify themselves. */
private val OLD_NOTIFIED = stringPreferencesKey("notified_messages")

/**
 * What the user has already seen in the app: per chat, the newest message id; and the
 * newest of TDLib's notifications that alerted.
 * Ids only grow within a chat, so a message that arrives late is still newer, whatever its
 * date or the phone's clock.
 */
@Singleton
class NotificationState @Inject constructor(
    @NotificationStore private val store: DataStore<Preferences>,
) {
    /** Newest message per chat that was in the app while it was open; null if never recorded. */
    suspend fun seen(): Map<Long, Long>? = store.data.first()[SEEN]?.let(::decode)

    /** Records [messages] as seen; only ever moves forward. */
    suspend fun markSeen(messages: Map<Long, Long>) = merge(SEEN, messages)

    /**
     * The newest notification that alerted, by TDLib's id; those only grow, and persist
     * like TDLib's database. 0 if none yet.
     */
    suspend fun alertedUpTo(): Int = store.data.first()[ALERTED] ?: 0

    suspend fun setAlertedUpTo(id: Int) {
        store.edit { it[ALERTED] = maxOf(it[ALERTED] ?: 0, id) }
    }

    /** Forgets everything, e.g. the chats of an account logged out of. */
    suspend fun clear() {
        store.edit { it.clear() }
    }

    private suspend fun merge(key: Preferences.Key<String>, messages: Map<Long, Long>) {
        store.edit { prefs ->
            prefs.remove(OLD_NOTIFIED)
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
