package dev.otmgram.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The input bar: what's typed, and whether it goes as a new message, a reply or an edit.
 * Keeps the chat's draft (text, reply) in step with Telegram's, saving only changes; a draft
 * set aside while editing a message comes back after.
 */
class DraftComposer(
    private val scope: CoroutineScope,
    /** What was typed before the process was recreated. */
    initialText: String,
    /** Keeps what's typed across the process being recreated. */
    private val keep: (String) -> Unit,
    /** Sets the chat's draft on Telegram; null removes it. Must not suspend: also used as the chat goes away. */
    private val saveToTelegram: (TdApi.DraftMessage?) -> Unit,
    /** Sends [text], already trimmed, as [mode] says. */
    private val send: suspend (mode: Composer, text: String) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    /** What's typed; here, so searching, selecting or recording keeps it. */
    var text by mutableStateOf(initialText)
        private set

    var mode by mutableStateOf<Composer>(Composer.New)
        private set

    /** The draft set aside while editing a message, back once done. */
    private var draftBeforeEdit = ""

    /** The draft as Telegram has it (text, reply), so only changes are saved. */
    private var savedDraft: Pair<String, Long?> = "" to null

    fun type(value: String) {
        text = value
        if (mode !is Composer.Edit) keep(value)
    }

    /**
     * A draft from Telegram (typed here before, or on another device), unless something's
     * typed. Its reply comes back if [find] knows the message, i.e. it's loaded already.
     */
    fun restore(draft: TdApi.DraftMessage?, find: (messageId: Long) -> MessageItem?) {
        val draftText = (draft?.content as? TdApi.DraftMessageContentText)?.text?.text.orEmpty()
        val replyId = (draft?.replyTo as? TdApi.InputMessageReplyToMessage)?.messageId
        savedDraft = draftText to replyId
        if (text.isNotEmpty() || draftText.isEmpty()) return
        type(draftText)
        replyId?.let(find)?.let { mode = Composer.Reply(it) }
    }

    /** Saves what's typed as the chat's draft on Telegram, if it changed; on leaving the chat. */
    fun save() {
        val draftText = (if (mode is Composer.Edit) draftBeforeEdit else text).trim()
        val replyId = (mode as? Composer.Reply)?.message?.id?.takeIf { draftText.isNotEmpty() }
        if (draftText to replyId == savedDraft) return
        savedDraft = draftText to replyId
        val draft = draftText.takeIf { it.isNotEmpty() }?.let {
            TdApi.DraftMessage(
                replyId?.let { id -> TdApi.InputMessageReplyToMessage(id, null, 0, "") },
                0,
                TdApi.DraftMessageContentText(TdApi.FormattedText(it, emptyArray()), null),
                0,
                null,
            )
        }
        saveToTelegram(draft)
    }

    fun send() {
        val text = text
        val mode = mode
        // A caption may be removed by saving it empty; text messages can't be empty.
        val removingCaption = mode is Composer.Edit && mode.message.media != null
        if (text.isBlank() && !removingCaption) return
        this.mode = Composer.New
        type(if (mode is Composer.Edit) draftBeforeEdit else "")
        val left = this.text
        // Sending clears the draft on Telegram.
        if (mode !is Composer.Edit) savedDraft = "" to null
        scope.launch {
            try {
                send(mode, text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refused (an edit too late, say): the text back to try again, unless
                // something else was typed meanwhile. A message that fails later on its way
                // shows as failed instead.
                if (this@DraftComposer.mode == Composer.New && this@DraftComposer.text == left) {
                    if (mode is Composer.Edit) draftBeforeEdit = left
                    this@DraftComposer.mode = mode
                    type(text)
                }
                onError(e)
            }
        }
    }

    fun reply(message: MessageItem) {
        mode = Composer.Reply(message)
    }

    fun edit(message: MessageItem) {
        if (mode !is Composer.Edit) draftBeforeEdit = text
        mode = Composer.Edit(message)
        text = message.text
    }

    /** Back to a new message; after an edit, with the draft set aside for it. */
    fun cancel() {
        val wasEditing = mode is Composer.Edit
        mode = Composer.New
        if (wasEditing) type(draftBeforeEdit)
    }
}
