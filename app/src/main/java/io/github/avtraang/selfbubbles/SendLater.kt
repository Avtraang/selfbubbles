package io.github.avtraang.selfbubbles

// Send later: a text the relay keeps on the Mac and sends at a chosen time. The app's side
// of the /scheduled contract, as plain functions (SendLaterTest): the time presets and the
// bounds the relay enforces too, the request shapes (POST, PATCH, GET, DELETE, send_now),
// the item as the relay lists it, what a row above the composer says, the push the relay
// sends when an item is sent or fails, and the socket frame that moves a row. The requests
// themselves are made by Api (Imsg.kt); the rows are drawn by ScheduledStrip
// (SendLaterSheet.kt); ChatVM holds the open chat's list.
//
// Times are unix seconds, UTC, as the relay keeps them; the presets and the labels turn
// them into the phone's local time and back. A scheduled text lives on the relay, not in
// the outbox: the composer clears only once the relay has answered with the item, so a
// refused request loses no words.

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// ---- the bounds ----

/** The relay refuses a send time less than this far ahead (422); the app says so before asking. */
const val SCHEDULE_MIN_LEAD_SECONDS = 60L

/** ... and one more than this far ahead. */
const val SCHEDULE_MAX_LEAD_SECONDS = 30L * 86_400L

enum class ScheduleBound { OK, TOO_SOON, TOO_LATE }

/** Whether [sendAtSeconds] is a time the relay takes, judged from [nowSeconds]: the same rule as its own 422. */
fun scheduleBound(sendAtSeconds: Long, nowSeconds: Long): ScheduleBound = when {
    sendAtSeconds < nowSeconds + SCHEDULE_MIN_LEAD_SECONDS -> ScheduleBound.TOO_SOON
    sendAtSeconds > nowSeconds + SCHEDULE_MAX_LEAD_SECONDS -> ScheduleBound.TOO_LATE
    else -> ScheduleBound.OK
}

/** What the chooser says under a time it will not take; null for one it will. */
fun scheduleBoundLine(bound: ScheduleBound): String? = when (bound) {
    ScheduleBound.OK -> null
    ScheduleBound.TOO_SOON -> "Pick a time at least a minute from now"
    ScheduleBound.TOO_LATE -> "Pick a time within the next 30 days"
}

// ---- the clock ----

/**
 * Send later shows and asks for times on a 24-hour clock everywhere, whatever
 * the locale: the bubbles' timestamps already are, and the owner asked for it
 * on 2026-10-11 after a 12-hour picker's AM/PM landed a text twelve hours out
 * ("11:23" meant the morning, the picker said PM). The locale still names
 * weekdays and months.
 */
const val SEND_LATER_CLOCK_PATTERN = "HH:mm"

/** [time] as the chooser and the rows show it, on the 24-hour clock ([SEND_LATER_CLOCK_PATTERN]): "21:00", "09:05". */
fun clockLabel(time: LocalTime, locale: Locale): String =
    time.format(DateTimeFormatter.ofPattern(SEND_LATER_CLOCK_PATTERN, locale))

// ---- the presets ----

/** One row of the chooser: its label and the second it stands for. */
data class SendLaterPreset(val label: String, val sendAtSeconds: Long)

/** "Tonight" is nine in the evening, "Tomorrow" nine in the morning, local time. */
val SEND_LATER_TONIGHT: LocalTime = LocalTime.of(21, 0)
val SEND_LATER_MORNING: LocalTime = LocalTime.of(9, 0)

/** How far ahead "Tonight" must still be to be offered: the relay's minimum would do, but an hour is what the first row already offers. */
private const val TONIGHT_MIN_LEAD_SECONDS = 3_600L

/**
 * The chooser's fixed rows for [now] (the phone's zone rides in it): in an
 * hour; tonight at nine, when that is still an hour or more away; tomorrow at
 * nine. Local calendar days, so at half past eleven "In 1 hour" is after
 * midnight and "Tomorrow" is the day after that midnight, and on New Year's
 * Eve "Tomorrow" is next year. Every row is inside the relay's bounds.
 */
fun sendLaterPresets(now: ZonedDateTime, locale: Locale): List<SendLaterPreset> {
    val nowSeconds = now.toEpochSecond()
    val today = now.toLocalDate()
    val tonight = today.atTime(SEND_LATER_TONIGHT).atZone(now.zone)
    val tomorrow = today.plusDays(1).atTime(SEND_LATER_MORNING).atZone(now.zone)
    return listOfNotNull(
        SendLaterPreset("In 1 hour", nowSeconds + 3_600L),
        tonight.takeIf { it.toEpochSecond() - nowSeconds >= TONIGHT_MIN_LEAD_SECONDS }
            ?.let { SendLaterPreset("Tonight ${clockLabel(it.toLocalTime(), locale)}", it.toEpochSecond()) },
        SendLaterPreset("Tomorrow ${clockLabel(tomorrow.toLocalTime(), locale)}", tomorrow.toEpochSecond()),
    )
}

/** A calendar day as Material's DatePicker names one: midnight of that day in UTC, in milliseconds. */
fun dateUtcMillisOf(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The calendar day behind a DatePicker value ([dateUtcMillisOf] the other way round). */
fun dateOfUtcMillis(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/** Which days the DatePicker lets through: today and the next thirty, the relay's outer bound. */
fun selectableScheduleDate(utcMillis: Long, today: LocalDate): Boolean {
    val day = dateOfUtcMillis(utcMillis)
    return !day.isBefore(today) && !day.isAfter(today.plusDays(SCHEDULE_MAX_LEAD_SECONDS / 86_400L))
}

/**
 * The second a picked day and time stand for: the DatePicker's day (midnight UTC,
 * [dateOfUtcMillis]) at the TimePicker's [hour] and [minute] in the phone's
 * [zone]. A time that falls into a clock change's gap moves forward with it.
 */
fun pickedSendAt(dateUtcMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long =
    dateOfUtcMillis(dateUtcMillis).atTime(hour, minute).atZone(zone).toEpochSecond()

// ---- the item as the relay lists it ----

enum class ScheduledState { PENDING, SENDING, SENT, FAILED }

/**
 * The relay's "state" as the app reads it. A spelling the app does not know
 * (a newer relay) is treated as pending: the row keeps its actions, and the
 * relay's 409 to one that no longer applies is a toast, not a lost row.
 */
fun scheduledStateOf(raw: String?): ScheduledState = when (raw) {
    "sending" -> ScheduledState.SENDING
    "sent" -> ScheduledState.SENT
    "failed" -> ScheduledState.FAILED
    else -> ScheduledState.PENDING
}

/**
 * One scheduled text, in the relay's own names. Only [id], [chat_guid], [text]
 * and [send_at] are required: everything else has the default an older or a
 * terser relay would mean. A finished item stays listed for a day and then
 * goes; [sent_at], [sent_via] and [error] are set as it finishes.
 */
@Serializable
data class ScheduledItem(
    val id: String,
    val chat_guid: String,
    val text: String,
    val send_at: Long,
    val created_at: Long = 0,
    val client_id: String = "",
    val reply_to_guid: String? = null,
    val effect: String? = null,
    val state: String = "pending",
    val sent_at: Long? = null,
    val sent_via: String? = null,
    val error: String? = null,
) {
    val status: ScheduledState get() = scheduledStateOf(state)

    /** Sent or failed: the relay is done with it, and the row only reports. */
    val finished: Boolean get() = status == ScheduledState.SENT || status == ScheduledState.FAILED
}

@Serializable private data class ScheduledItemResp(val ok: Boolean = false, val item: ScheduledItem? = null, val duplicate: Boolean = false)

/**
 * The items in a `GET /scheduled` body, soonest first. Each is read on its own,
 * so one the app cannot read costs that one and not the list; a body that is
 * not `{"items":[...]}` is an empty list.
 */
fun scheduledItemsOf(body: String): List<ScheduledItem> = runCatching {
    val items = (json.parseToJsonElement(body) as? JsonObject)?.get("items")?.jsonArray ?: return emptyList()
    items.mapNotNull { runCatching { json.decodeFromJsonElement<ScheduledItem>(it) }.getOrNull() }.sortedBy { it.send_at }
}.getOrDefault(emptyList())

/** The item in a `{"ok":true,"item":{...}}` reply (a POST, a PATCH, a send_now); null when the body carries none that reads. */
fun scheduledItemOf(body: String): ScheduledItem? =
    runCatching { json.decodeFromString<ScheduledItemResp>(body).item }.getOrNull()

// ---- the requests ----

private val JSON_MEDIA = "application/json".toMediaType()

@Serializable private data class ScheduleReq(
    val chat_guid: String, val text: String, val send_at: Long, val client_id: String,
    // Null defaults are left out of the JSON: the relay's optional keys are absent, not null.
    val reply_to_guid: String? = null, val effect: String? = null,
)

@Serializable private data class RescheduleReq(val text: String? = null, val send_at: Long? = null)

/**
 * `POST /scheduled` on the relay at [base]: [text] into [chatGuid] at
 * [sendAtSeconds], under the send id [clientId] its delivery will run under
 * (a fresh one from [newSendId]; the relay answers a repeat of it with the
 * item it already has). [replyTo] and [effect] travel only when set. Written
 * once ([sentOnce]) like every request that ends in a message.
 */
fun scheduleRequest(
    base: String, chatGuid: String, text: String, sendAtSeconds: Long, clientId: String, replyTo: String?, effect: String?,
): Request {
    val body = json.encodeToString(ScheduleReq(chatGuid, text, sendAtSeconds, clientId, replyTo, effect)).toRequestBody(JSON_MEDIA).sentOnce()
    return Request.Builder().url("$base/scheduled").post(body).build()
}

/** `GET /scheduled?chat_guid=` for one chat, or every chat's items without [chatGuid]. The builder encodes the guid's ';' and '+'. */
fun scheduledListRequest(base: String, chatGuid: String?): Request {
    val url = base.toHttpUrl().newBuilder().addPathSegment("scheduled")
        .apply { if (chatGuid != null) addQueryParameter("chat_guid", chatGuid) }
        .build()
    return Request.Builder().url(url).build()
}

/** `/scheduled/{id}`; the id is a path segment, so whatever the relay put in it is encoded. */
private fun scheduledItemUrl(base: String, id: String): HttpUrl =
    base.toHttpUrl().newBuilder().addPathSegment("scheduled").addPathSegment(id).build()

/** `PATCH /scheduled/{id}` with the keys that are set and no others: a null [sendAtSeconds] or [text] leaves that field as it is. */
fun rescheduleRequest(base: String, id: String, sendAtSeconds: Long?, text: String?): Request {
    val body = json.encodeToString(RescheduleReq(text, sendAtSeconds)).toRequestBody(JSON_MEDIA).sentOnce()
    return Request.Builder().url(scheduledItemUrl(base, id)).patch(body).build()
}

/** `DELETE /scheduled/{id}`: drops a waiting or a failed item (the relay says 409 for one it is sending or has sent). */
fun cancelScheduledRequest(base: String, id: String): Request =
    Request.Builder().url(scheduledItemUrl(base, id)).delete().build()

@Serializable private data class SendNowReq(val client_id: String)

/**
 * `POST /scheduled/{id}/send_now`: delivered at once, the way the scheduler
 * would, under the item's own send id, so a Retry of a failed item can never
 * deliver it twice. With [clientId] (a fresh one from [newSendId]) it is "Send
 * anyway": the relay puts that id in the item's place and sends under it, which
 * it allows for a failed item only. The body carries `client_id` only when one
 * is given: an empty body is the plain Retry. A send, so written once.
 */
fun sendScheduledNowRequest(base: String, id: String, clientId: String? = null): Request {
    val body = if (clientId == null) ByteArray(0).toRequestBody() else json.encodeToString(SendNowReq(clientId)).toRequestBody(JSON_MEDIA)
    return Request.Builder().url(scheduledItemUrl(base, id).newBuilder().addPathSegment("send_now").build())
        .post(body.sentOnce())
        .build()
}

/**
 * A `/scheduled` call that did not end as asked: the status that answered
 * ([httpCode]; null when nothing did), never the body, which may be a page
 * served in the relay's place.
 */
class ScheduleFailedException(val httpCode: Int?, detail: String) : IOException(detail)

// ---- the send id a text is scheduled under ----
// The POST carries the send id its delivery will later run under, and the relay answers a
// repeat of that id with the item it already holds ("duplicate") instead of a second one.
// That only helps if the id outlives one try: a POST whose answer was lost (the read timed
// out, the connection reset after the request had left) may well have left the item on the
// relay, and "Send later" again on the same words must carry the same id, or the text goes
// twice at the chosen time. So the id is kept with the words, as the first text of a new
// conversation keeps its own (NewChatSend, SendIds.kt), until the relay has answered.

/** The scheduling the composer is busy with: what it is ([key]: the chat and the words) and its send [id]. */
data class ScheduleSend(val key: String, val id: String)

private fun scheduleKey(chatGuid: String, text: String): String = chatGuid + "\u0001" + text

/** The send for [text] into [chatGuid]: [previous] when it is the same one, else a new one under [newId]. */
fun scheduleSendFor(previous: ScheduleSend?, chatGuid: String, text: String, newId: () -> String): ScheduleSend {
    val key = scheduleKey(chatGuid, text)
    return if (previous != null && previous.key == key) previous else ScheduleSend(key, newId())
}

/**
 * What is kept of [send] after a try that failed with [error]: nothing after a
 * refusal the relay answered with a 4xx, since it stored no item under the id;
 * the send itself after anything else (no answer, a lost connection, a 5xx, a
 * page in the relay's place), when the relay may hold the item and the same id
 * is what makes the next POST harmless.
 */
fun scheduleSendAfter(send: ScheduleSend, error: Throwable?): ScheduleSend? =
    if ((error as? ScheduleFailedException)?.httpCode in 400..499) null else send

/**
 * The toast for a scheduling call that failed. The relay's statuses say what
 * happened: 404 and 409 mean the item moved on without this app hearing (the
 * socket was down, or another phone acted), 422 that it would not take the
 * time or the text. A 409 reads the same on a failed row: the relay retries
 * and deletes failed items, so its 409 to one means the item is being sent or
 * was sent since the row was read (a Retry from another phone, say). No
 * answer, or a 5xx, leaves the relay's state in doubt, so the owner is sent to
 * the rows, which are read again, before trying again, as the outbox's lines
 * do for a send. The body is never shown.
 */
fun scheduleFailureLine(error: Throwable?): String {
    val code = (error as? ScheduleFailedException)?.httpCode
    return when {
        code == 404 -> "That scheduled message is gone — it was already sent or cancelled"
        code == 409 -> "That scheduled message has moved on — the list is refreshed"
        code == 422 -> "The relay refused it — check the time and the text"
        code == null -> "Couldn't reach the relay — check the scheduled rows before trying again"
        code >= 500 -> "The relay's address answered with a server error — check the scheduled rows before trying again"
        else -> "The relay refused the request"
    }
}

/** The toast after the relay took a text: when it will go. */
fun scheduledToast(sendAtSeconds: Long, now: ZonedDateTime, locale: Locale): String =
    "Scheduled for ${scheduledWhenLabel(sendAtSeconds, now, locale)}"

// ---- the rows above the composer ----

/** A day this many days ahead or fewer is named by its weekday ("Tue"); beyond that, a weekday alone would not say which week. */
const val SCHEDULED_WEEKDAY_DAYS = 6L

/**
 * When a row's text goes, in the phone's zone, on the 24-hour clock
 * ([clockLabel]) whatever the phone's 12/24-hour setting: "Today 21:00",
 * "Tomorrow 09:00", "Tue 09:00" within the week, "Oct 21, 09:00" past it (the
 * relay takes up to thirty days). Calendar days, counted in [now]'s zone, so
 * "Tomorrow" is after the next local midnight however near it is.
 */
fun scheduledWhenLabel(sendAtSeconds: Long, now: ZonedDateTime, locale: Locale): String {
    val at = Instant.ofEpochSecond(sendAtSeconds).atZone(now.zone)
    val time = clockLabel(at.toLocalTime(), locale)
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        in 2L..SCHEDULED_WEEKDAY_DAYS -> "${at.format(DateTimeFormatter.ofPattern("EEE", locale))} $time"
        else -> "${at.format(DateTimeFormatter.ofPattern("MMM d", locale))}, $time"
    }
}

/** The relay's reason for a failure as a row may repeat it: a short plain line, nothing that could be a page or a script. */
private val SAFE_REASON = Regex("^[A-Za-z0-9 ,.:;'’()\\-]{1,80}$")

/** [error] when it is a plain short reason the row can show; null otherwise (the row then says only that it could not send). */
fun scheduledErrorShown(error: String?): String? = error?.trim()?.takeIf { SAFE_REASON.matches(it) }

/**
 * The relay's `error` on a failed item whose send it cannot account for: an
 * engine answered with a 5xx or not at all, which under the relay's send-id
 * rule means "may have been sent" (SendIds.kt). The relay will not send that
 * id again, so a Retry answers 200 with the item still failed and this exact
 * reason, and the only way to send is a new id: "Send anyway".
 */
const val SCHEDULED_OUTCOME_UNKNOWN = "what became of the send is not known"

/** True for a failed [item] the relay will not retry under its id ([SCHEDULED_OUTCOME_UNKNOWN]): on the row before a tap, or in the answer to Retry. */
fun scheduledOutcomeUnknown(item: ScheduledItem): Boolean =
    item.status == ScheduledState.FAILED && item.error == SCHEDULED_OUTCOME_UNKNOWN

const val SCHEDULED_SEND_ANYWAY_TITLE = "Send anyway?"
const val SCHEDULED_SEND_ANYWAY_WARNING = "The earlier try may have gone through. Sending again may deliver it twice."

/**
 * The first line of a row: when a waiting text goes, "Sending…" while the
 * relay is on it, "Sent" once it did, and for a failure why, when the relay
 * gave a reason the row can repeat ([scheduledErrorShown]). A failure the
 * relay cannot account for is not called "Could not send", since it may have:
 * it reads as the unsent rows' line for the same doubt.
 */
fun scheduledRowHead(item: ScheduledItem, now: ZonedDateTime, locale: Locale): String = when (item.status) {
    ScheduledState.PENDING -> "Scheduled · ${scheduledWhenLabel(item.send_at, now, locale)}"
    ScheduledState.SENDING -> "Sending…"
    ScheduledState.SENT -> "Sent"
    ScheduledState.FAILED ->
        if (scheduledOutcomeUnknown(item)) "The relay cannot tell whether this was sent"
        else scheduledErrorShown(item.error)?.let { "Could not send — $it" } ?: "Could not send"
}

/** What a row offers. */
enum class ScheduledRowAction(val label: String) {
    /** Delivered at once, as the scheduler would deliver it. */
    SEND_NOW("Send now"),
    /** The chooser again, for a new time. */
    CHANGE_TIME("Change time"),
    /** The text goes, from the relay too. */
    CANCEL("Cancel"),
    /** A failed item: delivered at once, under its own id, so never twice. */
    RETRY("Retry"),
    /** A failed item the relay cannot account for: delivered under a new id, which may make a second copy; asked first ([SCHEDULED_SEND_ANYWAY_WARNING]). */
    SEND_ANYWAY(SEND_ANYWAY_LABEL),
    /** A failed item: dropped from the relay. */
    DELETE("Delete"),
    /** A sent item: its row goes from this screen; the relay drops the item itself after a day. */
    DISMISS("Dismiss"),
}

/**
 * The actions for an item in [status], left to right as the row shows them:
 * the one that drops the text first, the one that sends it last, as the unsent
 * rows order "Discard" and "Send again". None while the relay is sending it,
 * since nothing can call that back. A failed item offers Retry, which cannot
 * send it twice, unless the relay has already said it cannot tell what became
 * of the send ([outcomeUnknown], [scheduledOutcomeUnknown]): a Retry would
 * only be told so again, so the row offers "Send anyway" straight off, as an
 * unsent row does ([unsentSendAction]).
 */
fun scheduledRowActions(status: ScheduledState, outcomeUnknown: Boolean = false): List<ScheduledRowAction> = when (status) {
    ScheduledState.PENDING -> listOf(ScheduledRowAction.CANCEL, ScheduledRowAction.CHANGE_TIME, ScheduledRowAction.SEND_NOW)
    ScheduledState.SENDING -> emptyList()
    ScheduledState.SENT -> listOf(ScheduledRowAction.DISMISS)
    ScheduledState.FAILED -> listOf(ScheduledRowAction.DELETE, if (outcomeUnknown) ScheduledRowAction.SEND_ANYWAY else ScheduledRowAction.RETRY)
}

/** How many scheduled rows a chat shows above its composer; the rest are one line, as the unsent rows do it ([UNSENT_ROWS_SHOWN]). */
const val SCHEDULED_ROWS_SHOWN = 3

/** The rows above a chat's composer: [shown], soonest first, and how many [more] wait behind them. */
data class ScheduledRows(val shown: List<ScheduledItem>, val more: Int)

/** [items] of [chatGuid], less the sent ones the owner [dismissed], soonest first and cut to [limit]. */
fun scheduledRowsFor(items: List<ScheduledItem>, chatGuid: String, dismissed: Set<String>, limit: Int = SCHEDULED_ROWS_SHOWN): ScheduledRows {
    val mine = items.filter { it.chat_guid == chatGuid && it.id !in dismissed }.sortedBy { it.send_at }
    return ScheduledRows(mine.take(limit), (mine.size - limit).coerceAtLeast(0))
}

fun scheduledMoreLine(more: Int): String = if (more == 1) "1 more scheduled message" else "$more more scheduled messages"

/** [items] with [item] in place of the one with its id (or added), soonest first: what the relay answered to an action. */
fun scheduledUpsert(items: List<ScheduledItem>, item: ScheduledItem): List<ScheduledItem> =
    (items.filterNot { it.id == item.id } + item).sortedBy { it.send_at }

/** [items] without [id]: a cancelled or deleted one. */
fun scheduledWithout(items: List<ScheduledItem>, id: String): List<ScheduledItem> = items.filterNot { it.id == id }

// ---- what the relay tells the app ----

/** The relay's WebSocket frame on every change of an item: `{"type":"scheduled","chat_guid":…,"id":…,"state":…}`. */
data class ScheduledEvent(val chatGuid: String, val id: String, val state: ScheduledState)

/** The frame's fields as one event; null when the frame names no chat or no item. */
fun scheduledEventOf(chatGuid: String?, id: String?, state: String?): ScheduledEvent? {
    if (chatGuid.isNullOrBlank() || id.isNullOrBlank()) return null
    return ScheduledEvent(chatGuid, id, scheduledStateOf(state))
}

/** The `kind` of the relay's push about an item that was sent or failed. */
const val PUSH_KIND_SCHEDULED = "scheduled"

/**
 * The notification's line for a push of kind "scheduled" with this [state]
 * about the chat named [chatName]: sent, or failed with the way back (the
 * chat's rows offer Retry). Null for any other state, which the relay does not
 * push and the app would have nothing to say about. No message text: the
 * notification may show on the lock screen.
 */
fun scheduledPushText(state: String?, chatName: String): String? {
    val to = chatName.trim()
    return when (scheduledStateOf(state)) {
        ScheduledState.SENT -> if (to.isEmpty()) "Scheduled message sent" else "Scheduled message sent to $to"
        ScheduledState.FAILED ->
            if (to.isEmpty()) "Scheduled message could not be sent — open to retry"
            else "Scheduled message to $to could not be sent — open to retry"
        else -> null
    }
}
