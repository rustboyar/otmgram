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

/** How new messages reach the phone, for notifications. */
enum class NotificationMode(@StringRes val label: Int) {
    /** Telegram pushes them through a push app (UnifiedPush); a check every hour backs it up. */
    Push(R.string.mode_push),

    /** The app checks every [CheckInterval]. */
    Interval(R.string.mode_interval),
    Off(R.string.mode_off),
}

/** How often to check for new messages, without push. */
enum class CheckInterval(val minutes: Long, @StringRes val label: Int) {
    Minutes15(15, R.string.interval_15_min),
    Minutes30(30, R.string.interval_30_min),
    Hour1(60, R.string.interval_1_hour),
    Hours3(180, R.string.interval_3_hours),
}

/** How much downloaded media to keep; [bytes] is null for no limit. */
enum class CacheLimit(val bytes: Long?, @StringRes val label: Int) {
    Mb500(500_000_000, R.string.cache_500_mb),
    Gb1(1_000_000_000, R.string.cache_1_gb),
    Gb2(2_000_000_000, R.string.cache_2_gb),
    None(null, R.string.cache_no_limit),
}

/** How picked photos and videos are sent: made smaller ([SD], [HD]) or as they are. */
enum class SendQuality(@StringRes val label: Int) {
    SD(R.string.quality_sd),
    HD(R.string.quality_hd),
    Original(R.string.quality_original),
}

private val NOTIFICATION_MODE = stringPreferencesKey("notification_mode")
private val CHECK_INTERVAL = stringPreferencesKey("check_interval")
private val COMPRESS = booleanPreferencesKey("compress_by_default")
private val SEND_QUALITY = stringPreferencesKey("send_quality")
private val CACHE_LIMIT = stringPreferencesKey("cache_limit")
private val MESSAGE_TEXT = booleanPreferencesKey("notification_message_text")
private val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")

/** Settings of the app on this phone; they stay across logins. */
@Singleton
class AppSettings @Inject constructor(
    @SettingsStore private val settings: DataStore<Preferences>,
) {
    /** Earlier versions had only intervals, "Never" among them. */
    val notificationMode: Flow<NotificationMode> = settings.data.map { prefs ->
        NotificationMode.entries.find { it.name == prefs[NOTIFICATION_MODE] }
            ?: if (prefs[CHECK_INTERVAL] == "Never") NotificationMode.Off else NotificationMode.Interval
    }

    val checkInterval: Flow<CheckInterval> = settings.data.map { prefs ->
        CheckInterval.entries.find { it.name == prefs[CHECK_INTERVAL] } ?: CheckInterval.Minutes15
    }

    /** The quality the send preview starts with. Earlier versions had only Compress, on or off. */
    val sendQuality: Flow<SendQuality> = settings.data.map { prefs ->
        SendQuality.entries.find { it.name == prefs[SEND_QUALITY] }
            ?: if (prefs[COMPRESS] == false) SendQuality.Original else SendQuality.HD
    }

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

    suspend fun setNotificationMode(mode: NotificationMode) {
        settings.edit { it[NOTIFICATION_MODE] = mode.name }
    }

    suspend fun setCheckInterval(interval: CheckInterval) {
        settings.edit { it[CHECK_INTERVAL] = interval.name }
    }

    suspend fun setSendQuality(quality: SendQuality) {
        settings.edit { it[SEND_QUALITY] = quality.name }
    }

    suspend fun setCacheLimit(limit: CacheLimit) {
        settings.edit { it[CACHE_LIMIT] = limit.name }
    }
}
