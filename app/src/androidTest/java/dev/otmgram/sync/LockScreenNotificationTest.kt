package dev.otmgram.sync

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.otmgram.core.td.TdClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A locked phone shows how many messages arrived, never their text or who sent them. */
@RunWith(AndroidJUnit4::class)
class LockScreenNotificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val news = listOf(ChatNews("Anna", listOf("see you at 7, by the bridge", "bring the tent"), newest = 0))

    private fun show(withText: Boolean): Notification {
        val td = TdClient(context)
        val notifier = NewMessagesNotifier(context, td, ChatRepository(td), NotificationState(context), AppSettings(context))
        notifier.show(news, withText)
        return context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
    }

    private fun Notification.allText() =
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUMMARY_TEXT).joinToString(" ") { extras.get(it)?.toString().orEmpty() } +
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
        assertTrue(shown.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.any { it.contains("bridge") })
    }
}
