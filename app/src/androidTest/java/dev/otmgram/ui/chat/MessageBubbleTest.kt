package dev.otmgram.ui.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.otmgram.ui.theme.OtmgramTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bubble states as the user sees them: delivery, failure, selection. */
@RunWith(AndroidJUnit4::class)
class MessageBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(status: MessageStatus? = MessageStatus.Sent, text: String = "see you all on saturday?") = MessageItem(
        id = 1, isOutgoing = status != null, isService = false, senderName = null, forwardedFrom = null, replyTo = null,
        text = text, media = null, isMediaPlaceholder = false, date = 1_792_756_800, isEdited = false, status = status, reactions = emptyList(),
    )

    private fun show(
        message: MessageItem,
        selecting: Boolean = false,
        selected: Boolean = false,
        onToggle: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onMenu: () -> Unit = {},
        onSelect: () -> Unit = {},
    ) = compose.setContent {
        OtmgramTheme {
            MessageBubble(
                message, audio = { null }, onClick = onMenu, onLongClick = onSelect, onReactionClick = {}, onOpenMedia = {}, onPlayVoice = VoiceActions.None,
                onVideoNoteSound = {}, selecting = selecting, selected = selected, onToggleSelected = onToggle,
                onRetry = onRetry, onDiscard = onDiscard,
            )
        }
    }

    @Test
    fun aTapOpensTheMenuAndALongPressSelects() {
        var menus = 0
        var selects = 0
        show(item(), onMenu = { menus++ }, onSelect = { selects++ })
        val node = compose.onNodeWithTag("message-1")
        assertEquals("Message actions", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertEquals("Select", node.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        node.performClick()
        node.performTouchInput { longClick() }
        assertEquals(1, menus)
        assertEquals(1, selects)
    }

    @Test
    fun deliveryMarks() {
        show(item(MessageStatus.Read))
        compose.onNodeWithContentDescription("Read").assertIsDisplayed()
    }

    @Test
    fun sendingShowsAClock() {
        show(item(MessageStatus.Sending))
        compose.onNodeWithContentDescription("Sending").assertIsDisplayed()
    }

    @Test
    fun failedMessageOffersRetryAndDelete() {
        var retried = 0
        var discarded = 0
        show(item(MessageStatus.Failed), onRetry = { retried++ }, onDiscard = { discarded++ })
        compose.onNodeWithText("Not sent.").assertIsDisplayed()
        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("discard").performClick()
        assertEquals(1, retried)
        assertEquals(1, discarded)
    }

    @Test
    fun selectingTogglesOnTap() {
        var toggled = 0
        show(item(), selecting = true, selected = true, onToggle = { toggled++ })
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        compose.onNodeWithText("see you all on saturday?").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun noRetryWhileSelecting() {
        show(item(MessageStatus.Failed), selecting = true)
        compose.onNodeWithTag("retry").assertDoesNotExist()
    }
}
