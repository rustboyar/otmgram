package dev.otmgram.ui.chat

import dev.otmgram.R
import dev.otmgram.testing.TestStrings
import dev.otmgram.ui.format.sendsAt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, 27, hour, minute, 0, 0, zone)

    private fun List<SendLaterOption>.labels() = map { it.label }

    @Test
    fun `quick choices are in an hour, this evening while it's over an hour away, and tomorrow morning`() {
        val afternoon = sendLaterOptions(at(15, 30))
        assertEquals(listOf(R.string.send_later_in_hour, R.string.send_later_evening, R.string.send_later_tomorrow), afternoon.labels())
        assertEquals(at(16, 30).toEpochSecond().toInt(), afternoon[0].sendAt)
        assertEquals(at(20).toEpochSecond().toInt(), afternoon[1].sendAt)
        assertEquals(at(9).plusDays(1).toEpochSecond().toInt(), afternoon[2].sendAt)

        assertEquals(listOf(R.string.send_later_in_hour, R.string.send_later_tomorrow), sendLaterOptions(at(19, 10)).labels())
    }

    @Test
    fun `send times read as today, tomorrow, or the day`() {
        val today = LocalDate.of(2026, 10, 27)
        fun label(time: ZonedDateTime) = sendsAt(time.toEpochSecond().toInt(), TestStrings, today, zone)
        assertEquals(true, label(at(20)).startsWith("Today, "))
        assertEquals(true, label(at(9).plusDays(1)).startsWith("Tomorrow, "))
        assertEquals(true, label(at(9).plusDays(3)).startsWith("Friday, 30 October, "))
    }
}
