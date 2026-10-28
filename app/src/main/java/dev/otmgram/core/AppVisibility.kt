package dev.otmgram.core

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app is on screen. The process now lives for days in the background (Instant
 * notifications): work only the screen needs, an open chat's and the chat list's, waits
 * while it isn't.
 */
@Singleton
class AppVisibility @Inject constructor() {
    val visible: Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .distinctUntilChanged()
}
