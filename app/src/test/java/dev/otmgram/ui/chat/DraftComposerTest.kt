package dev.otmgram.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftComposerTest {

    private val kept = mutableListOf<String>()
    private val savedToTelegram = mutableListOf<TdApi.DraftMessage?>()
    private val sent = mutableListOf<Pair<Composer, String>>()
    private val errors = mutableListOf<Exception>()
    private var refuse = false

    private fun TestScope.composer(initial: String = "") = DraftComposer(
        backgroundScope,
        initialText = initial,
        keep = { kept += it },
        saveToTelegram = { savedToTelegram += it },
        send = { mode, text, _ ->
            if (refuse) error("MESSAGE_EDIT_TIME_EXPIRED")
            sent += mode to text
        },
        onError = { errors += it },
    )

    private fun item(id: Long, text: String, media: MessageMedia? = null) = MessageItem(
        id = id, isOutgoing = true, isService = false, senderName = null, forwardedFrom = null, replyTo = null,
        text = text, media = media, isMediaPlaceholder = false, date = 0, isEdited = false, status = null, reactions = emptyList(),
    )

    private fun telegramDraft(text: String, replyTo: Long? = null) = TdApi.DraftMessage(
        replyTo?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }, 0,
        TdApi.DraftMessageContentText(TdApi.FormattedText(text, emptyArray()), null), 0, null,
    )

    @Test
    fun `what's typed is kept, except an edit's text`() = runTest {
        val composer = composer()
        composer.type("hel")
        composer.edit(item(5, "old"))
        composer.type("new")
        assertEquals(listOf("hel"), kept)
    }

    @Test
    fun `an edit sets the draft aside, and cancelling brings it back`() = runTest {
        val composer = composer()
        composer.type("half a thought")
        composer.edit(item(5, "old text"))
        assertEquals("old text", composer.text)
        composer.cancel()
        assertEquals(Composer.New, composer.mode)
        assertEquals("half a thought", composer.text)
    }

    @Test
    fun `sending trims, clears, and sends as the mode says`() = runTest {
        val composer = composer()
        val replied = item(7, "question")
        composer.reply(replied)
        composer.type("  answer ")
        composer.send()
        runCurrent()
        assertEquals(listOf(Composer.Reply(replied) to "answer"), sent)
        assertEquals("", composer.text)
        assertEquals(Composer.New, composer.mode)
    }

    @Test
    fun `nothing is sent blank, but a caption can be removed`() = runTest {
        val composer = composer()
        composer.type("   ")
        composer.send()
        composer.edit(item(5, "caption", media = MessageMedia.Photo(1, 10, 10, null)))
        composer.type("")
        composer.send()
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals("", sent.single().second)
    }

    @Test
    fun `a refused edit gives the text and the edit back, with the draft still set aside`() = runTest {
        val composer = composer()
        composer.type("draft")
        val message = item(5, "old")
        composer.edit(message)
        composer.type("too late")
        refuse = true
        composer.send()
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(Composer.Edit(message), composer.mode)
        assertEquals("too late", composer.text)
        composer.cancel()
        assertEquals("draft", composer.text)
    }

    @Test
    fun `a refusal doesn't overwrite what was typed meanwhile`() = runTest {
        val composer = composer()
        composer.type("first")
        refuse = true
        composer.send()
        composer.type("second")
        runCurrent()
        assertEquals("second", composer.text)
    }

    @Test
    fun `the draft goes to Telegram only when it changed, with its reply`() = runTest {
        val composer = composer()
        composer.save()
        assertTrue(savedToTelegram.isEmpty())
        val replied = item(7, "question")
        composer.reply(replied)
        composer.type("later ")
        composer.save()
        composer.save()
        val draft = savedToTelegram.single()
        assertEquals("later", (draft?.content as TdApi.DraftMessageContentText).text.text)
        assertEquals(7L, (draft.replyTo as TdApi.InputMessageReplyToMessage).messageId)
    }

    @Test
    fun `while editing, the draft saved is the one set aside`() = runTest {
        val composer = composer()
        composer.type("draft")
        composer.edit(item(5, "old"))
        composer.save()
        assertEquals("draft", (savedToTelegram.single()?.content as TdApi.DraftMessageContentText).text.text)
    }

    @Test
    fun `Telegram's draft comes back only if nothing's typed, with its reply if loaded`() = runTest {
        val replied = item(7, "question")
        val empty = composer()
        empty.restore(telegramDraft("from the phone", replyTo = 7)) { if (it == 7L) replied else null }
        assertEquals("from the phone", empty.text)
        assertEquals(Composer.Reply(replied), empty.mode)

        val typed = composer(initial = "typed here")
        typed.restore(telegramDraft("from the phone")) { null }
        assertEquals("typed here", typed.text)
        // Restored as Telegram has it: saving it again sends nothing.
        empty.save()
        assertTrue(savedToTelegram.isEmpty())
    }

    @Test
    fun `sending forgets the draft on Telegram, so it isn't saved again`() = runTest {
        val composer = composer()
        composer.restore(telegramDraft("from the phone")) { null }
        composer.send()
        runCurrent()
        composer.save()
        assertTrue(savedToTelegram.isEmpty())
        assertNull(errors.firstOrNull())
    }
}
