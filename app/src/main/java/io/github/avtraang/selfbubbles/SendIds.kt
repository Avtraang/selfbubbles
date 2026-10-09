package io.github.avtraang.selfbubbles

// One send is one message: the app's rules around the send id. Plain functions
// (SendIdTest). The id travels as `client_id` (Imsg.kt), is stored with a text in
// the outbox (SendRecovery.kt, Outbox.kt), and for the first text of a new
// conversation lives with the compose screen (ChatVM.kt).
//
// The relay keeps each id with what became of its send and does not send the same
// id twice: delivered -> "ok" again, marked a duplicate; certainly not sent -> it
// sends; unknown -> it refuses, for good. So "Send again" under the same id cannot
// deliver a text twice, on a relay that keeps ids. On one that does not, and for a
// send the relay itself cannot account for, the only way to send is a new id, and
// that is "Send anyway": a separate action that says what it risks.

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** The relay's name, in its `/health` "capabilities", for keeping send ids. */
const val RELAY_CAPABILITY_SEND_ID = "send_id"

/** The relay's reasons in `{"detail": {"code": ...}}` ([relayErrorCode]). */
const val RELAY_CODE_OUTCOME_UNKNOWN = "send_outcome_unknown"
const val RELAY_CODE_ID_REUSED = "send_id_reused"
const val RELAY_CODE_IDS_UNAVAILABLE = "send_ids_unavailable"

/** A new send id: random, and of the shape the relay accepts (8 to 64 letters, digits, '-' or '_'). */
fun newSendId(): String = UUID.randomUUID().toString()

/**
 * The relay's own reason for refusing a send, from the body of its answer:
 * the `code` inside `detail`, when both are what the relay sends. Anything
 * else (plain words, a web page, another service's JSON) has none. Only the
 * code is taken; nothing else of the body is kept or shown.
 */
fun relayErrorCode(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val detail = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("detail") as? JsonObject }.getOrNull()
    val code = detail?.get("code") as? JsonPrimitive ?: return null
    return code.content.takeIf { code.isString && it.isNotBlank() }
}

/** Whether the relay that answered `/health` with [outcome] keeps send ids; null when nothing answered as a relay. */
fun relayKeepsSendIds(outcome: ProbeOutcome?): Boolean? =
    (outcome as? ProbeOutcome.Connected)?.let { RELAY_CAPABILITY_SEND_ID in it.capabilities.orEmpty() }

// ---- a text in the outbox ----

/** The one way a row offers to send its text. */
enum class UnsentSendAction {
    /** The same message once more, under its id. Never a second delivery ([sendAgainPlan]). */
    SEND_AGAIN,
    /** A new message that may arrive next to the first: asked for separately, with [SEND_ANYWAY_WARNING]. */
    SEND_ANYWAY,
}

const val SEND_ANYWAY_LABEL = "Send anyway"
const val SEND_ANYWAY_TITLE = "Send it a second time?"
const val SEND_ANYWAY_WARNING =
    "This message may already have been sent. Sending it again can deliver it twice. Check the chat first."

/** Shown when "Send again" could not ask the relay what became of the first try. */
const val SEND_AGAIN_COULD_NOT_ASK = "Couldn't reach the relay to check — nothing was sent"

/**
 * What the row of [entry] offers. "Send again" where it cannot deliver the
 * text twice: after a certain failure, or under the text's id. "Send anyway"
 * where nothing stands in the way of a second delivery: the relay cannot say
 * what became of the first try, or the text has no id (it was stored by a
 * build from before the id).
 */
fun unsentSendAction(entry: UnsentText): UnsentSendAction = when {
    entry.why == UnsentWhy.RELAY_UNSURE -> UnsentSendAction.SEND_ANYWAY
    entry.certainlyNotSent -> UnsentSendAction.SEND_AGAIN
    entry.sendId.isBlank() -> UnsentSendAction.SEND_ANYWAY
    else -> UnsentSendAction.SEND_AGAIN
}

/** What a tap on "Send again" does. */
enum class SendAgainPlan {
    /** The request goes out under the text's id. */
    SEND,
    /** Nothing is sent, and the row offers "Send anyway" from now on. */
    RELAY_CANNOT_TELL,
    /** Nothing is sent and the row stays as it was: the relay could not be asked. */
    COULD_NOT_ASK,
}

/**
 * "Send again" on [entry]. A text that certainly did not go out is sent. One
 * that may have gone out is sent only under its id and only to a relay that
 * keeps ids ([relayKeepsIds], asked just before; null: no answer): a relay
 * from before the id would take the same id for a new message. Nothing here
 * ever sends a doubtful text on its own; that is "Send anyway".
 */
fun sendAgainPlan(entry: UnsentText, relayKeepsIds: Boolean?): SendAgainPlan = when {
    entry.certainlyNotSent -> SendAgainPlan.SEND
    entry.sendId.isBlank() -> SendAgainPlan.RELAY_CANNOT_TELL
    relayKeepsIds == true -> SendAgainPlan.SEND
    relayKeepsIds == false -> SendAgainPlan.RELAY_CANNOT_TELL
    else -> SendAgainPlan.COULD_NOT_ASK
}

/**
 * "Send anyway" on text [id], confirmed: a new message under [newId], in
 * flight. A text already in flight is left alone. Whatever this send ends
 * with, an earlier one may be in the chat ([UnsentText.maybeSent]).
 */
fun outboxSendAnyway(outbox: List<UnsentText>, id: Long, newId: String): List<UnsentText> =
    outbox.map {
        if (it.id == id && !it.sending) {
            it.copy(why = null, attempts = it.attempts + 1, slow = false, maybeSent = true, sendId = newId)
        } else it
    }

// ---- the first text of a new conversation ----
// It is sent straight from the compose screen and has no stored entry. Its id is
// kept here for as long as that screen holds the message: a second tap on the same
// message to the same people is the same send. If the app is closed before the
// send has ended and the message is typed again, that is a new id and a new send.

/** The send the compose screen is busy with: what it is ([key]), its [id], and whether a try of it ended without a certain answer. */
data class NewChatSend(val key: String, val id: String, val doubtful: Boolean = false)

private fun newChatKey(addresses: List<String>, text: String): String =
    addresses.map { it.trim() }.sorted().joinToString("\u0000") + "\u0001" + text

/** The send for this message to these people: [previous] when it is the same one, else a new one under [newId]. */
fun newChatSendFor(previous: NewChatSend?, addresses: List<String>, text: String, newId: () -> String): NewChatSend {
    val key = newChatKey(addresses, text)
    return if (previous != null && previous.key == key) previous else NewChatSend(key, newId())
}

/** [send] after a try that failed with [error]: doubtful from now on unless the failure is certain. */
fun newChatAfter(send: NewChatSend, error: Throwable?): NewChatSend =
    send.copy(doubtful = send.doubtful || !unsentWhyFor(error).certain)

/** What a tap on Send does for a new conversation. */
enum class NewChatPlan { SEND, ASK_FIRST }

/**
 * A first try is sent. A try after a doubtful one is sent under the same id
 * only to a relay that keeps ids; otherwise the owner is asked first
 * ([NEW_CHAT_ANYWAY_WARNING]).
 */
fun newChatPlan(send: NewChatSend, relayKeepsIds: Boolean?): NewChatPlan =
    if (!send.doubtful || relayKeepsIds == true) NewChatPlan.SEND else NewChatPlan.ASK_FIRST

/** The relay answered that it cannot say what became of the first try: only "Send anyway" is left. */
fun newChatMustAsk(error: Throwable?): Boolean =
    error is SendFailedException && error.relayCode == RELAY_CODE_OUTCOME_UNKNOWN

const val NEW_CHAT_ANYWAY_WARNING =
    "Your first message may already have been sent. Sending it again can deliver it twice. Check your conversations first."

/** A conversation the relay says was already started under this id, when it cannot name it. */
const val NEW_CHAT_ALREADY_SENT = "Already sent — open the conversation from the list"
