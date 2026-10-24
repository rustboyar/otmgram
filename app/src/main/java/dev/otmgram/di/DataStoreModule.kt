package dev.otmgram.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/** The app's settings file, shared by the repositories that keep their settings in it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

/** What was seen and notified about, per chat (sync/NotificationState). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotificationStore

private val Context.settings by preferencesDataStore("settings")
private val Context.notificationState by preferencesDataStore("notifications")

/** The stores as injected values, so tests can use files of their own. */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @SettingsStore
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settings

    @Provides
    @NotificationStore
    fun notifications(@ApplicationContext context: Context): DataStore<Preferences> = context.notificationState
}
