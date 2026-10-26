package dev.otmgram.ui.chat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.otmgram.ui.theme.OtmgramTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var deleted: Boolean? = null

    private fun show(forSelf: Boolean, forEveryone: Boolean, count: Int = 1) = compose.setContent {
        OtmgramTheme {
            DeleteDialog(
                DeleteRequest(List(count) { it.toLong() }, quote = null, canDeleteForSelf = forSelf, canDeleteForEveryone = forEveryone),
                onDelete = { deleted = it },
                onDismiss = {},
            )
        }
    }

    @Test
    fun forEveryoneIsOnByDefaultAndCanBeTurnedOff() {
        show(forSelf = true, forEveryone = true)
        compose.onNodeWithTag("forEveryone").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(false, deleted)
    }

    @Test
    fun onlyForEveryoneWhenThatsAllThatsAllowed() {
        show(forSelf = false, forEveryone = true)
        compose.onNodeWithTag("forEveryone").assertDoesNotExist()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }

    @Test
    fun countsSeveralMessages() {
        show(forSelf = true, forEveryone = false, count = 3)
        compose.onNodeWithText("Delete 3 messages?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(false, deleted)
    }
}
