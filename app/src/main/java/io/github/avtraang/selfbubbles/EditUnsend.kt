package io.github.avtraang.selfbubbles

// Editing and unsending ("Undo Send") one of the owner's own recent iMessages: how the
// relay's answer to `POST /edit` and `POST /unsend` is read, what the owner is told when
// the change was refused or cannot be confirmed, which messages are offered either action,
// the composer's edit mode, and what the app remembers about a relay that cannot do one of
// them. Plain Kotlin (no Android, no Compose), so EditUnsendTest pins every table here.
// The requests themselves are in Imsg.kt (Api.edit, Api.unsend), the screen in
// ConversationScreen.kt, and ChatVM holds the state.
//
// Neither action goes through the outbox: a change is one request, made while the owner
// looks at the chat, and its result is the relay's own update frame for that message.

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// ---- what the relay answered ----

/** The two changes to a sent message. [capability] is its name in the relay's `/health` "capabilities" list. */
enum class ChangeAction(val capability: String) {
    EDIT("edit"),
    UNSEND("unsend"),
}

/** Why a change certainly did not happen. */
enum class ChangeRefusal {
    /** 409: Apple's window for this change is over (2 minutes to unsend, 15 to edit). */
    TOO_LATE,
    /** 409: the message was unsent before this request. */
    ALREADY_UNSENT,
    /** 409: the message has had its five edits. */
    EDIT_LIMIT,
    /** A 409 the app has no wording for. */
    CONFLICT,
    /** 404 from the relay's own route: it holds no message with that guid. */
    UNKNOWN_MESSAGE,
    /** The relay's 404 "Not Found" or 405: a relay from before these routes existed. */
    RELAY_TOO_OLD,
    /** 403 from the relay: the message is not the owner's. */
    NOT_YOURS,
    /** The relay's 501: it has no engine for this change, or this chat cannot take it. */
    NOT_SUPPORTED,
    /** 422, or any other 4xx: the relay refused the request as it was written. */
    BAD_REQUEST,
    /** The relay's own 502 saying so: it watched the Mac's message database and saw no change. */
    MAC_DID_NOT_APPLY,
    /** 401, a redirect, or a web page or a non-relay 403 in the relay's place: the token or Cloudflare Access. */
    AUTH,
    /** Something that is not the relay answered at its address. */
    NOT_A_RELAY,
    /** The request never left the phone: no network, no address, no trusted certificate, no relay configured. */
    UNREACHABLE,
}

/** Why the app cannot say whether a change happened. */
enum class ChangeDoubt {
    /** No answer in time (the app's own wait, or a 408/504/522/524 from the route), or a 2xx that does not say `ok`. */
    NO_ANSWER,
    /** The connection failed after the request had left. */
    CONNECTION_LOST,
    /**
     * A 5xx that is not the relay's own verdict: the route answering for a relay
     * it could not hear, or the relay saying its engine failed without saying
     * what the Mac's database shows.
     */
    SERVER_ERROR,
}

/**
 * How one edit or unsend ended. [Done]: the relay confirmed the change in the
 * Mac's message database ([Done.unchanged]: an edit whose text was already the
 * message's). [Refused]: nothing changed, for certain. [Unconfirmed]: the
 * request left the phone, or may have, and nothing certain came back, so the
 * change may or may not have happened and the owner is never told it failed.
 */
sealed interface ChangeOutcome {
    data class Done(val unchanged: Boolean = false) : ChangeOutcome
    data class Refused(val reason: ChangeRefusal) : ChangeOutcome
    data class Unconfirmed(val reason: ChangeDoubt) : ChangeOutcome
}

/** The relay's `detail` text in an error reply (FastAPI's `{"detail": "..."}`); null when there is none or it is not text. */
private fun detailOf(reply: JsonObject?): String? =
    (reply?.get("detail") as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun flag(reply: JsonObject, name: String): Boolean = (reply[name] as? JsonPrimitive)?.booleanOrNull == true

/** Which 409 this is, by the words of the relay's `detail` that do not change with its phrasing around them. */
private fun conflictFor(detail: String?): ChangeRefusal {
    val said = detail.orEmpty().lowercase()
    return when {
        "too late" in said -> ChangeRefusal.TOO_LATE
        "already unsent" in said -> ChangeRefusal.ALREADY_UNSENT
        "edited" in said && "times" in said -> ChangeRefusal.EDIT_LIMIT
        else -> ChangeRefusal.CONFLICT
    }
}

/**
 * True for the relay's 502 that says the Mac's message database was watched
 * and shows no change ("the Mac did not apply the change"). Its other 502, an
 * engine that failed or gave no answer in time, says nothing of the database:
 * the change may still land a moment later.
 */
private fun saysNotApplied(detail: String?): Boolean = "did not apply" in detail.orEmpty().lowercase()

/**
 * Classifies a reply to `POST /edit` or `POST /unsend`. Only the relay's own
 * `{"ok": true}` is a change: a 2xx web page is Cloudflare Access showing its
 * login page (the shared client follows the redirect), a 2xx that is not a
 * JSON object is another service, and a JSON object that does not say `ok` is
 * no confirmation of anything. A refusal is certain only where the relay (or
 * what stands in front of it) answered before anything was changed; a 5xx page
 * from the route, a timeout status, a 502 that is not the relay's own JSON and
 * a 502 of the relay's that does not say the database shows no change
 * ([saysNotApplied]) leave the change in doubt. The two verdicts the app
 * remembers for the session ([hidesFromNowOn]) are taken from the relay's own
 * JSON only: a 404, a 405 or a 501 in any other form is whatever stands at
 * the address, and says nothing about what the relay can do. The body is read
 * for its JSON keys and never repeated.
 */
fun changeOutcomeFor(httpCode: Int, contentType: String?, body: String): ChangeOutcome {
    val html = looksLikeHtml(contentType, body)
    val reply = if (html) null else runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    fun refused(reason: ChangeRefusal) = ChangeOutcome.Refused(reason)
    fun unconfirmed(reason: ChangeDoubt) = ChangeOutcome.Unconfirmed(reason)
    return when {
        httpCode in 200..299 -> when {
            html -> refused(ChangeRefusal.AUTH)
            reply == null -> refused(ChangeRefusal.NOT_A_RELAY)
            flag(reply, "ok") -> ChangeOutcome.Done(unchanged = flag(reply, "unchanged"))
            else -> unconfirmed(ChangeDoubt.NO_ANSWER)
        }
        httpCode in 300..399 || httpCode == 401 -> refused(ChangeRefusal.AUTH)
        httpCode == 408 || httpCode == 504 || httpCode == 522 || httpCode == 524 -> unconfirmed(ChangeDoubt.NO_ANSWER)
        // The relay answers JSON. A page is the route's: Cloudflare Access on a 403, an error page on a 5xx.
        html -> when {
            httpCode == 403 -> refused(ChangeRefusal.AUTH)
            httpCode in 400..499 -> refused(ChangeRefusal.NOT_A_RELAY)
            else -> unconfirmed(ChangeDoubt.SERVER_ERROR)
        }
        // A 403 that is not the relay's JSON is the route refusing (Cloudflare answers some blocks in plain text).
        httpCode == 403 -> refused(if (reply != null) ChangeRefusal.NOT_YOURS else ChangeRefusal.AUTH)
        httpCode == 404 -> when {
            // Not the relay's JSON: whatever stands at the address has no such page, which says nothing about the relay.
            reply == null -> refused(ChangeRefusal.NOT_A_RELAY)
            // What a FastAPI relay answers for a route it does not have.
            detailOf(reply)?.trim().equals("not found", ignoreCase = true) -> refused(ChangeRefusal.RELAY_TOO_OLD)
            // Its own route's 404: "unknown message", or any other detail.
            else -> refused(ChangeRefusal.UNKNOWN_MESSAGE)
        }
        // FastAPI answers JSON for a method a route does not take; anything else is not the relay's word.
        httpCode == 405 -> refused(if (reply != null) ChangeRefusal.RELAY_TOO_OLD else ChangeRefusal.NOT_A_RELAY)
        httpCode == 409 -> refused(conflictFor(detailOf(reply)))
        httpCode in 400..499 -> refused(ChangeRefusal.BAD_REQUEST)
        // "Cannot" is the relay's to say. A 501 in any other form is the route's, and leaves the change in doubt like its other 5xx.
        httpCode == 501 && reply != null -> refused(ChangeRefusal.NOT_SUPPORTED)
        // Certain only when the relay says the Mac's database shows no change. Its 502 for an engine that failed
        // or timed out, and a 502 from the route, confirm nothing: the change may land all the same.
        httpCode == 502 && saysNotApplied(detailOf(reply)) -> refused(ChangeRefusal.MAC_DID_NOT_APPLY)
        else -> unconfirmed(ChangeDoubt.SERVER_ERROR)
    }
}

/**
 * Classifies whatever the request threw, by the rules a send is judged by
 * ([unsentWhyFor]): only a failure before anything was written is certain. A
 * timeout, and a connection that ended after the request left, are not.
 */
fun changeOutcomeFor(error: Throwable?): ChangeOutcome = when (unsentWhyFor(error)) {
    UnsentWhy.UNREACHABLE, UnsentWhy.NO_TRUSTED_CERT, UnsentWhy.NOT_BUILT -> ChangeOutcome.Refused(ChangeRefusal.UNREACHABLE)
    UnsentWhy.CONNECTION_LOST -> ChangeOutcome.Unconfirmed(ChangeDoubt.CONNECTION_LOST)
    UnsentWhy.SERVER_ERROR -> ChangeOutcome.Unconfirmed(ChangeDoubt.SERVER_ERROR)
    else -> ChangeOutcome.Unconfirmed(ChangeDoubt.NO_ANSWER)
}

// ---- what the owner is told ----

/** The toast for a change that ended without a certain answer. It never says the change failed: it may not have. */
const val CHANGE_UNCONFIRMED_MESSAGE = "No answer from the relay — check the chat"

/** The toast for [action] refused for [reason]. Names no host, no chat and nothing from a reply. */
fun changeRefusalMessage(action: ChangeAction, reason: ChangeRefusal): String {
    val unsend = action == ChangeAction.UNSEND
    return when (reason) {
        ChangeRefusal.TOO_LATE ->
            if (unsend) "Too late to unsend — Apple allows 2 minutes" else "Too late to edit — Apple allows 15 minutes"
        ChangeRefusal.ALREADY_UNSENT -> "This message was already unsent"
        ChangeRefusal.EDIT_LIMIT -> "This message has been edited 5 times already"
        ChangeRefusal.CONFLICT -> if (unsend) "This message can't be unsent now" else "This message can't be edited now"
        ChangeRefusal.UNKNOWN_MESSAGE -> "The relay doesn't know this message"
        ChangeRefusal.RELAY_TOO_OLD -> "Update the relay to edit or unsend"
        ChangeRefusal.NOT_YOURS -> if (unsend) "Only your own messages can be unsent" else "Only your own messages can be edited"
        ChangeRefusal.NOT_SUPPORTED -> "This chat can't edit or unsend messages"
        ChangeRefusal.BAD_REQUEST -> "The relay refused the request"
        ChangeRefusal.MAC_DID_NOT_APPLY -> "The Mac did not apply the change"
        ChangeRefusal.AUTH -> "The relay or Cloudflare Access rejected the request"
        ChangeRefusal.NOT_A_RELAY -> "The address did not answer like a relay"
        ChangeRefusal.UNREACHABLE -> "Can't reach the relay"
    }
}

/** What the owner reads about how [action] ended; null when it was done and there is nothing to say. */
fun changeFailureMessage(action: ChangeAction, outcome: ChangeOutcome): String? = when (outcome) {
    is ChangeOutcome.Done -> null
    is ChangeOutcome.Refused -> changeRefusalMessage(action, outcome.reason)
    is ChangeOutcome.Unconfirmed -> CHANGE_UNCONFIRMED_MESSAGE
}

/**
 * The toast for how [action] ended; null when it was done. [otherChat] is null
 * while the message's chat is the one on screen, and the line is then
 * [changeFailureMessage]'s. Otherwise it is that chat's title (blank when it
 * has none): the answer came after the chat was left (an edit may take the Mac
 * twenty seconds), and a line shown over the list or over another chat names
 * the chat it is about, as an unsent text's does ([unsentLine]), so that
 * "check the chat" is not read as being about the one on screen.
 */
fun changeToast(action: ChangeAction, outcome: ChangeOutcome, otherChat: String? = null): String? {
    val line = changeFailureMessage(action, outcome) ?: return null
    if (otherChat == null) return line
    val named = otherChat.isNotBlank()
    if (outcome is ChangeOutcome.Unconfirmed) {
        return CHANGE_UNCONFIRMED_MESSAGE.removeSuffix("the chat") + if (named) "the chat with $otherChat" else "that chat"
    }
    val what = if (action == ChangeAction.UNSEND) "Undo Send" else "Edit"
    return what + (if (named) " in the chat with $otherChat" else " in another chat") + ": " + line
}

/** The logcat line for a change that was not confirmed: the action and the verdict's names. Never the text, a guid or the chat. */
fun changeLogLine(action: ChangeAction, outcome: ChangeOutcome): String = action.capability + when (outcome) {
    is ChangeOutcome.Done -> if (outcome.unchanged) " done (unchanged)" else " done"
    is ChangeOutcome.Refused -> " refused (${outcome.reason.name})"
    is ChangeOutcome.Unconfirmed -> " unconfirmed (${outcome.reason.name})"
}

// ---- which messages are offered a change ----

/** Apple's window for unsending, counted from the send. */
const val UNSEND_WINDOW_SECONDS = 2 * 60L
/** "Undo Send" stops being offered this much before the window ends: the request still has to reach the Mac. */
const val UNSEND_MARGIN_SECONDS = 10L
/** Apple's window for editing, counted from the send. */
const val EDIT_WINDOW_SECONDS = 15 * 60L
/** "Edit" stops being offered this much before the window ends: the text still has to be typed, and the Mac takes its time. */
const val EDIT_MARGIN_SECONDS = 20L

/** The placeholder Messages keeps in a text for each attachment. */
private const val ATTACHMENT_MARK = '￼'

/** A chat or message identifier of the Google Messages bridge. */
private fun isBridgeId(id: String?): Boolean = id?.startsWith("bp:") == true

/**
 * True for a message with nothing left to show, which is what an unsent
 * message is once the relay's update arrives: no words, no listed attachment,
 * no link card. Those three are all a bubble draws; the Mac's attachment flag
 * draws nothing and may outlive the unsend.
 */
fun isEmptied(m: Msg): Boolean =
    m.text.orEmpty().replace(ATTACHMENT_MARK.toString(), "").isBlank() && m.attachments.isEmpty() && m.link == null

/**
 * Whether the bubble of [m] carries the "Edited" caption: a message with an
 * edit date, except one of the owner's own with nothing left to show. The Mac
 * stamps the edit date on a message that was unsent as well, so without the
 * exception every Undo Send would end as a lone "Edited" under the space where
 * the bubble was. A message someone else unsent keeps the caption it always
 * had: it is the one trace that something was there.
 */
fun showsEditedCaption(m: Msg): Boolean = m.date_edited != null && !(m.is_from_me && isEmptied(m))

/**
 * Whether the bubble of [m] says "Unsending…": its Undo Send is on its way, or
 * was confirmed and the relay's update has not emptied the bubble yet
 * ([inFlight], which ChatVM holds until then). Not once there is nothing left
 * to unsend.
 */
fun showsUnsending(m: Msg, inFlight: Set<String>): Boolean = m.guid in inFlight && !isEmptied(m)

/**
 * The owner's own message in an iMessage chat, with a guid to name it by.
 * Neither a text thread (SMS/RCS on the Mac, Google Messages) nor a tapback.
 */
private fun isOwnIMessage(m: Msg, thread: Thread?): Boolean =
    m.is_from_me && m.guid.isNotBlank() &&
        !isTextThread(thread) && !isTextMsg(m) &&
        !isBridgeId(m.guid) && !isBridgeId(m.chat_guid) && !isBridgeId(thread?.chat_guid) &&
        (m.assoc_type ?: 0) !in 2000..3999

/** True when [m] was sent less than [seconds] before [nowMillis]. A message without a date has no known age. */
private fun sentWithin(m: Msg, nowMillis: Long, seconds: Long): Boolean {
    val sent = m.date ?: return false      // Unix seconds, as everywhere in Msg
    return nowMillis / 1000.0 - sent < seconds
}

/**
 * The text of [m] the composer may edit, or null when the message is not a
 * plain text: no text at all; any attachment, by the list, by Messages'
 * placeholder in the text, or by the Mac's own flag (an edit names part 0,
 * which is then the attachment; the flag is also set for a link whose preview
 * the Mac stored as an attachment, which the relay does not list, so such a
 * message is not edited from here either); or a link and nothing else (the
 * bubble is the preview card, see Bubble's hideBody rule, and a text that is
 * one URL is treated the same whether or not its preview has arrived). Words
 * with a link among them, on a message the Mac flags no attachment for, are
 * text.
 */
fun editableText(m: Msg): String? {
    val raw = m.text ?: return null
    if (ATTACHMENT_MARK in raw || m.attachments.isNotEmpty() || m.has_attachments) return null
    val body = raw.trim()
    if (body.isEmpty()) return null
    fun bare(u: String?) = u?.trim()?.substringBefore('?')?.trimEnd('/')
    val isTheLink = m.link != null && bare(body).equals(bare(m.link.url), ignoreCase = true)
    val isOneUrl = URL_REGEX.matchEntire(body) != null
    return if (isTheLink || isOneUrl) null else body
}

/**
 * True when [m] is one part as Messages counts them: words and no attachment,
 * or one attachment and no words. The request names part 0 and nothing else,
 * so on a photo with a caption, or on several attachments sent together, it
 * would take one piece back and leave the rest with the recipient. Messages
 * keeps a placeholder in the text for each attachment: more placeholders than
 * listed attachments is an unknown number of parts, and not one. The Mac's
 * flag alone does not count: it is also set for a link's stored preview, which
 * the relay leaves out of the list and which is no part of its own. Nothing
 * left to show ([isEmptied]) is no part at all.
 */
fun isOnePart(m: Msg): Boolean {
    val text = m.text.orEmpty()
    val words = text.replace(ATTACHMENT_MARK.toString(), "").isNotBlank()
    val marks = text.count { it == ATTACHMENT_MARK }
    return when {
        marks > m.attachments.size -> false
        m.attachments.isEmpty() -> words
        else -> m.attachments.size == 1 && !words
    }
}

/**
 * Whether "Undo Send" is offered on [msg] in [thread] at [nowMillis]: the
 * owner's own iMessage, one part of it and so all of it ([isOnePart]), sent
 * within Apple's two minutes less a margin. The relay and the Mac's clock have
 * the last word.
 */
fun canUnsend(msg: Msg, thread: Thread?, nowMillis: Long): Boolean =
    isOwnIMessage(msg, thread) && isOnePart(msg) &&
        sentWithin(msg, nowMillis, UNSEND_WINDOW_SECONDS - UNSEND_MARGIN_SECONDS)

/**
 * Whether "Edit" is offered on [msg] in [thread] at [nowMillis]: the owner's
 * own iMessage, a plain text ([editableText]), sent within Apple's fifteen
 * minutes less a margin. How many edits it has had is the relay's to count.
 */
fun canEdit(msg: Msg, thread: Thread?, nowMillis: Long): Boolean =
    isOwnIMessage(msg, thread) && editableText(msg) != null &&
        sentWithin(msg, nowMillis, EDIT_WINDOW_SECONDS - EDIT_MARGIN_SECONDS)

// ---- what the app remembers about the relay ----

/** The kinds of chat a relay may answer differently for. */
enum class ChatKind { IMESSAGE, IMESSAGE_GROUP, TEXT }

fun chatKindOf(msg: Msg?, thread: Thread?): ChatKind = when {
    isTextThread(thread) || isBridgeId(thread?.chat_guid) -> ChatKind.TEXT
    msg != null && (isTextMsg(msg) || isBridgeId(msg.guid) || isBridgeId(msg.chat_guid)) -> ChatKind.TEXT
    thread?.is_group == true || msg?.is_group == true -> ChatKind.IMESSAGE_GROUP
    else -> ChatKind.IMESSAGE
}

/**
 * What this session has learned about the relay's edit and unsend: the
 * (action, kind of chat) pairs it answered "cannot" for ([refused]), and the
 * actions its `/health` did not list ([relayLacks]). An action it hides is not
 * offered in the long-press panel: an app whose relay has no edit tool should
 * not go on offering Edit. Kept in memory only, for one relay address
 * ([ChangeSupport]).
 */
data class ChangeMemory(
    val refused: Set<Pair<ChangeAction, ChatKind>> = emptySet(),
    val relayLacks: Set<ChangeAction> = emptySet(),
) {
    fun hides(action: ChangeAction, kind: ChatKind): Boolean = action in relayLacks || (action to kind) in refused
}

/** True for the two refusals that say "this relay cannot do that" and not "not this message, not now". */
fun hidesFromNowOn(outcome: ChangeOutcome): Boolean =
    outcome is ChangeOutcome.Refused &&
        (outcome.reason == ChangeRefusal.NOT_SUPPORTED || outcome.reason == ChangeRefusal.RELAY_TOO_OLD)

/** [memory] after [action] in a chat of [kind] ended with [outcome]. */
fun changeMemoryAfter(memory: ChangeMemory, action: ChangeAction, kind: ChatKind, outcome: ChangeOutcome): ChangeMemory =
    if (hidesFromNowOn(outcome)) memory.copy(refused = memory.refused + (action to kind)) else memory

/**
 * The memory after the relay answered `/health` as a relay, with this
 * "capabilities" list (null: a relay that sends none). It starts over from
 * what the relay says now, because whoever pressed Save or Test connection may
 * just have changed the relay: with a list, what it does not name is hidden in
 * every kind of chat and what it names is offered; with no list nothing is
 * known, everything is offered, and the first try tells. What was learned from
 * refusals before is dropped either way.
 */
fun changeMemoryReported(capabilities: List<String>?): ChangeMemory {
    if (capabilities == null) return ChangeMemory()
    val named = capabilities.map { it.trim().lowercase() }.toSet()
    return ChangeMemory(relayLacks = ChangeAction.entries.filterNot { it.capability in named }.toSet())
}

/** What was learned from the relay at [heldBase] as it applies to the relay at [base]: nothing, when that is another address. */
fun changeMemoryFor(heldBase: String, held: ChangeMemory, base: String): ChangeMemory =
    if (heldBase == base) held else ChangeMemory()

/**
 * The session's [ChangeMemory], for the relay in force. Gone with the process,
 * with the relay's address (what another relay could not do says nothing
 * about this one), and whenever a `/health` the app fetches anyway is answered
 * by that relay ([relayReported]).
 */
object ChangeSupport {
    private var base = ""
    private var held = ChangeMemory()

    private fun forRelayInForce(): ChangeMemory {
        val now = RelayConfigStore.current.base
        held = changeMemoryFor(base, held, now)
        base = now
        return held
    }

    @Synchronized fun memory(): ChangeMemory = forRelayInForce()

    /** [action] in a chat of [kind] ended with [outcome]. */
    @Synchronized fun note(action: ChangeAction, kind: ChatKind, outcome: ChangeOutcome) {
        held = changeMemoryAfter(forRelayInForce(), action, kind, outcome)
    }

    /**
     * A `/health` the app fetched anyway (after a Save, or a Test connection)
     * came from the relay at [reportedBase] with this "capabilities" list (null:
     * none). Ignored unless that is the relay in force: Test connection runs on
     * typed values that may never be saved.
     */
    @Synchronized fun relayReported(reportedBase: String, capabilities: List<String>?) {
        forRelayInForce()
        if (reportedBase == base) held = changeMemoryReported(capabilities)
    }
}

// ---- Undo Send ----

/** The messages whose unsend is in flight, with [guid] added; null when it already is, so a second tap is no second request. */
fun unsendStarted(inFlight: Set<String>, guid: String): Set<String>? = if (guid in inFlight) null else inFlight + guid

fun unsendEnded(inFlight: Set<String>, guid: String): Set<String> = inFlight - guid

// ---- after a change ----

/**
 * When ChatVM looks at the open chat again after a change, as the waits between
 * one look and the next. The relay's own update frame normally shows the change
 * within a moment; these looks are for a socket that was down. A confirmed
 * change gets one look, three seconds on. An unconfirmed one may still land (an
 * edit takes the Mac up to about twenty seconds, then the relay waits for the
 * database), so it gets three. "The Mac did not apply the change" gets one as
 * well: the relay stopped watching the database after a few seconds, and a
 * change that landed just after that should not stay hidden behind a toast
 * that said it did not happen. Any other refusal, and an edit that changed
 * nothing, leave nothing to look for. Every wait ends, and every look is
 * skipped, once the change shows ([changeStillPending]).
 */
fun changeRecheckDelaysMillis(outcome: ChangeOutcome): List<Long> = when (outcome) {
    is ChangeOutcome.Done -> if (outcome.unchanged) emptyList() else listOf(3_000L)
    is ChangeOutcome.Unconfirmed -> listOf(3_000L, 7_000L, 20_000L)
    is ChangeOutcome.Refused -> if (outcome.reason == ChangeRefusal.MAC_DID_NOT_APPLY) listOf(3_000L) else emptyList()
}

/**
 * True when the message stays marked as being changed after the relay's
 * answer, until the change shows on screen or the chat has been looked at
 * again ([changeRecheckDelaysMillis]): a change the relay confirmed. Its
 * update frame comes from the relay's own poll a moment after the answer, and
 * in between the bubble looks untouched; without the mark "Unsending…" would
 * go while the bubble is still there, and the panel would offer the same
 * message Undo Send or Edit again for a change that has already happened. A
 * refusal, and an end without an answer, clear the mark at once: the owner may
 * want to try again.
 */
fun changeHeldUntilShown(outcome: ChangeOutcome): Boolean = outcome is ChangeOutcome.Done

/**
 * True while [held] still shows the message as it was [before] the change: an
 * unsent message that still has something to show, an edited one whose text
 * and edit date are the old ones. False once the update arrived, and when the
 * message is not held at all (nothing to wait for).
 */
fun changeStillPending(action: ChangeAction, before: Msg, held: List<Msg>): Boolean {
    val now = held.firstOrNull { it.guid == before.guid } ?: return false
    return when (action) {
        ChangeAction.UNSEND -> !isEmptied(now)
        ChangeAction.EDIT -> now.text == before.text && now.date_edited == before.date_edited
    }
}

/**
 * [held] with the message [guid] as [page] (the chat's latest page, read again)
 * has it; [held] itself when the page lacks it or holds the same. Only that one
 * message is touched: nothing is added, dropped or reordered.
 */
fun withMessageFrom(held: List<Msg>, page: List<Msg>, guid: String): List<Msg> {
    val fresh = page.firstOrNull { it.guid == guid } ?: return held
    if (held.none { it.guid == guid && it != fresh }) return held
    return held.map { if (it.guid == guid) fresh else it }
}

// ---- the composer's edit mode ----

/**
 * The composer while it edits the message [guid] of [chatGuid]. [original] is
 * the message's text when Edit was tapped and [text] what the field holds now.
 * [stashedDraft] and [stashedReply] are what the composer held before: the
 * owner's half-typed draft and the message it was a reply to, put back when
 * edit mode ends. [sending]: the edit is on its way and the field is read-only.
 * [maybeApplied]: an earlier request of this mode ended without the relay
 * saying for certain that nothing changed, so the message may no longer say
 * [original] ([editSubmit]). [seq] tells one edit mode from the next, so the
 * answer to an earlier one's request is never taken for this one's.
 */
data class EditMode(
    val seq: Int,
    val chatGuid: String,
    val guid: String,
    val original: String,
    val text: String,
    val stashedDraft: String = "",
    val stashedReply: Msg? = null,
    val sending: Boolean = false,
    val maybeApplied: Boolean = false,
) {
    /** The send button's state: there is something to submit and nothing in flight. */
    val canSubmit: Boolean get() = !sending && text.isNotBlank()
}

/** One `POST /edit`, for the edit mode [seq]. */
data class EditRequest(val seq: Int, val chatGuid: String, val guid: String, val text: String)

/**
 * What one event does to edit mode. [mode] is the mode after it (null: the
 * composer is an ordinary composer again). [field], when not null, is the text
 * the composer's field is set to, cursor at the end; null leaves the field as
 * it is. [setsReply]: the reply banner becomes [reply] (null clears it).
 * [request] is the edit to send, [toast] what the owner is told.
 */
data class EditStep(
    val mode: EditMode?,
    val field: String? = null,
    val setsReply: Boolean = false,
    val reply: Msg? = null,
    val request: EditRequest? = null,
    val toast: String? = null,
)

/**
 * "Edit" on [msg] in the chat [chatGuid], whose composer holds [draft] and is
 * replying to [reply]: both are stashed, and the field shows the message's
 * text. Nothing happens for a message with no text to edit, and nothing while
 * this chat's composer is already in edit mode (one edit at a time; what is
 * being typed there is not thrown away by a tap in the panel).
 */
fun editEnter(current: EditMode?, seq: Int, msg: Msg, chatGuid: String, draft: String, reply: Msg?): EditStep {
    if (current != null && current.chatGuid == chatGuid) return EditStep(current)
    val text = editableText(msg) ?: return EditStep(current)
    return EditStep(
        mode = EditMode(seq, chatGuid, msg.guid, original = text, text = text, stashedDraft = draft, stashedReply = reply),
        field = text, setsReply = true, reply = null,
    )
}

/** The field now holds [text]. Ignored while the edit is on its way: what was submitted is what the mode holds. */
fun editTyped(mode: EditMode?, text: String): EditMode? =
    if (mode == null || mode.sending || mode.text == text) mode else mode.copy(text = text)

/** Edit mode ends without an edit: the stashed draft and reply target are back. */
private fun editClosed(mode: EditMode): EditStep =
    EditStep(null, field = mode.stashedDraft, setsReply = true, reply = mode.stashedReply)

/** The banner's close button. Not while the edit is on its way: that request cannot be called back. */
fun editCancel(mode: EditMode?): EditStep = if (mode == null || mode.sending) EditStep(mode) else editClosed(mode)

/**
 * True when system Back, in the chat whose composer is in [mode], leaves edit
 * mode as the banner's close button does and does not leave the chat: Back is
 * the obvious way out of editing, and leaving the chat would take the stashed
 * draft, which is not on screen at that moment, with it. Not while the edit is
 * on its way (it cannot be called back; Back then leaves the chat as ever).
 */
fun editBackCancels(mode: EditMode?): Boolean = mode != null && !mode.sending

/**
 * The send button in edit mode. Blank text is not submitted; text that says
 * what the message already says closes edit mode without a request; anything
 * else is one request, with the field read-only until it ends. Spaces and line
 * breaks around the text are not part of an edit.
 *
 * Once a request of this mode has ended without a certain "nothing changed"
 * ([EditMode.maybeApplied]) the message may hold that edit, so the text it had
 * when Edit was tapped is no longer known to be what it says: typing that text
 * back is then a request like any other (the relay answers "unchanged" itself
 * when nothing differs), not a quiet close that would leave the edited text in
 * place.
 */
fun editSubmit(mode: EditMode?): EditStep = when {
    mode == null || mode.sending || mode.text.isBlank() -> EditStep(mode)
    !mode.maybeApplied && mode.text.trim() == mode.original.trim() -> editClosed(mode)
    else -> EditStep(mode.copy(sending = true), request = EditRequest(mode.seq, mode.chatGuid, mode.guid, mode.text.trim()))
}

/**
 * The refusals after which trying the same edit again cannot work, so edit
 * mode ends: Apple's limits, a message that is gone, a relay or a chat that
 * cannot edit.
 */
val EDIT_ENDING_REFUSALS: Set<ChangeRefusal> = setOf(
    ChangeRefusal.TOO_LATE, ChangeRefusal.ALREADY_UNSENT, ChangeRefusal.EDIT_LIMIT,
    ChangeRefusal.NOT_SUPPORTED, ChangeRefusal.RELAY_TOO_OLD,
)

/**
 * The edit [request] ended with [outcome].
 *
 * Done: edit mode ends and the stashed draft and reply target are back. A
 * refusal that leaves the edit possible, and an outcome that confirms nothing,
 * keep edit mode with the text as typed, so nothing typed is lost and the
 * owner can try again or cancel. A refusal that ends it ([EDIT_ENDING_REFUSALS])
 * leaves the typed text in the field as an ordinary draft, to send as a new
 * message or to discard, after the draft that was stashed when there was one
 * (on a new line: neither text is dropped); the stashed reply target is not put
 * back, so that draft cannot go out as a reply by a tap on Send.
 *
 * An answer to a request that is not this mode's (the chat was left, or
 * another edit mode has started since) changes nothing here and is only told.
 * [otherChat] is null while the request's chat is the one on screen, else that
 * chat's title, which the toast then names ([changeToast]).
 */
fun editOutcome(mode: EditMode?, request: EditRequest, outcome: ChangeOutcome, otherChat: String? = null): EditStep {
    val toast = changeToast(ChangeAction.EDIT, outcome, otherChat)
    if (mode == null || !mode.sending || mode.seq != request.seq) return EditStep(mode, toast = toast)
    return when {
        outcome is ChangeOutcome.Done -> editClosed(mode)
        outcome is ChangeOutcome.Refused && outcome.reason in EDIT_ENDING_REFUSALS ->
            EditStep(null, field = appendToDraft(mode.stashedDraft, mode.text), toast = toast)
        else -> EditStep(mode.copy(sending = false, maybeApplied = mode.maybeApplied || editMayHaveLanded(outcome)), toast = toast)
    }
}

/**
 * True when an edit that ended with [outcome] may be in the message all the
 * same: no certain answer, or the relay's "the Mac did not apply the change",
 * which speaks of the few seconds it watched the database for.
 */
fun editMayHaveLanded(outcome: ChangeOutcome): Boolean =
    outcome is ChangeOutcome.Unconfirmed ||
        (outcome is ChangeOutcome.Refused && outcome.reason == ChangeRefusal.MAC_DID_NOT_APPLY)

/** "Reply" tapped while in edit mode: the reply waits with the stashed draft and shows when edit mode ends. */
fun editReplyChosen(mode: EditMode, reply: Msg): EditMode = mode.copy(stashedReply = reply)

/** Text shared into the chat while in edit mode joins the stashed draft, not the message being edited. */
fun editDraftAppended(mode: EditMode, text: String): EditMode = mode.copy(stashedDraft = appendToDraft(mode.stashedDraft, text))

/** The edit mode of the chat [chatGuid]'s composer; another chat's is not this one's. */
fun editModeFor(mode: EditMode?, chatGuid: String?): EditMode? = mode?.takeIf { it.chatGuid == chatGuid }

/** A text for the composer's field of [chatGuid], cursor at the end; [seq] makes each one distinct. */
data class ComposerText(val chatGuid: String, val text: String, val seq: Int)

/**
 * What the composer's field and the note saved beside it are brought to
 * ([composerSettle]): [field], when not null, is the text the field is set to,
 * cursor at the end (null leaves it as it is); [stash] is the note from now on.
 */
data class ComposerSettle(val field: String?, val stash: String?)

/**
 * Brings one chat's composer in line with its edit mode. The field is the
 * Conversation screen's own saved state: it survives the process being killed
 * in the background, and edit mode, which lives in ChatVM's memory, does not.
 * Without a note the field would then come back holding the text of a message
 * that was being edited, with no banner and the ordinary send button (one tap
 * would send it as a new message), and the draft set aside on entering edit
 * mode would be gone. So the screen saves [savedStash] with the field: the
 * stashed draft while edit mode is on, null otherwise.
 *
 * [mode] and [pending] are this chat's edit mode and the text ChatVM has for
 * the field. A pending text is taken first, whatever else holds: it is what
 * the last event decided the field shows. While edit mode is on, the note
 * follows its stashed draft (a text shared meanwhile joins it). A note with no
 * edit mode and nothing pending is an edit mode that did not survive: the
 * stashed draft goes back into the field, in place of the edit text, and the
 * note is cleared. Edit mode itself is not resumed, and neither is a reply
 * target, which was never kept across a process death.
 */
fun composerSettle(savedStash: String?, mode: EditMode?, pending: ComposerText?): ComposerSettle = when {
    pending != null -> ComposerSettle(pending.text, mode?.stashedDraft)
    mode != null -> ComposerSettle(null, mode.stashedDraft)
    savedStash != null -> ComposerSettle(savedStash, null)
    else -> ComposerSettle(null, null)
}

// ---- the long-press panel ----

/** Which of the two entries the long-press panel shows for one message. */
data class PanelChanges(val edit: Boolean, val unsend: Boolean)

/**
 * Decided once, as the panel opens on [msg] at [nowMillis]: "Edit" and "Undo
 * Send" for a message that is eligible ([canEdit], [canUnsend]) in a chat the
 * relay has not said it cannot change ([memory]). Neither while a change to
 * that message is on its way, or was confirmed and does not show yet: its
 * unsend ([unsending]) or its edit ([saving], which outlives the edit mode
 * that sent it, so leaving the chat and coming back during the wait does not
 * offer the same message a second edit). No "Edit" while the composer is in
 * edit mode already ([editing]); no "Undo Send" for the message whose edit is
 * on its way. A window that closes while the panel is open is the relay's to
 * refuse.
 */
fun panelChangesFor(
    msg: Msg,
    thread: Thread?,
    nowMillis: Long,
    memory: ChangeMemory,
    unsending: Set<String> = emptySet(),
    editing: EditMode? = null,
    saving: Set<String> = emptySet(),
): PanelChanges {
    val kind = chatKindOf(msg, thread)
    val busy = msg.guid in unsending || msg.guid in saving
    val beingSaved = editing?.sending == true && editing.guid == msg.guid
    return PanelChanges(
        edit = !busy && editing == null && !memory.hides(ChangeAction.EDIT, kind) && canEdit(msg, thread, nowMillis),
        unsend = !busy && !beingSaved && !memory.hides(ChangeAction.UNSEND, kind) && canUnsend(msg, thread, nowMillis),
    )
}
