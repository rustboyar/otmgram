package dev.otmgram.sync

import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/** What the push app tells: endpoints, pushes and failures; [PushRegistration] handles them. */
@AndroidEntryPoint
class PushEventService : PushService() {

    @Inject lateinit var push: PushRegistration

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        scope.launch { push.onNewEndpoint(endpoint) }
    }

    override fun onMessage(message: PushMessage, instance: String) = push.onMessage()

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        scope.launch { push.onRegistrationFailed(reason) }
    }

    override fun onUnregistered(instance: String) {
        scope.launch { push.onUnregistered() }
    }
}
