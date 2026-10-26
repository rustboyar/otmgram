package dev.otmgram.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `the quality carries on from Compress, on or off`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { dir.newFile("settings.preferences_pb").also { it.delete() } }
        val settings = AppSettings(store)
        assertEquals(SendQuality.HD, settings.sendQuality.first())
        store.edit { it[booleanPreferencesKey("compress_by_default")] = false }
        assertEquals(SendQuality.Original, settings.sendQuality.first())
        settings.setSendQuality(SendQuality.SD)
        assertEquals(SendQuality.SD, settings.sendQuality.first())
    }

    @Test
    fun `instant by push carries on as instant`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { dir.newFile("settings.preferences_pb").also { it.delete() } }
        val settings = AppSettings(store)
        assertEquals(NotificationMode.Interval, settings.notificationMode.first())
        store.edit { it[stringPreferencesKey("notification_mode")] = "Push" }
        assertEquals(NotificationMode.Instant, settings.notificationMode.first())
        store.edit { it[stringPreferencesKey("notification_mode")] = "" }
        store.edit { it[stringPreferencesKey("check_interval")] = "Never" }
        assertEquals(NotificationMode.Off, settings.notificationMode.first())
    }
}
