package dev.otmgram.ui.chat

import dev.otmgram.data.SendQuality
import dev.otmgram.data.SizeEstimate
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of an open chat that hold their own state: search, selection, stickers, sending. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPartsTest {

    private fun item(id: Long, text: String = "m$id", sender: String? = null, service: Boolean = false) = MessageItem(
        id = id, isOutgoing = false, isService = service, senderName = sender, forwardedFrom = null, replyTo = null,
        text = text, media = null, isMediaPlaceholder = false, date = 0, isEdited = false, status = null, reactions = emptyList(),
    )

    private fun result(id: Long) = SearchResult(id, null, "m$id", 0)

    // Search

    @Test
    fun `search waits for typing to stop, then shows the newest match`() = runTest {
        val queries = mutableListOf<String>()
        val shown = mutableListOf<Long>()
        val search = ChatSearch(backgroundScope, { queries += it; listOf(result(3), result(2), result(1)) }, { shown += it }, {})
        search.open()
        search.type("sa")
        advanceTimeBy(100)
        search.type("saturday ")
        advanceTimeBy(ChatSearch.DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("saturday"), queries)
        assertEquals(listOf(3L), shown)
    }

    @Test
    fun `stepping goes older and newer within the matches`() = runTest {
        val shown = mutableListOf<Long>()
        val search = ChatSearch(backgroundScope, { listOf(result(3), result(2)) }, { shown += it }, {})
        search.type("x")
        advanceTimeBy(ChatSearch.DELAY_MS + 1)
        runCurrent()
        search.step(1)
        search.step(1) // Already the oldest.
        search.step(-1)
        assertEquals(listOf(3L, 2L, 3L), shown)
    }

    @Test
    fun `closing forgets query and results`() = runTest {
        val search = ChatSearch(backgroundScope, { listOf(result(1)) }, {}, {})
        search.type("x")
        advanceTimeBy(ChatSearch.DELAY_MS + 1)
        runCurrent()
        search.close()
        assertFalse(search.isOpen)
        assertNull(search.results)
    }

    @Test
    fun `a failed search is reported, not thrown, and shows no matches`() = runTest {
        val errors = mutableListOf<Exception>()
        val search = ChatSearch(backgroundScope, { error("offline") }, {}, { errors += it })
        search.type("x")
        advanceTimeBy(ChatSearch.DELAY_MS + 1)
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(emptyList<SearchResult>(), search.results)
    }

    // Pinned messages

    @Test
    fun `the pinned bar goes from the newest to older ones, then around`() = runTest {
        val shown = mutableListOf<Long>()
        val pinned = PinnedMessages(backgroundScope, { listOf(result(3), result(2), result(1)) }, emptyFlow(), { shown += it }, {})
        runCurrent()
        repeat(4) { pinned.showNext() }
        assertEquals(listOf(3L, 2L, 1L, 3L), shown)
    }

    @Test
    fun `picking one from the list goes there, and the bar names the next older`() = runTest {
        val shown = mutableListOf<Long>()
        val pinned = PinnedMessages(backgroundScope, { listOf(result(3), result(2), result(1)) }, emptyFlow(), { shown += it }, {})
        runCurrent()
        pinned.openList()
        pinned.goTo(2)
        assertFalse(pinned.listOpen)
        assertEquals(listOf(2L), shown)
        assertEquals(2, pinned.shown)
    }

    @Test
    fun `fewer pinned after a change keep the bar on one of them`() = runTest {
        var list = listOf(result(3), result(2), result(1))
        val changes = MutableSharedFlow<Unit>()
        val pinned = PinnedMessages(backgroundScope, { list }, changes, {}, {})
        runCurrent()
        repeat(2) { pinned.showNext() }
        list = listOf(result(3))
        changes.emit(Unit)
        runCurrent()
        assertEquals(0, pinned.shown)
    }

    @Test
    fun `a failed load is reported and keeps what was there`() = runTest {
        var fail = false
        val changes = MutableSharedFlow<Unit>()
        val errors = mutableListOf<Exception>()
        val pinned = PinnedMessages(backgroundScope, { if (fail) error("offline") else listOf(result(1)) }, changes, {}, { errors += it })
        runCurrent()
        fail = true
        changes.emit(Unit)
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(listOf(1L), pinned.messages.map { it.messageId })
    }

    // Selection

    private fun allowed(forSelf: Boolean, forEveryone: Boolean) = TdApi.MessageProperties().apply {
        canBeDeletedOnlyForSelf = forSelf
        canBeDeletedForAllUsers = forEveryone
    }

    @Test
    fun `selection toggles, but not service notes`() {
        val selection = ChatSelection({ allowed(true, true) }, { emptyList() })
        selection.start(item(1))
        selection.toggle(item(2))
        selection.toggle(item(1))
        selection.toggle(item(3, service = true))
        assertEquals(setOf(2L), selection.selected)
    }

    @Test
    fun `copied text is in chat order, with senders`() {
        val messages = listOf(item(3, "third", "Lea"), item(2, "second"), item(1, "first", "Anna"))
        val selection = ChatSelection({ allowed(true, true) }, { messages })
        selection.start(messages[0])
        selection.toggle(messages[2])
        assertEquals("Anna: first\n\nLea: third", selection.text())
    }

    @Test
    fun `deleting a selection keeps only what may be deleted`() = runTest {
        val rules = mapOf(1L to allowed(true, true), 2L to allowed(false, false), 3L to allowed(true, false))
        val selection = ChatSelection({ rules.getValue(it) }, { emptyList() })
        listOf(1L, 2L, 3L).forEach { selection.toggle(item(it)) }
        val request = selection.deleteRequest()!!
        assertEquals(listOf(1L, 3L), request.messageIds)
        assertTrue(request.canDeleteForSelf)
        assertFalse(request.canDeleteForEveryone) // Not allowed for message 3.
    }

    @Test
    fun `nothing deletable means no request`() = runTest {
        val selection = ChatSelection({ allowed(false, false) }, { emptyList() })
        selection.start(item(1))
        assertNull(selection.deleteRequest())
    }

    // Stickers

    @Test
    fun `the sticker panel opens on its first tab and closes again`() = runTest {
        val recent = StickerTab(null, "Recent")
        val panel = StickerPanel(backgroundScope, { listOf(recent, StickerTab(5, "Frogs")) }, { emptyList() }, {})
        panel.toggle()
        runCurrent()
        assertTrue(panel.isOpen)
        assertEquals(recent, panel.selected)
        assertEquals(emptyList<Sticker>(), panel.stickers)
        panel.toggle()
        assertFalse(panel.isOpen)
    }

    // Sending

    @Test
    fun `an album gets the caption on its first item only`() = runTest {
        val captions = mutableListOf<String>()
        var sent = 0
        val queue = OutgoingQueue<String>(
            backgroundScope,
            backgroundScope,
            estimate = { SizeEstimate(10, 3, 5) },
            prepareMedia = { _, caption, _ -> captions += caption; TdApi.InputMessageText() },
            prepareFile = { _, _ -> TdApi.InputMessageText() },
            send = { sent += it.size },
            onError = {},
        )
        queue.pickMedia(listOf("a", "b", "c"))
        runCurrent()
        assertEquals(SizeEstimate(10, 3, 5), queue.sizes)
        queue.sendMedia("the view", SendQuality.HD)
        assertTrue(queue.preparing)
        runCurrent()
        assertEquals(listOf("the view", "", ""), captions)
        assertEquals(3, sent)
        assertFalse(queue.preparing)
        assertTrue(queue.media.isEmpty())
    }

    @Test
    fun `a failed compression is reported and stops preparing`() = runTest {
        val errors = mutableListOf<Exception>()
        val queue = OutgoingQueue<String>(
            backgroundScope, backgroundScope, { SizeEstimate(0, 0, 0) }, { _, _, _ -> error("decoder failed") }, { _, _ -> TdApi.InputMessageText() }, {}, { errors += it },
        )
        queue.pickMedia(listOf("a"))
        queue.sendMedia("", SendQuality.HD)
        runCurrent()
        assertEquals(1, errors.size)
        assertFalse(queue.preparing)
    }
}
