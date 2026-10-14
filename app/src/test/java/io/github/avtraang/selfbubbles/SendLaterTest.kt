package io.github.avtraang.selfbubbles

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Send later (2026-10-11). A text could only go now; the relay now keeps one
 * and sends it at a chosen time (`/scheduled`), under a send id of the app's
 * making so it can never go twice, and tells the app over the socket and by
 * push as the item moves.
 *
 * These pin the app's side of that contract (SendLater.kt, Features.kt,
 * PushService.kt): the chooser's presets from fixed "now" values across the
 * day and year boundary, the bounds the relay enforces too, the request shapes
 * (every optional key present only when set), the item in every state with
 * its nulls, what a row says for today, tomorrow and other days and what it
 * offers, the notification's words, the socket frame, the toast for a
 * refusal, the send id kept across tries so a lost answer cannot schedule a
 * text twice, Retry under the item's own id against "Send anyway" under a new
 * one once the relay cannot tell what became of the send, and that nothing is
 * offered without both the switch and the relay's word. Times are built in UTC
 * or a fixed offset, so no clock change can move an hour under an assertion.
 */
class SendLaterTest {
    private val chat = "iMessage;-;+15550100200"
    private val utc: ZoneId = ZoneOffset.UTC
    /** A fixed offset, not a region: nothing can shift a local hour in it. */
    private val plusNine: ZoneId = ZoneOffset.ofHours(9)
    private val base = "https://relay.example"
    private val slam = "com.apple.MobileSMS.expressivesend.impact"

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = utc): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    /** Tuesday 13 October 2026, ten in the morning. */
    private val tuesdayMorning = at(2026, 10, 13, 10, 0)

    private fun body(req: okhttp3.Request): String = Buffer().also { req.body!!.writeTo(it) }.readUtf8()

    private fun item(id: String = "s-1", state: String = "pending", sendAt: Long = 1_760_000_000L, chat: String = this.chat, error: String? = null) =
        ScheduledItem(id = id, chat_guid = chat, text = "synthetic text", send_at = sendAt, state = state, error = error)

    // ---- the presets ----

    @Test fun mid_morning_offers_an_hour_from_now_tonight_at_nine_and_tomorrow_at_nine() {
        val presets = sendLaterPresets(tuesdayMorning, Locale.US)
        assertEquals(listOf("In 1 hour", "Tonight 21:00", "Tomorrow 09:00"), presets.map { it.label })
        assertEquals(tuesdayMorning.toEpochSecond() + 3_600, presets[0].sendAtSeconds)
        assertEquals(at(2026, 10, 13, 21, 0).toEpochSecond(), presets[1].sendAtSeconds)
        assertEquals(at(2026, 10, 14, 9, 0).toEpochSecond(), presets[2].sendAtSeconds)
    }

    @Test fun tonight_is_offered_only_while_it_is_still_an_hour_away() {
        fun offersTonight(now: ZonedDateTime) = sendLaterPresets(now, Locale.US).any { it.label.startsWith("Tonight") }
        assertTrue(offersTonight(at(2026, 10, 13, 20, 0)))      // exactly an hour: still offered
        assertFalse(offersTonight(at(2026, 10, 13, 20, 1)))     // "In 1 hour" already says later than that
        assertFalse(offersTonight(at(2026, 10, 13, 21, 0)))
        assertFalse(offersTonight(at(2026, 10, 13, 23, 30)))
    }

    @Test fun late_at_night_the_hour_crosses_midnight_and_tomorrow_is_after_that_midnight() {
        val presets = sendLaterPresets(at(2026, 10, 13, 23, 30), Locale.US)
        assertEquals(listOf("In 1 hour", "Tomorrow 09:00"), presets.map { it.label })
        assertEquals(at(2026, 10, 14, 0, 30).toEpochSecond(), presets[0].sendAtSeconds)
        // Not nine o'clock after the next midnight from "in an hour", which would be the 15th.
        assertEquals(at(2026, 10, 14, 9, 0).toEpochSecond(), presets[1].sendAtSeconds)
    }

    @Test fun on_new_years_eve_tomorrow_is_next_year() {
        val presets = sendLaterPresets(at(2026, 12, 31, 22, 0), Locale.US)
        val tomorrow = Instant.ofEpochSecond(presets.last().sendAtSeconds).atZone(utc)
        assertEquals(LocalDate.of(2027, 1, 1), tomorrow.toLocalDate())
        assertEquals(LocalTime.of(9, 0), tomorrow.toLocalTime())
    }

    @Test fun the_presets_are_the_phones_local_hours_not_utcs() {
        val now = at(2026, 10, 13, 10, 0, plusNine)
        val presets = sendLaterPresets(now, Locale.US)
        val tonight = Instant.ofEpochSecond(presets[1].sendAtSeconds).atZone(plusNine)
        assertEquals(LocalTime.of(21, 0), tonight.toLocalTime())
        assertEquals(now.toLocalDate(), tonight.toLocalDate())
        // The same instant read in UTC is noon: nine in the evening where the phone is, not in Greenwich.
        assertEquals(12, Instant.ofEpochSecond(presets[1].sendAtSeconds).atZone(utc).hour)
        assertEquals("Tonight 21:00", presets[1].label)
    }

    @Test fun every_preset_is_a_time_the_relay_takes() {
        for (now in listOf(tuesdayMorning, at(2026, 10, 13, 0, 0), at(2026, 10, 13, 20, 0), at(2026, 10, 13, 23, 59), at(2026, 12, 31, 23, 59))) {
            for (preset in sendLaterPresets(now, Locale.US)) {
                assertEquals("$now ${preset.label}", ScheduleBound.OK, scheduleBound(preset.sendAtSeconds, now.toEpochSecond()))
            }
        }
    }

    @Test fun a_24_hour_locale_reads_its_clock_and_a_12_hour_one_its_own() {
        assertEquals(listOf("In 1 hour", "Tonight 21:00", "Tomorrow 09:00"), sendLaterPresets(tuesdayMorning, Locale.FRANCE).map { it.label })
        assertEquals("09:05", clockLabel(LocalTime.of(9, 5), Locale.US))
        assertEquals("00:30", clockLabel(LocalTime.of(0, 30), Locale.US))
        assertEquals("00:30", clockLabel(LocalTime.of(0, 30), Locale.FRANCE))
    }

    // ---- the bounds ----

    @Test fun the_bounds_are_the_relays_a_minute_and_thirty_days() {
        val now = 1_760_000_000L
        assertEquals(ScheduleBound.TOO_SOON, scheduleBound(now - 1, now))
        assertEquals(ScheduleBound.TOO_SOON, scheduleBound(now, now))
        assertEquals(ScheduleBound.TOO_SOON, scheduleBound(now + 59, now))
        assertEquals(ScheduleBound.OK, scheduleBound(now + 60, now))
        assertEquals(ScheduleBound.OK, scheduleBound(now + 30 * 86_400, now))
        assertEquals(ScheduleBound.TOO_LATE, scheduleBound(now + 30 * 86_400 + 1, now))
        assertEquals(60L, SCHEDULE_MIN_LEAD_SECONDS)
        assertEquals(30L * 86_400, SCHEDULE_MAX_LEAD_SECONDS)
    }

    @Test fun a_time_outside_the_bounds_is_explained_and_one_inside_is_not() {
        assertNull(scheduleBoundLine(ScheduleBound.OK))
        assertEquals("Pick a time at least a minute from now", scheduleBoundLine(ScheduleBound.TOO_SOON))
        assertEquals("Pick a time within the next 30 days", scheduleBoundLine(ScheduleBound.TOO_LATE))
    }

    // ---- the pickers ----

    @Test fun a_picked_day_and_time_are_the_phones_local_time() {
        val day = dateUtcMillisOf(LocalDate.of(2026, 10, 21))
        assertEquals(LocalDate.of(2026, 10, 21), dateOfUtcMillis(day))
        assertEquals(at(2026, 10, 21, 9, 30).toEpochSecond(), pickedSendAt(day, 9, 30, utc))
        // Nine thirty where the phone is, nine hours ahead of Greenwich: an earlier instant.
        assertEquals(at(2026, 10, 21, 9, 30, plusNine).toEpochSecond(), pickedSendAt(day, 9, 30, plusNine))
        assertEquals(pickedSendAt(day, 9, 30, utc) - 9 * 3_600, pickedSendAt(day, 9, 30, plusNine))
    }

    @Test fun the_calendar_lets_through_today_and_the_next_thirty_days() {
        val today = LocalDate.of(2026, 10, 13)
        assertTrue(selectableScheduleDate(dateUtcMillisOf(today), today))
        assertTrue(selectableScheduleDate(dateUtcMillisOf(today.plusDays(30)), today))
        assertFalse(selectableScheduleDate(dateUtcMillisOf(today.minusDays(1)), today))
        assertFalse(selectableScheduleDate(dateUtcMillisOf(today.plusDays(31)), today))
    }

    // ---- the requests ----

    @Test fun the_post_names_what_is_set_and_nothing_else() {
        val plain = scheduleRequest(base, chat, "hello", 1_760_000_000L, "a-send-id-0001", null, null)
        assertEquals("$base/scheduled", plain.url.toString())
        assertEquals("POST", plain.method)
        assertTrue(plain.body!!.isOneShot())
        assertEquals("""{"chat_guid":"$chat","text":"hello","send_at":1760000000,"client_id":"a-send-id-0001"}""", body(plain))
        val full = body(scheduleRequest(base, chat, "hello", 1_760_000_000L, "a-send-id-0001", "g-7", slam))
        assertTrue(full, full.contains("\"reply_to_guid\":\"g-7\""))
        assertTrue(full, full.contains("\"effect\":\"$slam\""))
    }

    @Test fun the_patch_carries_only_the_keys_that_change() {
        val time = rescheduleRequest(base, "s-1", 1_760_003_600L, null)
        assertEquals("PATCH", time.method)
        assertEquals(listOf("scheduled", "s-1"), time.url.pathSegments)
        assertEquals("""{"send_at":1760003600}""", body(time))
        assertEquals("""{"text":"later words"}""", body(rescheduleRequest(base, "s-1", null, "later words")))
        assertEquals("""{"text":"later words","send_at":1760003600}""", body(rescheduleRequest(base, "s-1", 1_760_003_600L, "later words")))
        assertEquals("{}", body(rescheduleRequest(base, "s-1", null, null)))
    }

    @Test fun the_list_delete_and_send_now_requests_name_the_chat_or_the_item() {
        val one = scheduledListRequest(base, chat)
        assertEquals("GET", one.method)
        assertEquals("/scheduled", one.url.encodedPath)
        assertEquals(chat, one.url.queryParameter("chat_guid"))    // the guid's ';' and '+' survive the round trip
        val all = scheduledListRequest(base, null)
        assertEquals("$base/scheduled", all.url.toString())
        assertNull(all.url.query)

        val gone = cancelScheduledRequest(base, "s-1")
        assertEquals("DELETE", gone.method)
        assertEquals("$base/scheduled/s-1", gone.url.toString())

        val now = sendScheduledNowRequest(base, "s-1")
        assertEquals("POST", now.method)
        assertEquals("$base/scheduled/s-1/send_now", now.url.toString())
        assertTrue(now.body!!.isOneShot())
        assertEquals(0L, now.body!!.contentLength())

        // An id is one path segment whatever it holds: a slash in it does not make a second one.
        assertEquals(listOf("scheduled", "a/b"), cancelScheduledRequest(base, "a/b").url.pathSegments)
        assertTrue(cancelScheduledRequest(base, "a/b").url.encodedPath.contains("%2F"))
    }

    @Test fun send_now_carries_a_new_send_id_only_for_send_anyway() {
        // Retry: no body, so the relay sends under the item's own id and can never send it twice.
        val retry = sendScheduledNowRequest(base, "s-1")
        assertEquals(0L, retry.body!!.contentLength())
        assertNull(retry.body!!.contentType())
        assertEquals(0L, sendScheduledNowRequest(base, "s-1", clientId = null).body!!.contentLength())
        // Send anyway: the new id, and nothing else, as JSON; the same route, still written once.
        val anyway = sendScheduledNowRequest(base, "s-1", clientId = "a-new-send-id-0002")
        assertEquals("POST", anyway.method)
        assertEquals("$base/scheduled/s-1/send_now", anyway.url.toString())
        assertTrue(anyway.body!!.isOneShot())
        assertEquals("""{"client_id":"a-new-send-id-0002"}""", body(anyway))
        assertEquals("application/json", anyway.body!!.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    // ---- the item ----

    private fun itemJson(state: String, extra: String = "") =
        """{"id":"s-1","chat_guid":"$chat","text":"hello","send_at":1760000000,"created_at":1759990000,"client_id":"a-send-id-0001",""" +
            """"reply_to_guid":null,"effect":null,"state":"$state","sent_at":null,"sent_via":null,"error":null$extra}"""

    @Test fun an_item_parses_in_every_state_with_the_relays_nulls() {
        val pending = json.decodeFromString<ScheduledItem>(itemJson("pending"))
        assertEquals(ScheduledState.PENDING, pending.status)
        assertEquals("s-1", pending.id)
        assertEquals(1_760_000_000L, pending.send_at)
        assertEquals(1_759_990_000L, pending.created_at)
        assertEquals("a-send-id-0001", pending.client_id)
        assertNull(pending.reply_to_guid)
        assertNull(pending.effect)
        assertNull(pending.sent_at)
        assertNull(pending.sent_via)
        assertNull(pending.error)
        assertFalse(pending.finished)

        assertEquals(ScheduledState.SENDING, json.decodeFromString<ScheduledItem>(itemJson("sending")).status)

        val sent = json.decodeFromString<ScheduledItem>(
            itemJson("sent").replace("\"sent_at\":null", "\"sent_at\":1760000012").replace("\"sent_via\":null", "\"sent_via\":\"bb\""),
        )
        assertEquals(ScheduledState.SENT, sent.status)
        assertEquals(1_760_000_012L, sent.sent_at)
        assertEquals("bb", sent.sent_via)
        assertTrue(sent.finished)

        val failed = json.decodeFromString<ScheduledItem>(
            itemJson("failed").replace("\"error\":null", "\"error\":\"the relay was not running at the time\""),
        )
        assertEquals(ScheduledState.FAILED, failed.status)
        assertEquals("the relay was not running at the time", failed.error)
        assertTrue(failed.finished)

        val replying = json.decodeFromString<ScheduledItem>(
            itemJson("pending").replace("\"reply_to_guid\":null", "\"reply_to_guid\":\"g-7\"").replace("\"effect\":null", "\"effect\":\"$slam\""),
        )
        assertEquals("g-7", replying.reply_to_guid)
        assertEquals(slam, replying.effect)
    }

    @Test fun a_terser_relays_item_reads_with_defaults_and_a_newer_ones_state_counts_as_pending() {
        val terse = json.decodeFromString<ScheduledItem>("""{"id":"s-2","chat_guid":"$chat","text":"hello","send_at":1760000000}""")
        assertEquals(ScheduledState.PENDING, terse.status)
        assertEquals("", terse.client_id)
        assertEquals(0L, terse.created_at)
        assertEquals(ScheduledState.PENDING, scheduledStateOf("paused"))
        assertEquals(ScheduledState.PENDING, scheduledStateOf(null))
        assertEquals(ScheduledState.PENDING, scheduledStateOf("Sent"))     // the relay's spelling only
    }

    @Test fun the_list_is_soonest_first_and_an_item_that_does_not_read_costs_only_itself() {
        val body = """{"items":[
            {"id":"s-2","chat_guid":"$chat","text":"second","send_at":1760007200},
            {"id":"s-1","chat_guid":"$chat","text":"first","send_at":1760003600},
            {"id":"s-3","chat_guid":"$chat","send_at":1760000000}
        ]}"""
        assertEquals(listOf("s-1", "s-2"), scheduledItemsOf(body).map { it.id })
        assertEquals(emptyList<ScheduledItem>(), scheduledItemsOf("""{"items":[]}"""))
        assertEquals(emptyList<ScheduledItem>(), scheduledItemsOf("""{"ok":true}"""))
        assertEquals(emptyList<ScheduledItem>(), scheduledItemsOf("""{"items":"none"}"""))
        assertEquals(emptyList<ScheduledItem>(), scheduledItemsOf("<html><body>Sign in</body></html>"))
    }

    @Test fun the_item_in_an_answer_is_read_whether_or_not_it_is_a_duplicate() {
        val fresh = scheduledItemOf("""{"ok":true,"item":${itemJson("pending")}}""")
        assertEquals("s-1", fresh?.id)
        val again = scheduledItemOf("""{"ok":true,"item":${itemJson("pending")},"duplicate":true}""")
        assertEquals(fresh, again)
        assertNull(scheduledItemOf("""{"ok":true}"""))
        assertNull(scheduledItemOf("""{"ok":true,"item":{"id":"s-1"}}"""))    // not an item: no chat, no text, no time
        assertNull(scheduledItemOf("<html><body>Sign in</body></html>"))
    }

    @Test fun the_answer_to_send_anyway_carries_the_items_new_send_id() {
        // Before: the Retry's answer, still failed under the first id, with the relay's exact words.
        val before = scheduledItemOf("""{"ok":true,"item":${itemJson("failed").replace("\"error\":null", "\"error\":\"$SCHEDULED_OUTCOME_UNKNOWN\"")}}""")!!
        assertEquals("a-send-id-0001", before.client_id)
        assertTrue(scheduledOutcomeUnknown(before))
        // After: the relay replaced the id and delivered; the item comes back sent under the new one.
        val after = scheduledItemOf(
            """{"ok":true,"item":${itemJson("sent")
                .replace("\"client_id\":\"a-send-id-0001\"", "\"client_id\":\"a-new-send-id-0002\"")
                .replace("\"sent_via\":null", "\"sent_via\":\"bb\"")}}""",
        )!!
        assertEquals("a-new-send-id-0002", after.client_id)
        assertEquals(ScheduledState.SENT, after.status)
        assertFalse(scheduledOutcomeUnknown(after))
        // In the rows the answer takes the old item's place by its id: one row, now under the new send id.
        assertEquals(listOf("a-new-send-id-0002"), scheduledUpsert(listOf(before), after).map { it.client_id })
    }

    // ---- the rows ----

    @Test fun a_row_names_the_day_as_today_tomorrow_a_weekday_or_a_date() {
        val now = tuesdayMorning
        fun label(at: ZonedDateTime) = scheduledWhenLabel(at.toEpochSecond(), now, Locale.US)
        assertEquals("Today 21:00", label(at(2026, 10, 13, 21, 0)))
        assertEquals("Tomorrow 09:00", label(at(2026, 10, 14, 9, 0)))
        assertEquals("Thu 09:00", label(at(2026, 10, 15, 9, 0)))
        assertEquals("Mon 09:00", label(at(2026, 10, 19, 9, 0)))         // six days on: the last weekday
        assertEquals("Oct 20, 09:00", label(at(2026, 10, 20, 9, 0)))     // a week on, "Tue" would be today's name
        assertEquals("Nov 12, 09:00", label(at(2026, 11, 12, 9, 0)))
        // A time the relay missed (it was down) is a date in the past, named like any other day.
        assertEquals("Oct 10, 09:00", label(at(2026, 10, 10, 9, 0)))
    }

    @Test fun today_and_tomorrow_are_calendar_days_not_spans_of_hours() {
        val lateNow = at(2026, 10, 13, 23, 30)
        // Half past midnight is an hour away and already "Tomorrow".
        assertEquals("Tomorrow 00:30", scheduledWhenLabel(at(2026, 10, 14, 0, 30).toEpochSecond(), lateNow, Locale.US))
        // Counted in the phone's zone: the same instants, a phone nine hours east.
        val eastNow = at(2026, 10, 13, 23, 30, plusNine)
        assertEquals("Tomorrow 00:30", scheduledWhenLabel(at(2026, 10, 14, 0, 30, plusNine).toEpochSecond(), eastNow, Locale.US))
        assertEquals("Today 23:59", scheduledWhenLabel(at(2026, 10, 13, 23, 59, plusNine).toEpochSecond(), eastNow, Locale.US))
    }

    @Test fun the_row_head_follows_the_state() {
        val now = tuesdayMorning
        val tonight = at(2026, 10, 13, 21, 0).toEpochSecond()
        assertEquals("Scheduled · Today 21:00", scheduledRowHead(item(state = "pending", sendAt = tonight), now, Locale.US))
        assertEquals("Sending…", scheduledRowHead(item(state = "sending", sendAt = tonight), now, Locale.US))
        assertEquals("Sent", scheduledRowHead(item(state = "sent", sendAt = tonight), now, Locale.US))
        assertEquals("Could not send", scheduledRowHead(item(state = "failed", sendAt = tonight), now, Locale.US))
        assertEquals(
            "Could not send — the relay was not running at the time",
            scheduledRowHead(item(state = "failed", sendAt = tonight, error = "the relay was not running at the time"), now, Locale.US),
        )
        // A send the relay cannot account for may have gone out: "Could not send" would say otherwise.
        assertEquals(
            "The relay cannot tell whether this was sent",
            scheduledRowHead(item(state = "failed", sendAt = tonight, error = SCHEDULED_OUTCOME_UNKNOWN), now, Locale.US),
        )
    }

    @Test fun only_a_plain_short_reason_is_repeated_on_a_row() {
        assertEquals("the relay was not running at the time", scheduledErrorShown("the relay was not running at the time"))
        assertEquals("BlueBubbles: no engine (code 7)", scheduledErrorShown("  BlueBubbles: no engine (code 7) "))
        assertNull(scheduledErrorShown(null))
        assertNull(scheduledErrorShown(""))
        assertNull(scheduledErrorShown("<b>failed</b>"))
        assertNull(scheduledErrorShown("failed\nsee log"))
        assertNull(scheduledErrorShown("x".repeat(81)))
    }

    @Test fun a_row_offers_what_its_state_allows_drop_first_send_last() {
        assertEquals(
            listOf(ScheduledRowAction.CANCEL, ScheduledRowAction.CHANGE_TIME, ScheduledRowAction.SEND_NOW),
            scheduledRowActions(ScheduledState.PENDING),
        )
        assertEquals(emptyList<ScheduledRowAction>(), scheduledRowActions(ScheduledState.SENDING))   // nothing can call a send back
        assertEquals(listOf(ScheduledRowAction.DISMISS), scheduledRowActions(ScheduledState.SENT))
        assertEquals(listOf(ScheduledRowAction.DELETE, ScheduledRowAction.RETRY), scheduledRowActions(ScheduledState.FAILED))
        assertEquals("Send now", ScheduledRowAction.SEND_NOW.label)
        assertEquals("Change time", ScheduledRowAction.CHANGE_TIME.label)
        assertEquals("Cancel", ScheduledRowAction.CANCEL.label)
        assertEquals("Retry", ScheduledRowAction.RETRY.label)
        assertEquals("Delete", ScheduledRowAction.DELETE.label)
        assertEquals("Dismiss", ScheduledRowAction.DISMISS.label)
    }

    @Test fun a_failed_row_offers_send_anyway_once_the_relay_cannot_tell_what_became_of_the_send() {
        val unsure = item(state = "failed", error = SCHEDULED_OUTCOME_UNKNOWN)
        assertTrue(scheduledOutcomeUnknown(unsure))
        assertEquals(
            listOf(ScheduledRowAction.DELETE, ScheduledRowAction.SEND_ANYWAY),
            scheduledRowActions(unsure.status, scheduledOutcomeUnknown(unsure)),
        )
        // Any other reason, or none: Retry, which runs under the item's own id and cannot send it twice.
        val plain = item(state = "failed", error = "the relay was not running at the time")
        assertFalse(scheduledOutcomeUnknown(plain))
        assertEquals(listOf(ScheduledRowAction.DELETE, ScheduledRowAction.RETRY), scheduledRowActions(plain.status, scheduledOutcomeUnknown(plain)))
        assertFalse(scheduledOutcomeUnknown(item(state = "failed")))
        // The relay's exact words only: a near miss is retried, and the answer to that Retry asks the question instead.
        assertFalse(scheduledOutcomeUnknown(item(state = "failed", error = "What became of the send is not known.")))
        assertFalse(scheduledOutcomeUnknown(item(state = "failed", error = " $SCHEDULED_OUTCOME_UNKNOWN")))
        // The words mean nothing on an item that is not failed.
        assertFalse(scheduledOutcomeUnknown(item(state = "pending", error = SCHEDULED_OUTCOME_UNKNOWN)))
        assertFalse(scheduledOutcomeUnknown(item(state = "sent", error = SCHEDULED_OUTCOME_UNKNOWN)))
        assertEquals("what became of the send is not known", SCHEDULED_OUTCOME_UNKNOWN)
        // The dialog's words, and the button that is the unsent rows' too.
        assertEquals("Send anyway", ScheduledRowAction.SEND_ANYWAY.label)
        assertEquals(SEND_ANYWAY_LABEL, ScheduledRowAction.SEND_ANYWAY.label)
        assertEquals("Send anyway?", SCHEDULED_SEND_ANYWAY_TITLE)
        assertEquals("The earlier try may have gone through. Sending again may deliver it twice.", SCHEDULED_SEND_ANYWAY_WARNING)
    }

    @Test fun a_chats_rows_are_its_items_soonest_first_less_the_dismissed_and_cut_with_a_count() {
        val items = listOf(
            item("s-3", sendAt = 3_000), item("s-1", sendAt = 1_000), item("other", sendAt = 500, chat = "iMessage;-;+15550100300"),
            item("s-2", state = "sent", sendAt = 2_000), item("s-4", sendAt = 4_000),
        )
        val rows = scheduledRowsFor(items, chat, dismissed = setOf("s-2"))
        assertEquals(listOf("s-1", "s-3", "s-4"), rows.shown.map { it.id })
        assertEquals(0, rows.more)
        val cut = scheduledRowsFor(items, chat, dismissed = emptySet(), limit = 2)
        assertEquals(listOf("s-1", "s-2"), cut.shown.map { it.id })
        assertEquals(2, cut.more)
        assertEquals(3, SCHEDULED_ROWS_SHOWN)
        assertEquals("1 more scheduled message", scheduledMoreLine(1))
        assertEquals("2 more scheduled messages", scheduledMoreLine(2))
        assertEquals(ScheduledRows(emptyList(), 0), scheduledRowsFor(emptyList(), chat, emptySet()))
    }

    @Test fun an_answered_item_takes_its_rows_place_and_a_cancelled_one_leaves() {
        val before = listOf(item("s-1", sendAt = 1_000), item("s-2", sendAt = 2_000))
        val moved = scheduledUpsert(before, item("s-1", sendAt = 3_000))
        assertEquals(listOf("s-2", "s-1"), moved.map { it.id })
        assertEquals(3_000L, moved.last().send_at)
        assertEquals(listOf("s-1", "s-2", "s-9"), scheduledUpsert(before, item("s-9", sendAt = 9_000)).map { it.id })
        assertEquals(listOf("s-2"), scheduledWithout(before, "s-1").map { it.id })
        assertEquals(before, scheduledWithout(before, "s-9"))
    }

    // ---- what the relay tells the app ----

    @Test fun the_push_says_sent_or_failed_and_names_the_chat() {
        assertEquals("Scheduled message sent to Alice Anders", scheduledPushText("sent", "Alice Anders"))
        assertEquals("Scheduled message to Alice Anders could not be sent — open to retry", scheduledPushText("failed", "Alice Anders"))
        // A chat with no name: the line still stands on its own.
        assertEquals("Scheduled message sent", scheduledPushText("sent", ""))
        assertEquals("Scheduled message could not be sent — open to retry", scheduledPushText("failed", "  "))
        // The relay pushes nothing else; a push with any other state has nothing to say.
        assertNull(scheduledPushText("pending", "Alice Anders"))
        assertNull(scheduledPushText("sending", "Alice Anders"))
        assertNull(scheduledPushText(null, "Alice Anders"))
        assertEquals("scheduled", PUSH_KIND_SCHEDULED)
    }

    @Test fun the_socket_frame_is_an_event_only_with_a_chat_and_an_id() {
        assertEquals(ScheduledEvent(chat, "s-1", ScheduledState.SENT), scheduledEventOf(chat, "s-1", "sent"))
        assertEquals(ScheduledState.PENDING, scheduledEventOf(chat, "s-1", "created")?.state)   // a state name the app does not model
        assertNull(scheduledEventOf(null, "s-1", "sent"))
        assertNull(scheduledEventOf("", "s-1", "sent"))
        assertNull(scheduledEventOf(chat, null, "sent"))
        assertNull(scheduledEventOf(chat, " ", "sent"))
    }

    // ---- what the owner is told ----

    @Test fun a_refusal_is_explained_by_its_status_and_never_by_its_body() {
        fun refused(code: Int) = ScheduleFailedException(code, "schedule HTTP $code")
        assertEquals("That scheduled message is gone — it was already sent or cancelled", scheduleFailureLine(refused(404)))
        assertEquals("That scheduled message has moved on — the list is refreshed", scheduleFailureLine(refused(409)))
        assertEquals("The relay refused it — check the time and the text", scheduleFailureLine(refused(422)))
        assertEquals("The relay refused the request", scheduleFailureLine(refused(403)))
        // What the exception carries is the status and a label, never the body.
        assertEquals(422, refused(422).httpCode)
        assertEquals("schedule HTTP 422", refused(422).message)
    }

    @Test fun no_answer_or_a_server_error_sends_the_owner_to_the_rows_before_trying_again() {
        // The relay may hold the item all the same, so the line reads like the outbox's "check the chat".
        val doubt = "check the scheduled rows before trying again"
        assertEquals("Couldn't reach the relay — $doubt", scheduleFailureLine(java.net.ConnectException("refused")))
        assertEquals("Couldn't reach the relay — $doubt", scheduleFailureLine(ScheduleFailedException(null, "no answer")))
        assertEquals("Couldn't reach the relay — $doubt", scheduleFailureLine(null))
        assertEquals("The relay's address answered with a server error — $doubt", scheduleFailureLine(ScheduleFailedException(502, "schedule HTTP 502")))
    }

    @Test fun a_409_on_a_failed_row_means_the_item_moved_on_like_on_any_other() {
        // The relay retries and deletes failed items, so its 409 to Retry or Delete on one says the item is
        // being sent or was sent since the row was read (another phone's Retry): the one line for that, and
        // no longer "the relay can't retry or delete a failed message".
        val moved = "That scheduled message has moved on — the list is refreshed"
        assertEquals(moved, scheduleFailureLine(ScheduleFailedException(409, "send scheduled now HTTP 409")))
        assertEquals(moved, scheduleFailureLine(ScheduleFailedException(409, "cancel scheduled HTTP 409")))
        assertEquals(moved, scheduleFailureLine(ScheduleFailedException(409, "reschedule HTTP 409")))
        assertEquals(
            "That scheduled message is gone — it was already sent or cancelled",
            scheduleFailureLine(ScheduleFailedException(404, "cancel scheduled HTTP 404")),
        )
    }

    // ---- the send id a text is scheduled under ----

    @Test fun send_later_again_on_the_same_words_carries_the_same_id() {
        val first = scheduleSendFor(null, chat, "hello") { "id-one-0001" }
        assertEquals("id-one-0001", first.id)
        // The answer to the first POST was lost; the relay may hold the item, and only a repeat of the id finds it.
        assertEquals(first, scheduleSendFor(first, chat, "hello") { "id-two-0002" })
    }

    @Test fun other_words_or_another_chat_are_another_scheduling() {
        val first = scheduleSendFor(null, chat, "hello") { "id-one-0001" }
        assertEquals("id-two-0002", scheduleSendFor(first, chat, "hello there") { "id-two-0002" }.id)
        assertEquals("id-two-0002", scheduleSendFor(first, "iMessage;-;+15550100300", "hello") { "id-two-0002" }.id)
    }

    @Test fun the_id_outlives_a_lost_answer_and_not_a_refusal() {
        val send = ScheduleSend("k", "id-one-0001")
        // Nothing certain came back: the relay may hold the item, so the next try must carry the id.
        assertEquals(send, scheduleSendAfter(send, java.net.SocketTimeoutException("timeout")))
        assertEquals(send, scheduleSendAfter(send, ScheduleFailedException(null, "no answer")))
        assertEquals(send, scheduleSendAfter(send, ScheduleFailedException(502, "schedule HTTP 502")))
        assertEquals(send, scheduleSendAfter(send, ScheduleFailedException(200, "schedule HTTP 200, not the relay's reply")))
        assertEquals(send, scheduleSendAfter(send, null))
        // A failure before anything left the phone: the id is kept too, since a repeat of it costs nothing.
        assertEquals(send, scheduleSendAfter(send, java.net.ConnectException("refused")))
        // The relay refused it and stored nothing: the id is spent, and the next try is a new one.
        assertNull(scheduleSendAfter(send, ScheduleFailedException(422, "schedule HTTP 422")))
        assertNull(scheduleSendAfter(send, ScheduleFailedException(404, "schedule HTTP 404")))
    }

    @Test fun the_toast_says_when_the_text_goes() {
        assertEquals("Scheduled for Tomorrow 09:00", scheduledToast(at(2026, 10, 14, 9, 0).toEpochSecond(), tuesdayMorning, Locale.US))
        assertEquals("Scheduled for Today 21:00", scheduledToast(at(2026, 10, 13, 21, 0).toEpochSecond(), tuesdayMorning, Locale.US))
    }

    // ---- the gate ----

    @Test fun send_later_is_offered_only_with_the_switch_on_and_the_relay_reporting_it() {
        assertTrue(SendLaterGate.offered(featureOn = true, relayReported = true))
        assertFalse(SendLaterGate.offered(featureOn = false, relayReported = true))      // the owner turned it off
        assertFalse(SendLaterGate.offered(featureOn = true, relayReported = false))      // the relay said it has none
        assertFalse(SendLaterGate.offered(featureOn = true, relayReported = null))       // an older relay never mentioned it
        assertFalse(SendLaterGate.offered(featureOn = false, relayReported = null))
    }

    @Test fun schedule_is_a_feature_name_the_relay_and_the_build_key_share() {
        assertEquals(Feature.SCHEDULE, Feature.byRelayName("schedule"))
        assertEquals("schedule", Feature.SCHEDULE.relayName)
        assertEquals("feature_schedule", Feature.SCHEDULE.prefsKey)
        assertEquals(setOf(Feature.SCHEDULE), parseFeatureList("schedule").on)
        assertEquals(mapOf(Feature.SCHEDULE to true), FeatureFlags.fromRelay(mapOf("schedule" to true)))
        // A stranger's build seeds its switch from a relay that reports it, like the other four.
        val stranger = FeatureFlags(BuildFeatureDefault(featuresPresent = false, featuresRaw = "", relayBuiltIn = false), FeatureFlags.InMemoryBacking())
        assertTrue(stranger.relayReported(mapOf("schedule" to true)))
        assertTrue(stranger.isOn(Feature.SCHEDULE))
        assertEquals(true, stranger.lastReported[Feature.SCHEDULE])
    }
}
