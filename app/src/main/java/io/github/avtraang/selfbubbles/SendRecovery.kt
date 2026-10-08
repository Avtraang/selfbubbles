package io.github.avtraang.selfbubbles

// What happens around a send beyond the send itself: whether a reply to a send is
// the relay's own, why a text did not get to the relay (or may not have), the texts
// kept until they are sent or discarded (Outbox.kt holds and sends them; the rules
// are here), what the owner is told about each, and the two toasts about the relay's
// send path. Plain Kotlin (no Android, no Compose), so SendRecoveryTest pins every
// table here.
//
// A text the owner sent is never put back into the composer and never matched
// against the chat's bubbles by its words: the composer is the owner's alone, and
// two messages with the same words are two messages. A text stays in the outbox,
// shown above the composer, until the relay says it was delivered or the owner
// sends it again or discards it.

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

// ---- was it the relay that answered? ----

/**
 * True when a 2xx reply to a send is the relay's own: a JSON object
 * (`{"ok":true,"via":…}`, or the shape without `via` a BlueBubbles success has).
 * Anything else answered in the relay's place and delivered nothing: Cloudflare
 * Access answers a wrong or expired service-token pair with a redirect to its
 * login page, which the shared client follows, so it arrives as a 2xx web page.
 * Counting that as sent would lose the text without a word.
 */
fun isRelaySendReply(contentType: String?, body: String): Boolean =
    !looksLikeHtml(contentType, body) && runCatching { json.parseToJsonElement(body) is JsonObject }.getOrDefault(false)

// ---- why a text is not sent ----

/**
 * The longest the relay itself may take over one text and still deliver it:
 * 15 s for BlueBubbles, then the AppleScript fallback at 20 s per attempt for up
 * to two spellings of the chat guid (the Google Messages bridge has 30 s). The
 * app waits longer than this for the answer ([SEND_READ_TIMEOUT_SECONDS]).
 */
const val RELAY_SEND_BUDGET_SECONDS = 15 + 2 * 20

/**
 * Why a text is in the outbox and not in the chat. [certain]: that send sent
 * nothing, so sending the text again cannot make a second copy of it. The
 * others are sends that left the phone (or may have) and ended without the
 * relay saying "delivered": the Mac goes on sending whether or not anyone
 * still listens, so the message may be in the chat, and the owner is told to
 * look before sending again. Stored by name with the text (Outbox.kt).
 *
 * A reason speaks for one send only. A text sent again after a send that was
 * not certain keeps that doubt ([UnsentText.maybeSent]): whatever the later
 * send's reason, the text is never called "not sent" ([UnsentText.certainlyNotSent]).
 */
enum class UnsentWhy(val certain: Boolean) {
    /** DNS or connect failure: no network, the host does not resolve, nothing listens. */
    UNREACHABLE(true),
    /** TLS failed before anything was sent. */
    NO_TRUSTED_CERT(true),
    /** 401: the token is not the relay's. */
    TOKEN_REJECTED(true),
    /** A 403, a redirect or a web page in the relay's place: Cloudflare Access. */
    ACCESS_REJECTED(true),
    /** 413 from the route. */
    TOO_LARGE(true),
    /** Any other 4xx: the request was refused before anything was handed to an engine. */
    REFUSED(true),
    /** 501: the relay has no engine for this chat, which it decides before trying any. */
    NO_ENGINE(true),
    /** A 2xx that is not the relay's reply and not a web page: another service answers at the address. */
    NOT_A_RELAY(true),
    /** The request could not be built: no relay configured, or (an attachment) the picked file could not be opened. */
    NOT_BUILT(true),

    /** No answer in time: the app's own timeout, or a 408/504/522/524 from the route. */
    NO_ANSWER(false),
    /** The connection failed after the request had left (a reset, a network change mid-request). */
    CONNECTION_LOST(false),
    /**
     * A 5xx. The relay's own 502 means every engine reported a failure, which
     * includes engines that merely ran out of time and deliver a moment later;
     * a 502/520/530 from the route means the relay could not be heard, not that
     * it did nothing.
     */
    SERVER_ERROR(false),
    /** The app's process ended while the send was in flight ([outboxAfterRestart]). */
    INTERRUPTED(false),
}

/** A failure before anything was written to the relay: no address, nothing listening, no trusted certificate. */
private fun isBeforeSend(t: Throwable): Boolean =
    t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
        t is SSLHandshakeException || t is SSLPeerUnverifiedException

/**
 * True when [error] carries, as a suppressed exception, an earlier attempt of
 * the same call that failed after it may have been sent. OkHttp reports a call
 * it retried by the LAST attempt's exception and hangs the earlier ones on it:
 * a send that reached the relay, lost its connection and was then retried into
 * a dead network surfaces as "unknown host" with the reset only in `suppressed`.
 * A suppressed connect failure or connect timeout (the only timeout OkHttp
 * retries) is an attempt that sent nothing. Send bodies are written once
 * ([sentOnce]), so this is a second line of defence, not the first.
 */
private fun hidesASentAttempt(error: Throwable): Boolean =
    error.suppressed.any { it is IOException && !isBeforeSend(it) && it !is SocketTimeoutException }

/** [UnsentWhy] for a status that answered a send in place of a delivery. */
fun unsentWhyFor(httpCode: Int, html: Boolean): UnsentWhy = when {
    httpCode in 200..299 -> if (html) UnsentWhy.ACCESS_REJECTED else UnsentWhy.NOT_A_RELAY
    httpCode in 300..399 || httpCode == 403 -> UnsentWhy.ACCESS_REJECTED
    httpCode == 401 -> UnsentWhy.TOKEN_REJECTED
    httpCode == 413 -> UnsentWhy.TOO_LARGE
    httpCode == 408 || httpCode == 504 || httpCode == 522 || httpCode == 524 -> UnsentWhy.NO_ANSWER
    httpCode in 400..499 -> UnsentWhy.REFUSED
    httpCode == 501 -> UnsentWhy.NO_ENGINE
    else -> UnsentWhy.SERVER_ERROR
}

/**
 * Classifies whatever a send threw (null: it ended with neither a delivery nor
 * an error, which says nothing). Only what is known for certain counts as "not
 * sent": a status that refuses the request before any engine runs, or a failure
 * before anything was written. Everything else left the phone, or may have. A
 * timeout is never certain: a connect timeout and a read timeout are the same
 * exception. A throwable's own text is never used.
 */
fun unsentWhyFor(error: Throwable?): UnsentWhy = when {
    error == null -> UnsentWhy.NO_ANSWER
    error is SendFailedException -> when {
        error.httpCode != null -> unsentWhyFor(error.httpCode, error.html)
        error.reason == SendFailure.TOO_LARGE -> UnsentWhy.TOO_LARGE
        else -> UnsentWhy.NO_ANSWER
    }
    hidesASentAttempt(error) -> UnsentWhy.CONNECTION_LOST
    error is UnknownHostException || error is ConnectException || error is NoRouteToHostException -> UnsentWhy.UNREACHABLE
    error is SSLHandshakeException || error is SSLPeerUnverifiedException -> UnsentWhy.NO_TRUSTED_CERT
    error is SocketTimeoutException -> UnsentWhy.NO_ANSWER
    error is FileNotFoundException -> UnsentWhy.NOT_BUILT
    error is IOException -> UnsentWhy.CONNECTION_LOST
    error is IllegalArgumentException -> UnsentWhy.NOT_BUILT
    else -> UnsentWhy.NO_ANSWER
}

private fun notSentCause(why: UnsentWhy): String? = when (why) {
    UnsentWhy.UNREACHABLE -> "can't reach the relay"
    UnsentWhy.NO_TRUSTED_CERT -> "no trusted certificate at the relay's address"
    UnsentWhy.TOKEN_REJECTED -> "the relay rejected the token"
    UnsentWhy.ACCESS_REJECTED -> "Cloudflare Access rejected the request"
    UnsentWhy.TOO_LARGE -> "the message is too large"
    UnsentWhy.REFUSED -> "the relay refused the message"
    UnsentWhy.NO_ENGINE -> "the relay cannot send into this chat"
    UnsentWhy.NOT_A_RELAY -> "the address did not answer like a relay"
    else -> null
}

private fun unconfirmedHead(why: UnsentWhy): String = when (why) {
    UnsentWhy.CONNECTION_LOST -> "Connection lost before the relay answered"
    UnsentWhy.SERVER_ERROR -> "The relay's address answered with a server error"
    UnsentWhy.INTERRUPTED -> "The app closed before the relay answered"
    else -> "No answer from the relay"
}

/**
 * What the owner reads about a text that is not in its chat: on its row above
 * the composer, in the toast and in the notification. A certain failure says
 * "Not sent" and why. An uncertain one never says the send failed, because it
 * may not have: it says what happened and to look at the chat first.
 *
 * [otherChat] is null when the line is shown in the text's own chat; otherwise
 * it is that chat's title (blank when there is none), so a line shown anywhere
 * else names the chat and cannot be read as being about the one on screen.
 * Names no host, no token and nothing from a reply.
 *
 * [maybeSent]: an earlier send of the same text ended without a certain
 * refusal ([UnsentText.maybeSent]). A certain failure of the send after it
 * then says its cause and that the earlier try may have gone out, and to look
 * at the chat: "Not sent" would be read as "safe to send again", and it is not.
 */
fun unsentLine(why: UnsentWhy, otherChat: String? = null, maybeSent: Boolean = false): String =
    if (why.certain && !maybeSent) {
        val head = when {
            otherChat == null -> "Not sent"
            otherChat.isBlank() -> "Message in another chat not sent"
            else -> "Message to $otherChat not sent"
        }
        notSentCause(why)?.let { "$head \u2014 $it" } ?: head
    } else {
        val where = when {
            otherChat == null -> "the chat"
            otherChat.isBlank() -> "that chat"
            else -> "the chat with $otherChat"
        }
        val head = if (why.certain) earlierTryHead(why) else unconfirmedHead(why)
        "$head \u2014 check $where before sending again"
    }

/** The start of the line for a send that certainly failed after an earlier send of the same text that may not have. */
private fun earlierTryHead(why: UnsentWhy): String =
    notSentCause(why)?.let { "${it.replaceFirstChar { c -> c.uppercaseChar() }}, but an earlier try may have gone out" }
        ?: "An earlier try may have gone out"

// ---- the outbox: every text until the relay has it ----

/**
 * One text the owner sent, kept from the tap on Send until the relay answers
 * "delivered" (then it is gone) or the owner discards it. [why] is null while
 * the send is in flight; after a failure it says why the text is still here.
 * [replyToGuid] is the message it replies to, so "Send again" replies to the
 * same one. [chatTitle] names the chat where its thread is not at hand (a
 * toast, a notification, the list's notice). [attempts] counts the sends.
 * [maybeSent]: an earlier send of this text ended without a certain refusal,
 * so the text may be in the chat whatever the send after it ends with
 * ([outboxRetry] sets it and nothing clears it). [slow] marks a send that has
 * been in flight long enough to be shown; it is not stored.
 *
 * A field added here needs a default: a stored text that does not decode is
 * not kept ([outboxDecode]).
 */
@Serializable
data class UnsentText(
    val id: Long,
    val chatGuid: String,
    val chatTitle: String = "",
    val text: String,
    val replyToGuid: String? = null,
    val why: UnsentWhy? = null,
    val attempts: Int = 1,
    val createdAtMillis: Long = 0,
    val maybeSent: Boolean = false,
    @Transient val slow: Boolean = false,
) {
    val sending: Boolean get() = why == null

    /** True only when no send of this text can have gone out: the last one certainly failed, and none before it may have been delivered. */
    val certainlyNotSent: Boolean get() = why?.certain == true && !maybeSent
}

/** How long a send is in flight before its "Sending…" row shows: an ordinary send answers in a second or two. */
const val OUTBOX_SLOW_MILLIS = 4_000L

/** [entry] joins the outbox at its newest end. Nothing is ever dropped to make room: the outbox holds what the owner sent. */
fun outboxAdd(outbox: List<UnsentText>, entry: UnsentText): List<UnsentText> = outbox + entry

/**
 * The send of text [id] ended: delivered ([why] null) and the text leaves the
 * outbox, or not, and it stays with the reason.
 */
fun outboxSettle(outbox: List<UnsentText>, id: Long, why: UnsentWhy?): List<UnsentText> =
    if (why == null) outbox.filterNot { it.id == id }
    else outbox.map { if (it.id == id) it.copy(why = why, slow = false) else it }

/**
 * "Send again" on text [id]: in flight once more. A text already in flight is
 * left alone, so two taps are one send. The reason of the send before goes,
 * but not what it meant: when that send may have gone out, the text may be in
 * the chat from now on, whatever this send ends with ([UnsentText.maybeSent]).
 */
fun outboxRetry(outbox: List<UnsentText>, id: Long): List<UnsentText> =
    outbox.map {
        if (it.id == id && !it.sending) {
            it.copy(why = null, attempts = it.attempts + 1, slow = false, maybeSent = it.maybeSent || it.why?.certain == false)
        } else it
    }

/** "Discard" on text [id]. A text in flight cannot be discarded: its send would still deliver it. */
fun outboxDiscard(outbox: List<UnsentText>, id: Long): List<UnsentText> =
    outbox.filterNot { it.id == id && !it.sending }

/** The send of text [id] has been in flight for [OUTBOX_SLOW_MILLIS]: its row shows. */
fun outboxMarkSlow(outbox: List<UnsentText>, id: Long): List<UnsentText> =
    outbox.map { if (it.id == id && it.sending) it.copy(slow = true) else it }

/**
 * The outbox as a new process finds it. A text still marked in flight belongs
 * to a send the previous process started and never saw the end of (the app was
 * closed, or killed in the background): it may have been delivered, so it
 * comes back as [UnsentWhy.INTERRUPTED], never as in flight and never dropped.
 */
fun outboxAfterRestart(stored: List<UnsentText>): List<UnsentText> =
    stored.map { if (it.sending) it.copy(why = UnsentWhy.INTERRUPTED) else it }

/**
 * The SharedPreferences file the outbox is stored in. It holds message text,
 * so res/xml/backup_rules.xml and data_extraction_rules.xml exclude
 * "[OUTBOX_PREFS_FILE].xml" from cloud backup and device transfer
 * (SendRecoveryTest checks that they name it).
 */
const val OUTBOX_PREFS_FILE = "outbox"

/** The outbox as it is stored (app-private, excluded from backup). */
fun outboxEncode(outbox: List<UnsentText>): String = json.encodeToString(outbox)

/**
 * The stored outbox; empty for nothing stored or something that is not a list.
 * Each text is read by itself, so one that cannot be read costs that one and
 * not the others. A text without words is not kept.
 */
fun outboxDecode(stored: String?): List<UnsentText> =
    if (stored.isNullOrBlank()) emptyList()
    else runCatching {
        json.parseToJsonElement(stored).jsonArray.mapNotNull { runCatching { json.decodeFromJsonElement<UnsentText>(it) }.getOrNull() }
    }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }

/** An id above every id in [outbox] and above [last], the id handed out before. */
fun nextOutboxId(outbox: List<UnsentText>, last: Long, nowMillis: Long): Long =
    maxOf(nowMillis, last + 1, (outbox.maxOfOrNull { it.id } ?: 0L) + 1)

/** True when [entry] has a row: it failed, or its send is slow, or it is being sent again (its row was already there). */
fun unsentShows(entry: UnsentText): Boolean = !entry.sending || entry.slow || entry.attempts > 1

/**
 * How many unsent rows one chat shows above its composer; the rest are counted
 * in one line under them. Few, so the rows, the composer and the keyboard
 * together still leave the conversation in view.
 */
const val UNSENT_ROWS_SHOWN = 2

/** The rows above a chat's composer: [shown], oldest first, and how many [more] wait behind them. */
data class UnsentRows(val shown: List<UnsentText>, val more: Int)

fun unsentRowsFor(outbox: List<UnsentText>, chatGuid: String, limit: Int = UNSENT_ROWS_SHOWN): UnsentRows {
    val mine = outbox.filter { it.chatGuid == chatGuid && unsentShows(it) }
    return UnsentRows(mine.take(limit), (mine.size - limit).coerceAtLeast(0))
}

/** The line under the rows for the ones that do not fit. */
fun unsentMoreLine(more: Int): String = if (more == 1) "1 more unsent message" else "$more more unsent messages"

/** One chat's unsent texts for the list's notice: how many certainly failed, and how many may have been sent. */
data class UnsentChat(val chatGuid: String, val chatTitle: String, val notSent: Int, val unconfirmed: Int)

/**
 * The chats that hold a text which failed or may have (a send in flight is not
 * news), in the order their first such text was sent. The title is the newest
 * one stored that is not blank.
 */
fun unsentChats(outbox: List<UnsentText>): List<UnsentChat> =
    outbox.filter { !it.sending }.groupBy { it.chatGuid }.map { (guid, texts) ->
        UnsentChat(
            chatGuid = guid,
            chatTitle = texts.lastOrNull { it.chatTitle.isNotBlank() }?.chatTitle.orEmpty(),
            notSent = texts.count { it.certainlyNotSent },
            unconfirmed = texts.count { !it.certainlyNotSent },
        )
    }

/**
 * The name stored with an unsent text for [chatGuid], from the thread's title:
 * a thread without a name has its identifier for a title (a phone number or an
 * e-mail address in the relay's spelling), which says nothing in a toast or a
 * notification, so it is stored as no name ([unsentLine] then says "another chat").
 */
fun unsentChatTitle(threadTitle: String, chatGuid: String): String = if (threadTitle == chatGuid) "" else threadTitle

/** The second line of the list's notice for one chat. */
fun unsentChatLine(notSent: Int, unconfirmed: Int): String {
    val total = notSent + unconfirmed
    val noun = if (total == 1) "1 message" else "$total messages"
    return when {
        unconfirmed == 0 -> "$noun not sent"
        notSent == 0 -> "$noun may not have been sent"
        else -> "$noun not sent or not confirmed"
    }
}

/** How a send of one text ended, for whoever shows the chat (ChatVM): [via] when delivered, else [why]. */
data class TextSendResult(
    val chatGuid: String,
    val text: String,
    val via: String?,
    val why: UnsentWhy?,
    val sentAtMillis: Long,
)

/** How the owner is told about a text that was not delivered. */
sealed interface UnsentAlert {
    /** The app is on screen: a toast, naming the chat when it is not the open one. */
    data class Toast(val text: String) : UnsentAlert
    /** Nobody is looking at the app (background, screen off, the lock screen in front): a notification that opens the chat. */
    data class Notification(val title: String, val text: String) : UnsentAlert
}

/**
 * The alert for [entry], which just failed for [why]. A toast is seen only
 * while the app is in front and unlocked; otherwise it would be shown to nobody
 * and the failure would wait, unannounced, for the next time the chat is opened.
 * The notification carries no message text: it may show on the lock screen.
 * [entry] is the text as it was sent this time, so its [UnsentText.maybeSent]
 * speaks of the sends before this one.
 */
fun unsentAlert(entry: UnsentText, why: UnsentWhy, appInFront: Boolean, locked: Boolean, openChat: String?): UnsentAlert =
    if (appInFront && !locked) {
        UnsentAlert.Toast(unsentLine(why, otherChat = entry.chatTitle.takeIf { openChat != entry.chatGuid }, maybeSent = entry.maybeSent))
    } else {
        UnsentAlert.Notification(
            title = entry.chatTitle.ifBlank {
                if (why.certain && !entry.maybeSent) "Message not sent" else "Message may not have been sent"
            },
            text = unsentLine(why, maybeSent = entry.maybeSent),
        )
    }

// ---- after a send that ended without an answer ----

/**
 * When ChatVM re-reads the open chat after a send there ended without an
 * answer, as the waits between one look and the next: at once, twice more
 * shortly after, and once after the relay's own time for a send has passed
 * ([RELAY_SEND_BUDGET_SECONDS] after the send, [sinceSendMillis] of which are
 * gone). The row says "check the chat": these looks make the chat on screen
 * worth checking, also where the socket echoes nothing (Google Messages) or was
 * down. They only add bubbles; they decide nothing about the text.
 */
fun recheckDelaysMillis(sinceSendMillis: Long): List<Long> {
    val last = RELAY_SEND_BUDGET_SECONDS * 1000L + 5_000L - sinceSendMillis.coerceAtLeast(0L)
    val looks = (listOf(0L, 3_000L, 10_000L).filter { it == 0L || it < last } + listOfNotNull(last.takeIf { it > 0L })).sorted()
    return looks.mapIndexed { i, at -> if (i == 0) at else at - looks[i - 1] }
}

/**
 * For a chat that is being opened: when its newest text whose send ended
 * without an answer was sent, if the relay's own time for that send
 * ([RELAY_SEND_BUDGET_SECONDS], and the same margin as above) may not be over;
 * null otherwise. ChatVM then re-reads the chat as it does after such a send
 * ([recheckDelaysMillis]): a text the app was closed over a moment ago comes
 * back as a row saying "check the chat" while the Mac may still be sending it,
 * and the page loaded on opening cannot show a message that is not out yet.
 * The time is the text's first send ([UnsentText.createdAtMillis], the only
 * one stored): for a text sent again since, the looks may end early, which
 * costs nothing. Like those after a send, they only add bubbles.
 */
fun recheckOnOpenSince(outbox: List<UnsentText>, chatGuid: String, nowMillis: Long): Long? =
    outbox.lastOrNull { it.chatGuid == chatGuid && it.why?.certain == false }?.createdAtMillis
        ?.takeIf { nowMillis - it in 0 until RELAY_SEND_BUDGET_SECONDS * 1000L + 5_000L }

// ---- other sends that fail ----

/** The toast for an attachment whose send ended without the relay saying it failed for certain. */
const val ATTACHMENT_UNCONFIRMED_MESSAGE = "The attachment may not have been sent \u2014 check the chat before sending it again"

/**
 * The toast for a failed attachment send: the cause when the route names one
 * (too large, timed out), a plain failure when nothing was sent, and otherwise
 * that it may have gone out, since picking the file again would then send it twice.
 */
fun attachmentFailureMessage(error: Throwable?): String {
    val reason = sendFailureFor(error)
    return when {
        reason != SendFailure.OTHER -> sendFailureMessage(reason)
        unsentWhyFor(error).certain -> sendFailureMessage(SendFailure.OTHER)
        else -> ATTACHMENT_UNCONFIRMED_MESSAGE
    }
}

const val CREATE_CHAT_FAILED_MESSAGE = "Couldn't start the conversation"
const val CREATE_CHAT_UNCONFIRMED_MESSAGE =
    "The conversation may have been started \u2014 check your conversations before sending again"

/**
 * The toast when starting a conversation failed. Its first text travels like
 * any send, so a request that left without a certain refusal may have started
 * the chat and delivered the text: a second tap on Send would deliver it again.
 */
fun createChatFailureMessage(error: Throwable?): String =
    if (unsentWhyFor(error).certain) CREATE_CHAT_FAILED_MESSAGE else CREATE_CHAT_UNCONFIRMED_MESSAGE

const val FACETIME_LINK_FAILED_MESSAGE = "Couldn't text the link \u2014 opening yours"
const val FACETIME_LINK_UNCONFIRMED_MESSAGE = "The link may have been sent \u2014 check the chat before sending it again. Opening yours"

/**
 * The toast when the FaceTime link could not be texted to the person called.
 * It travels like any send (into an existing chat or as the first text of a
 * new one), so without a certain refusal it may have been delivered: saying
 * "couldn't" would have the owner share the link by hand a second time.
 */
fun faceTimeLinkFailureMessage(error: Throwable?): String =
    if (unsentWhyFor(error).certain) FACETIME_LINK_FAILED_MESSAGE else FACETIME_LINK_UNCONFIRMED_MESSAGE

/**
 * The message a send into [chatGuid] replies to: the reply banner's ([reply]),
 * only when that reply was started in this same chat ([replyChat]). A banner
 * left over from another chat is never sent along.
 */
fun replyTargetGuid(reply: Msg?, replyChat: String?, chatGuid: String): String? =
    reply?.guid?.takeIf { replyChat == chatGuid }

// ---- the relay's send path ----

/** A send's `via` when BlueBubbles delivered it (also what a reply without `via` means). */
const val SEND_VIA_BLUEBUBBLES = "bb"

/** A send's `via` when Messages.app delivered it through AppleScript. */
const val SEND_VIA_APPLESCRIPT = "applescript"

/**
 * What the app has seen of the relay's send path. [seenBlueBubbles]: a send
 * went through BlueBubbles, so the relay has that engine. [fallback]: the
 * "BlueBubbles down" notice is up (the composer's "Fallback mode" line).
 */
data class SendPathState(val seenBlueBubbles: Boolean = false, val fallback: Boolean = false)

/** The toast a send may cause. */
enum class SendPathNotice { NONE, FALLBACK, RESTORED }

data class SendPathStep(val state: SendPathState, val notice: SendPathNotice)

/**
 * The state after a send that went out [via], and the notice it causes.
 *
 * "BlueBubbles down" is said only on a change from BlueBubbles to AppleScript:
 * a relay with no BlueBubbles engine delivers every send through AppleScript,
 * which is its normal path and not a fallback, and the app cannot tell the two
 * apart until a send has gone through BlueBubbles ([sendPathSeeded] carries
 * that knowledge across restarts). "Restored" follows only a "down" that was
 * said. A failed send (null) and any other path (Google Messages) change nothing.
 */
fun sendPathAfter(state: SendPathState, via: String?): SendPathStep = when (via) {
    SEND_VIA_BLUEBUBBLES -> SendPathStep(
        SendPathState(seenBlueBubbles = true, fallback = false),
        if (state.fallback) SendPathNotice.RESTORED else SendPathNotice.NONE,
    )
    SEND_VIA_APPLESCRIPT ->
        if (state.seenBlueBubbles && !state.fallback) SendPathStep(state.copy(fallback = true), SendPathNotice.FALLBACK)
        else SendPathStep(state, SendPathNotice.NONE)
    else -> SendPathStep(state, SendPathNotice.NONE)
}

/**
 * A relay address as app_prefs may keep it to remember something about that
 * relay: the hex SHA-256 of [base], so the address itself is not written to a
 * second file. "" for no relay.
 */
fun relayMark(base: String): String =
    if (base.isEmpty()) ""
    else MessageDigest.getInstance("SHA-256").digest(base.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * True when the relay at [base] is the one an earlier send was seen to go
 * through BlueBubbles on ([storedMark], from AppPrefs; null when none was).
 * Another relay, or no relay, is never taken for it.
 */
fun blueBubblesKnownFor(storedMark: String?, base: String): Boolean =
    base.isNotEmpty() && !storedMark.isNullOrEmpty() && storedMark == relayMark(base)

/**
 * The state a send starts from: this session's, plus what the app remembers of
 * this relay from earlier ones ([relayHasBlueBubbles]). The owner opening the
 * app while BlueBubbles is already down is told so on the first send, as before
 * the state machine existed; a relay that never delivered through BlueBubbles
 * is never remembered as having it, so it still never shows either toast.
 */
fun sendPathSeeded(state: SendPathState, relayHasBlueBubbles: Boolean): SendPathState =
    if (relayHasBlueBubbles && !state.seenBlueBubbles) state.copy(seenBlueBubbles = true) else state
