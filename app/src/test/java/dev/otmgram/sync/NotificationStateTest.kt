package dev.otmgram.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStateTest {

    @Test
    fun `message marks survive being stored`() {
        val marks = mapOf(-100123L to 4_194_304L, 77L to 1_048_576L)
        assertEquals(marks, decode(encode(marks)))
        assertEquals(emptyMap<Long, Long>(), decode(encode(emptyMap())))
    }
}
