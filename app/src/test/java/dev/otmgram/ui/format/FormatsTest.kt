package dev.otmgram.ui.format

import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.testing.TestStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class FormatsTest {

    private val utc = ZoneOffset.UTC
    private val friday = LocalDate.of(2026, 10, 23)
    private val day = 24 * 60 * 60
    private lateinit var locale: Locale

    // Localized date and time formats follow the default locale.
    @Before
    fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
    }

    @Test
    fun `the chat list shows the time today, the weekday this week, else the date`() {
        assertEquals("12:00", chatListDate(NOON_2026_10_23, friday, utc, Locale.UK))
        assertEquals("Tue", chatListDate(NOON_2026_10_23 - 3 * day, friday, utc, Locale.UK))
        assertEquals("09/10/2026", chatListDate(NOON_2026_10_23 - 14 * day, friday, utc, Locale.UK))
    }

    @Test
    fun `the viewer says today and yesterday, the date before`() {
        assertEquals("Today, 12:00", postedAt(NOON_2026_10_23, TestStrings, friday, utc))
        assertEquals("Yesterday, 12:00", postedAt(NOON_2026_10_23 - day, TestStrings, friday, utc))
        assertEquals("20 Oct 2026, 12:00", postedAt(NOON_2026_10_23 - 3 * day, TestStrings, friday, utc))
    }
}
