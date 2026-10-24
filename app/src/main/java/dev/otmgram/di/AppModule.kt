package dev.otmgram.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.otmgram.core.AndroidStrings
import dev.otmgram.core.Strings
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Scope for work that lives as long as the app process (on a background thread). Screens
 * use it, on the main thread, for work that must finish when they're left: sending,
 * saving. See [onMain].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class TelegramModule {

    @Binds
    abstract fun telegramClient(td: TdClient): TelegramClient

    @Binds
    abstract fun strings(strings: AndroidStrings): Strings
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** This scope on the main thread. */
fun CoroutineScope.onMain(): CoroutineScope = this + Dispatchers.Main.immediate
