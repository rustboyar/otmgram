package dev.otmgram.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How often to check for new messages; [minutes] is null for never. */
enum class CheckInterval(val minutes: Long?, val label: String) {
    Minutes15(15, "15 min"),
    Minutes30(30, "30 min"),
    Hour1(60, "1 hour"),
    Hours3(180, "3 hours"),
    Never(null, "Never"),
}

/** How much downloaded media to keep; [bytes] is null for no limit. */
enum class CacheLimit(val bytes: Long?, val label: String) {
    Mb500(500_000_000, "500 MB"),
    Gb1(1_000_000_000, "1 GB"),
    Gb2(2_000_000_000, "2 GB"),
    None(null, "No limit"),
}

private val CHECK_INTERVAL = stringPreferencesKey("check_interval")
private val COMPRESS = booleanPreferencesKey("compress_by_default")
private val CACHE_LIMIT = stringPreferencesKey("cache_limit")
private val MESSAGE_TEXT = booleanPreferencesKey("notification_message_text")

/** Settings of the app on this phone; they stay across logins. */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val checkInterval: Flow<CheckInterval> = context.settings.data.map { prefs ->
        CheckInterval.entries.find { it.name == prefs[CHECK_INTERVAL] } ?: CheckInterval.Minutes15
    }

    /** Whether "Compress" starts on when sending photos and videos. */
    val compressByDefault: Flow<Boolean> = context.settings.data.map { it[COMPRESS] ?: true }

    val cacheLimit: Flow<CacheLimit> = context.settings.data.map { prefs ->
        CacheLimit.entries.find { it.name == prefs[CACHE_LIMIT] } ?: CacheLimit.Gb2
    }

    /** Whether notifications include message text; off keeps it off the lock screen. */
    val showMessageText: Flow<Boolean> = context.settings.data.map { it[MESSAGE_TEXT] ?: false }

    suspend fun setShowMessageText(show: Boolean) {
        context.settings.edit { it[MESSAGE_TEXT] = show }
    }

    suspend fun setCheckInterval(interval: CheckInterval) {
        context.settings.edit { it[CHECK_INTERVAL] = interval.name }
    }

    suspend fun setCompressByDefault(compress: Boolean) {
        context.settings.edit { it[COMPRESS] = compress }
    }

    suspend fun setCacheLimit(limit: CacheLimit) {
        context.settings.edit { it[CACHE_LIMIT] = limit.name }
    }
}
