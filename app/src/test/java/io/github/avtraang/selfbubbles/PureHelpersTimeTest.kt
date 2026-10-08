package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Characterization tests (plan C0) for the time helpers in MainActivity.kt: fmtTime, fmtListTime,
 * sameDay, needsDivider, sameRun, dividerLabel, bubbleTime. They read the default TimeZone and
 * Locale, and fmtListTime / dividerLabel also read the system clock, with no injection point.
 *
 * Every test pins the defaults explicitly (UTC / en-US unless stated) and restores them in a
 * `finally`. Clock-dependent cases pin only what is stable: output for timestamps far in the past,
 * format shape, same-input idempotence on a fixed instant, and "now" / local midnight / "now - 1 day"
 * guarded against a midnight crossing with Assume (local midnight is computed from the clock in the
 * forced zone, so the day boundary itself is pinned in UTC and Tokyo). The default Locale is pinned
 * only as "ja-JP output differs from en-US for MMM/EEE", never as a specific non-English string.
 * Left untested: DST-day behaviour of "Yesterday" (dividerLabel uses Calendar DAY_OF_YEAR - 1 while
 * the tests use 24 h), zones other than UTC and Tokyo, and the exact localized strings.
 */
class PureHelpersTimeTest {

    private companion object {
        const val UTC = "UTC"
        const val TOKYO = "Asia/Tokyo" // +09:00, no DST
        const val EPOCH = 0.0                 // Thu 1970-01-01 00:00:00Z
        const val T1 = 1_700_000_000.0        // Tue 2023-11-14 22:13:20Z
        const val NOON = 1_699_963_200.0      // Tue 2023-11-14 12:00:00Z
        const val TOKYO_MIDNIGHT = 1_699_974_000.0 // 2023-11-14 15:00:00Z = Wed 2023-11-15 00:00 Tokyo
        const val DAY = 86_400.0
        const val HOUR = 3_600.0
    }

    private fun <T> withDefaults(zone: String, locale: Locale = Locale.US, block: () -> T): T {
        val tz0 = TimeZone.getDefault()
        val loc0 = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            Locale.setDefault(locale)
            return block()
        } finally {
            TimeZone.setDefault(tz0)
            Locale.setDefault(loc0)
        }
    }

    /** yyyyMMdd of [ms] in the current default zone; used to detect a midnight crossing mid-test. */
    private fun dayStamp(ms: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    /** Local midnight that started today in the current default zone, in ms (a multiple of 1000, so /1000.0 is exact). */
    private fun startOfTodayMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun msg(rowid: Long, date: Double?, fromMe: Boolean = false, sender: String? = "Ada", handle: String? = null) =
        Msg(rowid = rowid, guid = "MSG-$rowid", date = date, is_from_me = fromMe, sender = sender, sender_handle = handle, chat_guid = "iMessage;-;+15550100")

    // ---- fmtTime ("MMM d, HH:mm", default zone + locale) ----

    @Test fun fmtTime_knownInstantsUtc() = withDefaults(UTC) {
        assertEquals("Jan 1, 00:00", fmtTime(EPOCH))
        assertEquals("Jan 1, 23:59", fmtTime(DAY - 1))
        assertEquals("Jan 2, 00:00", fmtTime(DAY))
        assertEquals("Nov 14, 22:13", fmtTime(T1))
        assertEquals("Nov 14, 12:00", fmtTime(NOON))
    }

    @Test fun fmtTime_is24HourWithoutYearOrSeconds() = withDefaults(UTC) {
        assertEquals("Jan 1, 13:00", fmtTime(13 * HOUR))        // not "1:00 PM"
        assertEquals("Jan 1, 00:00", fmtTime(59.999))           // seconds dropped
        assertEquals(fmtTime(EPOCH), fmtTime(365 * DAY))        // 1970 and 1971 collide: no year
        assertTrue(fmtTime(T1).matches(Regex("""[A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2}""")))
    }

    @Test fun fmtTime_fractionalSecondsTruncateTowardZero() = withDefaults(UTC) {
        assertEquals("Jan 1, 00:00", fmtTime(0.9999))
        assertEquals("Jan 1, 00:01", fmtTime(60.0))
        assertEquals("Jan 1, 00:00", fmtTime(59.9999)) // (59.9999 * 1000).toLong() = 59999 ms
    }

    @Test fun fmtTime_preEpochWrapsBackwards() = withDefaults(UTC) {
        assertEquals("Dec 31, 23:59", fmtTime(-1.0)) // pins current behaviour: 1969 renders normally
    }

    @Test fun fmtTime_followsDefaultZone() {
        withDefaults(TOKYO) {
            assertEquals("Jan 1, 09:00", fmtTime(EPOCH))
            assertEquals("Nov 15, 07:13", fmtTime(T1)) // crosses into the next day in +09:00
        }
    }

    @Test fun fmtTime_idempotent() = withDefaults(UTC) {
        assertEquals(fmtTime(T1), fmtTime(T1))
    }

    @Test fun fmtTime_followsDefaultLocale() {
        // Locale.getDefault() is consulted: CLDR ja renders MMM as "11月", so the output differs
        // from en-US while the locale-independent "d, HH:mm" tail stays the same. The exact
        // Japanese string is deliberately not asserted.
        val us = withDefaults(UTC) { fmtTime(T1) }
        val jp = withDefaults(UTC, Locale.JAPAN) { fmtTime(T1) }
        assertEquals("Nov 14, 22:13", us)
        assertTrue(jp, jp.endsWith(" 14, 22:13"))
        assertNotEquals(us, jp)
    }

    // ---- fmtListTime ("HH:mm" today, else "MMM d"; reads the clock) ----

    @Test fun fmtListTime_pastDatesUseShortDateUtc() = withDefaults(UTC) {
        assertEquals("Jan 1", fmtListTime(EPOCH))
        assertEquals("Jan 1", fmtListTime(DAY - 1))
        assertEquals("Jan 2", fmtListTime(DAY))
        assertEquals("Nov 14", fmtListTime(T1))
        assertEquals("Nov 14", fmtListTime(NOON))
        assertEquals(fmtListTime(EPOCH), fmtListTime(365 * DAY)) // no year
        assertEquals("Dec 31", fmtListTime(-1.0))
    }

    @Test fun fmtListTime_pastDatesFollowDefaultZone() = withDefaults(TOKYO) {
        assertEquals("Nov 15", fmtListTime(T1))
        assertEquals("Jan 1", fmtListTime(EPOCH))
    }

    @Test fun fmtListTime_todayUsesTimeOfDay() = withDefaults(UTC) {
        val nowMs = System.currentTimeMillis()
        val before = dayStamp(nowMs)
        val out = fmtListTime(nowMs / 1000.0)
        assumeTrue("midnight crossed during the test", before == dayStamp())
        assertTrue(out, out.matches(Regex("""\d{2}:\d{2}""")))
        assertEquals(SimpleDateFormat("HH:mm", Locale.US).format(Date(nowMs)), out)
    }

    @Test fun fmtListTime_yesterdayIsADateNotATime() = withDefaults(UTC) {
        val nowMs = System.currentTimeMillis()
        val before = dayStamp(nowMs)
        val out = fmtListTime(nowMs / 1000.0 - DAY)
        assumeTrue("midnight crossed during the test", before == dayStamp())
        assertTrue(out, out.matches(Regex("""[A-Z][a-z]{2} \d{1,2}""")))
    }

    @Test fun fmtListTime_localMidnightIsTheDayBoundary() {
        // Pinned in two zones without DST: midnight itself is "today", one millisecond earlier is yesterday.
        for (zone in listOf(UTC, TOKYO)) withDefaults(zone) {
            val before = dayStamp()
            val start = startOfTodayMs()
            val atMidnight = fmtListTime(start / 1000.0)
            val msBefore = fmtListTime((start - 1) / 1000.0)
            val secondBefore = fmtListTime((start - 1000) / 1000.0)
            assumeTrue("midnight crossed during the test", before == dayStamp())
            assertEquals(zone, "00:00", atMidnight)
            assertTrue("$zone: $msBefore", msBefore.matches(Regex("""[A-Z][a-z]{2} \d{1,2}""")))
            assertTrue("$zone: $secondBefore", secondBefore.matches(Regex("""[A-Z][a-z]{2} \d{1,2}""")))
        }
    }

    @Test fun fmtListTime_idempotentForTheSameInput() = withDefaults(UTC) {
        // Only the clock-free instant: a "now" comparison was dropped because it could straddle
        // midnight between the two calls and pinned nothing a deterministic function would not.
        assertEquals(fmtListTime(T1), fmtListTime(T1))
    }

    @Test fun fmtListTime_pastDateFollowsDefaultLocale() {
        val us = withDefaults(UTC) { fmtListTime(T1) }
        val jp = withDefaults(UTC, Locale.JAPAN) { fmtListTime(T1) }
        assertEquals("Nov 14", us)
        assertTrue(jp, jp.endsWith(" 14"))
        assertNotEquals(us, jp)   // MMM is localized
    }

    // ---- sameDay ----

    @Test fun sameDay_nullMeansSame() = withDefaults(UTC) {
        // pins current behaviour: an unknown date never starts a new day
        assertTrue(sameDay(null, null))
        assertTrue(sameDay(null, T1))
        assertTrue(sameDay(T1, null))
    }

    @Test fun sameDay_calendarDayInDefaultZone() = withDefaults(UTC) {
        assertTrue(sameDay(EPOCH, EPOCH))
        assertTrue(sameDay(EPOCH, DAY - 1))
        assertTrue(sameDay(DAY - 1, EPOCH))     // symmetric
        assertFalse(sameDay(EPOCH, DAY))
        assertFalse(sameDay(DAY - 0.5, DAY))    // half a second across midnight
        assertTrue(sameDay(T1, NOON))
        assertFalse(sameDay(-1.0, EPOCH))       // 1969-12-31 vs 1970-01-01
        assertFalse(sameDay(EPOCH, 365 * DAY))  // same "Jan 1", different year
    }

    @Test fun sameDay_followsDefaultZone() = withDefaults(TOKYO) {
        assertFalse(sameDay(EPOCH, DAY - 1))          // 09:00 Jan 1 vs 08:59:59 Jan 2 in +09:00
        assertFalse(sameDay(T1, NOON))                // 07:13 Nov 15 vs 21:00 Nov 14 in +09:00
        assertTrue(sameDay(TOKYO_MIDNIGHT, TOKYO_MIDNIGHT + DAY - 1))
    }

    // ---- needsDivider ----

    @Test fun needsDivider_currentMessageWithoutDateNeverDivides() = withDefaults(UTC) {
        assertFalse(needsDivider(null, msg(2, null)))
        assertFalse(needsDivider(msg(1, NOON), msg(2, null)))
        assertFalse(needsDivider(msg(1, null), msg(2, null)))
    }

    @Test fun needsDivider_firstDatedMessageDivides() = withDefaults(UTC) {
        assertTrue(needsDivider(null, msg(2, NOON)))
        assertTrue(needsDivider(msg(1, null), msg(2, NOON)))
    }

    @Test fun needsDivider_sameDayGapBoundaryIsOneHourExclusive() = withDefaults(UTC) {
        val prev = msg(1, NOON)
        assertFalse(needsDivider(prev, msg(2, NOON)))
        assertFalse(needsDivider(prev, msg(2, NOON + 1)))
        assertFalse(needsDivider(prev, msg(2, NOON + HOUR / 2)))
        assertFalse(needsDivider(prev, msg(2, NOON + HOUR)))          // exactly one hour: no divider
        assertTrue(needsDivider(prev, msg(2, NOON + HOUR + 0.001)))
        assertTrue(needsDivider(prev, msg(2, NOON + 2 * HOUR)))
    }

    @Test fun needsDivider_gapIsAbsolute() = withDefaults(UTC) {
        val prev = msg(1, NOON)
        assertFalse(needsDivider(prev, msg(2, NOON - HOUR / 2)))     // out-of-order within the hour
        assertFalse(needsDivider(prev, msg(2, NOON - HOUR)))
        assertTrue(needsDivider(prev, msg(2, NOON - HOUR - 1)))
    }

    @Test fun needsDivider_dayChangeDividesEvenWhenClose() = withDefaults(UTC) {
        assertTrue(needsDivider(msg(1, DAY - 1), msg(2, DAY + 0.5)))
        assertTrue(needsDivider(msg(1, DAY), msg(2, DAY - 1)))       // backwards across midnight
    }

    @Test fun needsDivider_dayChangeIsInDefaultZone() {
        val prev = msg(1, TOKYO_MIDNIGHT - 1)
        val m = msg(2, TOKYO_MIDNIGHT + 1)
        withDefaults(UTC) { assertFalse(needsDivider(prev, m)) }   // 14:59:59Z -> 15:00:01Z, same day
        withDefaults(TOKYO) { assertTrue(needsDivider(prev, m)) }  // crosses local midnight
    }

    // ---- sameRun ----

    @Test fun sameRun_sameSenderCloseInTime() = withDefaults(UTC) {
        assertTrue(sameRun(msg(1, NOON), msg(2, NOON + 60)))
        assertTrue(sameRun(msg(1, NOON, fromMe = true), msg(2, NOON + HOUR, fromMe = true)))
    }

    @Test fun sameRun_differentSenderOrDirectionBreaks() = withDefaults(UTC) {
        assertFalse(sameRun(msg(1, NOON, sender = "Ada"), msg(2, NOON + 1, sender = "Bob")))
        assertFalse(sameRun(msg(1, NOON, fromMe = true), msg(2, NOON + 1, fromMe = false)))
        assertFalse(sameRun(msg(1, NOON, sender = "Ada"), msg(2, NOON + 1, sender = null)))
    }

    @Test fun sameRun_separatorBreaksTheRun() = withDefaults(UTC) {
        assertFalse(sameRun(msg(1, NOON), msg(2, NOON + HOUR + 1)))      // > 1 h gap
        assertFalse(sameRun(msg(1, DAY - 1), msg(2, DAY + 1)))           // new day
    }

    @Test fun sameRun_nullDates() = withDefaults(UTC) {
        // pins current behaviour: a dateless current message never breaks a run,
        // a dateless previous message always does
        assertTrue(sameRun(msg(1, null), msg(2, null)))
        assertTrue(sameRun(msg(1, NOON), msg(2, null)))
        assertFalse(sameRun(msg(1, null), msg(2, NOON)))
    }

    @Test fun sameRun_comparesSenderNameNotHandle() = withDefaults(UTC) {
        // pins current behaviour: sender_handle is ignored, only the display name matters
        assertTrue(sameRun(msg(1, NOON, sender = "Ada", handle = "+15550101"), msg(2, NOON + 1, sender = "Ada", handle = "+15550102")))
        assertTrue(sameRun(msg(1, NOON, sender = null), msg(2, NOON + 1, sender = null)))
    }

    // ---- dividerLabel ("Today" / "Yesterday" / "EEE, MMM d"; reads the clock) ----

    @Test fun dividerLabel_pastDaysUtc() = withDefaults(UTC) {
        assertEquals("Thu, Jan 1", dividerLabel(EPOCH))
        assertEquals("Thu, Jan 1", dividerLabel(DAY - 1))
        assertEquals("Fri, Jan 2", dividerLabel(DAY))
        assertEquals("Tue, Nov 14", dividerLabel(T1))
        assertEquals("Tue, Nov 14", dividerLabel(NOON))   // time of day is not part of the label
        assertEquals("Wed, Dec 31", dividerLabel(-1.0))
    }

    @Test fun dividerLabel_pastDaysFollowDefaultZone() = withDefaults(TOKYO) {
        assertEquals("Wed, Nov 15", dividerLabel(T1))
        assertEquals("Thu, Jan 1", dividerLabel(EPOCH))
    }

    @Test fun dividerLabel_todayAndYesterday() = withDefaults(UTC) {
        val nowMs = System.currentTimeMillis()
        val before = dayStamp(nowMs)
        val today = dividerLabel(nowMs / 1000.0)
        val yesterday = dividerLabel(nowMs / 1000.0 - DAY)
        val twoDaysAgo = dividerLabel(nowMs / 1000.0 - 2 * DAY)
        assumeTrue("midnight crossed during the test", before == dayStamp())
        assertEquals("Today", today)
        assertEquals("Yesterday", yesterday)
        assertTrue(twoDaysAgo, twoDaysAgo.matches(Regex("""[A-Z][a-z]{2}, [A-Z][a-z]{2} \d{1,2}""")))
        assertNotEquals("Today", twoDaysAgo)
    }

    @Test fun dividerLabel_localMidnightIsTheDayBoundary() {
        for (zone in listOf(UTC, TOKYO)) withDefaults(zone) {
            val before = dayStamp()
            val start = startOfTodayMs()
            val atMidnight = dividerLabel(start / 1000.0)
            val msBefore = dividerLabel((start - 1) / 1000.0)
            val yesterdayStart = dividerLabel((start - 86_400_000L) / 1000.0)
            val dayBeforeEnd = dividerLabel((start - 86_400_000L - 1) / 1000.0)
            assumeTrue("midnight crossed during the test", before == dayStamp())
            assertEquals(zone, "Today", atMidnight)
            assertEquals(zone, "Yesterday", msBefore)
            assertEquals(zone, "Yesterday", yesterdayStart)
            assertTrue("$zone: $dayBeforeEnd", dayBeforeEnd.matches(Regex("""[A-Z][a-z]{2}, [A-Z][a-z]{2} \d{1,2}""")))
        }
    }

    @Test fun dividerLabel_idempotent() = withDefaults(UTC) {
        assertEquals(dividerLabel(T1), dividerLabel(T1)) // clock-free instant only (see fmtListTime_idempotentForTheSameInput)
    }

    @Test fun dividerLabel_pastDayFollowsDefaultLocale() {
        val us = withDefaults(UTC) { dividerLabel(T1) }
        val jp = withDefaults(UTC, Locale.JAPAN) { dividerLabel(T1) }
        assertEquals("Tue, Nov 14", us)
        assertTrue(jp, jp.endsWith(" 14"))
        assertNotEquals(us, jp)   // EEE and MMM are localized
    }

    // ---- bubbleTime ("HH:mm" or "") ----

    @Test fun bubbleTime_nullIsEmpty() = withDefaults(UTC) {
        assertEquals("", bubbleTime(null))
    }

    @Test fun bubbleTime_knownInstantsUtc() = withDefaults(UTC) {
        assertEquals("00:00", bubbleTime(EPOCH))
        assertEquals("22:13", bubbleTime(T1))
        assertEquals("12:00", bubbleTime(NOON))
        assertEquals("13:00", bubbleTime(13 * HOUR))   // 24-hour clock
        assertEquals("23:59", bubbleTime(DAY - 1))
        assertEquals("00:00", bubbleTime(59.999))      // seconds dropped
        assertEquals("23:59", bubbleTime(-1.0))        // pins current behaviour: pre-epoch
    }

    @Test fun bubbleTime_followsDefaultZone() = withDefaults(TOKYO) {
        assertEquals("09:00", bubbleTime(EPOCH))
        assertEquals("07:13", bubbleTime(T1))
    }

    @Test fun bubbleTime_idempotent() = withDefaults(UTC) {
        assertEquals(bubbleTime(T1), bubbleTime(T1))
    }

    @Test fun bubbleTime_sameAcrossAsciiDigitLocales() {
        // "HH:mm" has no localized field, so ja-JP and fr-FR render the same digits as en-US.
        assertEquals("22:13", withDefaults(UTC, Locale.JAPAN) { bubbleTime(T1) })
        assertEquals("22:13", withDefaults(UTC, Locale.FRANCE) { bubbleTime(T1) })
    }
}
