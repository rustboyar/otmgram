package dev.otmgram.core.td

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the local database may be deleted, and when the key file may. */
class DatabaseOpenerTest {

    private val key = byteArrayOf(1, 2, 3)
    private var stored: StoredKey = StoredKey.Loaded(key)
    private var keyDeleted = false
    private var destroyed = false
    private val sent = mutableListOf<ByteArray?>()
    private val retries = mutableListOf<Long>()

    /** What TDLib answers: an error code for a given key (null: no key), or null for success. */
    private var answer: (ByteArray?) -> Int? = { null }

    private val opener = DatabaseOpener(
        load = { stored },
        deleteKey = { keyDeleted = true },
        send = { key, done -> sent += key; done(answer(key)?.let { TdApi.Error(it, "error $it") }) },
        destroy = { destroyed = true },
        later = { delayMs, _ -> retries += delayMs },
    )

    @Test
    fun `opens with the key`() {
        opener.open()
        assertEquals(listOf(key.toList()), sent.map { it?.toList() })
        assertFalse(keyDeleted || destroyed)
    }

    @Test
    fun `a key the database doesn't use is dropped once it opens without`() {
        answer = { if (it != null) 401 else null }
        opener.open()
        assertEquals(2, sent.size)
        assertTrue(keyDeleted)
        assertFalse(destroyed)
    }

    @Test
    fun `other errors keep the key and try again later`() {
        answer = { 400 } // E.g. a full disk.
        opener.open()
        assertEquals(1, sent.size)
        assertFalse(keyDeleted || destroyed)
        assertEquals(listOf(DatabaseOpener.RETRY_FIRST_MS), retries)
    }

    @Test
    fun `a busy Keystore is retried, never opened without the key`() {
        stored = StoredKey.Unavailable(RuntimeException("busy"))
        opener.open()
        assertTrue(sent.isEmpty())
        assertFalse(keyDeleted || destroyed)
        assertEquals(1, retries.size)
    }

    @Test
    fun `an encrypted database without its key starts over`() {
        stored = StoredKey.None
        answer = { 401 }
        opener.open()
        assertTrue(destroyed)
    }

    @Test
    fun `a key file that can't be decrypted is dropped if the database isn't encrypted`() {
        stored = StoredKey.Lost
        opener.open()
        assertEquals(listOf(null), sent)
        assertTrue(keyDeleted)
        assertFalse(destroyed)
    }

    @Test
    fun `retries back off up to a minute`() {
        answer = { 400 }
        repeat(8) { opener.open(attempt = it) }
        assertEquals(DatabaseOpener.RETRY_MAX_MS, retries.last())
        assertEquals(retries.sorted(), retries)
    }
}
