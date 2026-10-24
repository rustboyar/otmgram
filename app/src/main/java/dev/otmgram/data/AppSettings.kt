package dev.otmgram.data

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.otmgram.R
import dev.otmgram.di.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How often to check for new messages; [minutes] is null for never. */
enum class CheckInterval(val minutes: Long?, @StringRes val label: Int) {
    Minutes15(15, R.string.interval_15_min),
    Minutes30(30, R.string.interval_30_min),
    Hour1(60, R.string.interval_1_hour),
    Hours3(180, R.string.interval_3_hours),
    Never(null, R.string.interval_never),
}

/** How much downloaded media to keep; [bytes] is null for no limit. */
enum class CacheLimit(val bytes: Long?, @StringRes val label: Int) {
    Mb500(500_000_000, R.string.cache_500_mb),
    Gb1(1_000_000_000, R.string.cache_1_gb),
    Gb2(2_000_000_000, R.string.cache_2_gb),
    None(null, R.string.cache_no_limit),
}

private val CHECK_INTERVAL = stringPreferencesKey("check_interval")
private val COMPRESS = booleanPreferencesKey("compress_by_default")
private val CACHE_LIMIT = stringPreferencesKey("cache_limit")
private val MESSAGE_TEXT = booleanPreferencesKey("notification_message_text")
private val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")

/** Settings of the app on this phone; they stay across logins. */
@Singleton
class AppSettings @Inject constructor(
    @SettingsStore private val settings: DataStore<Preferences>,
) {
    val checkInterval: Flow<CheckInterval> = settings.data.map { prefs ->
        CheckInterval.entries.find { it.name == prefs[CHECK_INTERVAL] } ?: CheckInterval.Minutes15
    }

    /** Whether "Compress" starts on when sending photos and videos. */
    val compressByDefault: Flow<Boolean> = settings.data.map { it[COMPRESS] ?: true }

    val cacheLimit: Flow<CacheLimit> = settings.data.map { prefs ->
        CacheLimit.entries.find { it.name == prefs[CACHE_LIMIT] } ?: CacheLimit.Gb2
    }

    /** Whether notifications include message text; off keeps it off the lock screen. */
    val showMessageText: Flow<Boolean> = settings.data.map { it[MESSAGE_TEXT] ?: false }

    /** Whether the app has asked for the notification permission; it asks once. */
    val notificationsAsked: Flow<Boolean> = settings.data.map { it[NOTIFICATIONS_ASKED] ?: false }

    suspend fun setNotificationsAsked() {
        settings.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    suspend fun setShowMessageText(show: Boolean) {
        settings.edit { it[MESSAGE_TEXT] = show }
    }

    suspend fun setCheckInterval(interval: CheckInterval) {
        settings.edit { it[CHECK_INTERVAL] = interval.name }
    }

    suspend fun setCompressByDefault(compress: Boolean) {
        settings.edit { it[COMPRESS] = compress }
    }

    suspend fun setCacheLimit(limit: CacheLimit) {
        settings.edit { it[CACHE_LIMIT] = limit.name }
    }
}
