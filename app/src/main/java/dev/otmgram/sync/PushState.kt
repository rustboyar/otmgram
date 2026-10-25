package dev.otmgram.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.otmgram.di.NotificationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val ENDPOINT = stringPreferencesKey("push_endpoint")
private val REGISTERED_AT = longPreferencesKey("push_registered_at")
private val FAILED = stringPreferencesKey("push_failed")
private val LAST_PUSH_AT = longPreferencesKey("push_last_at")

/** How push stands, for the settings. Times are milliseconds since the epoch. */
data class PushStatus(
    /** The push app gave an endpoint. */
    val hasEndpoint: Boolean,
    /** When Telegram was last told the endpoint; null if not yet. */
    val registeredAt: Long?,
    /** Why the push app couldn't register, if it couldn't. */
    val failed: String?,
    val lastPushAt: Long?,
)

/**
 * The push registration of the logged-in account. It shares the notification marks' store,
 * so logging out forgets it too. The endpoint is a secret of sorts (anyone with it can
 * wake the app): it's never logged.
 */
@Singleton
class PushState @Inject constructor(
    @NotificationStore private val store: DataStore<Preferences>,
) {
    val status: Flow<PushStatus> = store.data.map { prefs ->
        PushStatus(prefs[ENDPOINT] != null, prefs[REGISTERED_AT], prefs[FAILED], prefs[LAST_PUSH_AT])
    }

    /** Where the push app receives pushes for this app. */
    suspend fun endpoint(): String? = store.data.first()[ENDPOINT]

    suspend fun registeredAt(): Long? = store.data.first()[REGISTERED_AT]

    /** A new endpoint; Telegram needs to be told again if it changed. */
    suspend fun setEndpoint(url: String) {
        store.edit { prefs ->
            if (prefs[ENDPOINT] != url) prefs.remove(REGISTERED_AT)
            prefs[ENDPOINT] = url
            prefs.remove(FAILED)
        }
    }

    suspend fun setRegistered(at: Long) {
        store.edit { it[REGISTERED_AT] = at }
    }

    suspend fun setFailed(reason: String) {
        store.edit { it[FAILED] = reason }
    }

    suspend fun setLastPush(at: Long) {
        store.edit { it[LAST_PUSH_AT] = at }
    }

    suspend fun clear() {
        store.edit { prefs -> listOf(ENDPOINT, REGISTERED_AT, FAILED, LAST_PUSH_AT).forEach { prefs.remove(it) } }
    }
}
