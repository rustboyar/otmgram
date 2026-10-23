package dev.otmgram.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformTest {

    @Test
    fun `encode then decode returns the levels`() {
        val levels = List(100) { (it * 7) % 32 }
        val decoded = Waveform.decode(Waveform.encode(levels))
        assertEquals(levels, decoded.take(levels.size))
    }

    @Test
    fun `levels are packed into 5 bits each`() {
        assertEquals(63, Waveform.encode(List(100) { 31 }).size)
    }

    @Test
    fun `out of range levels are clamped`() {
        assertEquals(listOf(31, 0), Waveform.decode(Waveform.encode(listOf(40, -3))).take(2))
    }
}
