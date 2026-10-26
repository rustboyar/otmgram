package dev.otmgram.ui.chat

import android.location.Location
import dev.otmgram.core.location.LiveFailure
import dev.otmgram.core.location.LiveShare
import dev.otmgram.testing.TestStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatLocationTest {

    private val share = MutableStateFlow<LiveShare?>(null)
    private val failures = MutableSharedFlow<LiveFailure>(extraBufferCapacity = 1)
    private var fresh = CompletableDeferred<Location?>()
    private var lastKnown: Location? = null
    private val sent = mutableListOf<Location>()
    private val started = mutableListOf<Int>()
    private val notices = mutableListOf<String>()
    private val errors = mutableListOf<Exception>()

    private fun TestScope.location() = ChatLocation(
        backgroundScope,
        CHAT,
        current = { fresh.await() },
        lastKnown = { lastKnown },
        send = { sent += it },
        liveShare = share,
        liveFailures = failures,
        startSharing = { started += it },
        stopSharing = { share.value = null },
        strings = TestStrings,
        notify = { notices += it },
        onError = { errors += it },
    )

    @Test
    fun `sending waits for a fresh position, showing it's locating`() = runTest {
        val location = location()
        location.sendCurrent()
        runCurrent()
        assertTrue(location.locating)
        val here = Location("gps")
        fresh.complete(here)
        runCurrent()
        assertFalse(location.locating)
        assertSame(here, sent.single())
    }

    @Test
    fun `no position says so, and sends nothing`() = runTest {
        val location = location()
        fresh.complete(null)
        location.sendCurrent()
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(listOf("Couldn't get your location. Is location turned on?"), notices)
    }

    @Test
    fun `a failure stops locating and goes to onError`() = runTest {
        val location = location()
        fresh.completeExceptionally(IllegalStateException("no provider"))
        location.sendCurrent()
        runCurrent()
        assertFalse(location.locating)
        assertEquals("no provider", errors.single().message)
    }

    @Test
    fun `the sheet gets the last known position at once, then a fresh one`() = runTest {
        val old = Location("network")
        lastKnown = old
        val location = location()
        location.locate()
        assertSame(old, location.here)
        val now = Location("gps")
        fresh.complete(now)
        runCurrent()
        assertSame(now, location.here)
    }

    @Test
    fun `only this chat's live share shows here`() = runTest {
        share.value = LiveShare(OTHER_CHAT, "Other", endsAt = 0)
        val location = location()
        runCurrent()
        assertNull(location.live.value)
        location.startLive(900)
        share.value = LiveShare(CHAT, "This", endsAt = 0)
        runCurrent()
        assertEquals(listOf(900), started)
        assertEquals(CHAT, location.live.value?.chatId)
        location.stopLive()
        runCurrent()
        assertNull(location.live.value)
    }

    @Test
    fun `a live share that can't start says why, in its own chat only`() = runTest {
        location()
        runCurrent()
        failures.emit(LiveFailure(OTHER_CHAT, locationOff = false, reason = "elsewhere"))
        failures.emit(LiveFailure(CHAT, locationOff = false, reason = "PEER_ID_INVALID"))
        failures.emit(LiveFailure(CHAT, locationOff = true, reason = null))
        runCurrent()
        assertEquals(
            listOf("Couldn't share your live location: PEER_ID_INVALID", "Couldn't get your location. Is location turned on?"),
            notices,
        )
    }

    private companion object {
        const val CHAT = 1L
        const val OTHER_CHAT = 2L
    }
}
