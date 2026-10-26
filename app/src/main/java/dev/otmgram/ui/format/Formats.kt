package dev.otmgram.ui.format

import android.text.format.DateUtils
import dev.otmgram.R
import dev.otmgram.core.Strings
import org.drinkless.tdlib.TdApi
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// How times, dates, durations and sizes read across the app. "Now" and the time zone are
// parameters, so tests can fix them.

/** The day of a Unix time, where the phone is. */
fun localDay(unixTime: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(zone).toLocalDate()

/** "19:01", as the phone shows times: a message's time. */
fun formatTime(unixTime: Int, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** In the chat list: the time for today, the weekday within the last week, the date otherwise. */
fun chatListDate(
    unixTime: Int,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val dateTime = Instant.ofEpochSecond(unixTime.toLong()).atZone(zone)
    return when {
        dateTime.toLocalDate() == today -> dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        dateTime.toLocalDate().isAfter(today.minusDays(7)) -> dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        else -> dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}

/** Between days in a chat: "Thursday, 23 October"; with the year when it isn't this one. */
fun dayLabel(
    unixTime: Int,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val date = localDay(unixTime, zone)
    val pattern = if (date.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** When something was posted, in the viewer: "Today, 19:01", or the date. */
fun postedAt(unixTime: Int, strings: Strings, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val day = when (localDay(unixTime, zone)) {
        today -> strings.get(R.string.day_today)
        today.minusDays(1) -> strings.get(R.string.day_yesterday)
        else -> localDay(unixTime, zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return strings.get(R.string.day_and_time, day, formatTime(unixTime, zone))
}

/** "online", "last seen at 19:01", "last seen recently". */
fun lastSeen(
    status: TdApi.UserStatus?,
    strings: Strings,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = when (status) {
    is TdApi.UserStatusOnline -> strings.get(R.string.status_online)
    is TdApi.UserStatusOffline -> {
        val time = Instant.ofEpochSecond(status.wasOnline.toLong()).atZone(zone)
        if (time.toLocalDate() == today) strings.get(R.string.status_last_seen_at, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
        else strings.get(R.string.status_last_seen_on, time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
    is TdApi.UserStatusRecently -> strings.get(R.string.status_recently)
    is TdApi.UserStatusLastWeek -> strings.get(R.string.status_week)
    is TdApi.UserStatusLastMonth -> strings.get(R.string.status_month)
    else -> null
}

/** A time in milliseconds: the time for today, else the date. */
fun timeOrDate(millis: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.formatSameDayTime(millis, now, DateFormat.SHORT, DateFormat.SHORT).toString()

/** "1:05": a voice message's or video's length. */
fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** "2.4 MB", "830 KB", "120 B". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
