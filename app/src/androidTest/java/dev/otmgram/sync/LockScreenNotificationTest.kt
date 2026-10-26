package dev.otmgram.sync

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.otmgram.core.AndroidStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A locked phone shows how many messages arrived, never their text or who sent them. */
@RunWith(AndroidJUnit4::class)
class LockScreenNotificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun chat(withText: Boolean) = ChatNotification(
        chatId = 1,
        title = "Anna",
        isGroup = false,
        messages = listOf(NotifiedMessage(1, "Anna", "see you at 7, by the bridge", 0), NotifiedMessage(2, "Anna", "bring the tent", 0)),
        photoPath = null,
        withText = withText,
    )

    private fun show(withText: Boolean): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Android updates its list of shown notifications asynchronously: without waiting, a
        // test could read the one the previous test left.
        manager.cancelAll()
        waitFor { manager.activeNotifications.isEmpty() }
        MessagesNotification(context, AndroidStrings(context)).show(chat(withText), alert = false)
        waitFor { manager.activeNotifications.any { it.tag == "1" } }
        return manager.activeNotifications.single { it.tag == "1" }.notification
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    private fun Notification.allText() =
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUMMARY_TEXT).joinToString(" ") { extras.getCharSequence(it)?.toString().orEmpty() } +
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ").orEmpty()

    @Test
    fun byDefaultNoMessageTextAtAll() {
        val shown = show(withText = false)
        assertFalse(shown.allText().contains("bridge"))
        assertEquals("Anna", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("2 new messages", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun withTextTheLockedVersionHasNoTextOrNames() {
        val shown = show(withText = true)
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        val locked = checkNotNull(shown.publicVersion)
        val lockedText = locked.allText()
        assertEquals("2 new messages", locked.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertFalse(lockedText.contains("bridge"))
        assertFalse(lockedText.contains("Anna"))
        // Unlocked, the messages are there.
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown)!!.messages
        assertTrue(messages.any { it.text.toString().contains("bridge") })
    }
}
