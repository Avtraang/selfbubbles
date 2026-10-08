package io.github.avtraang.selfbubbles

// How the last load of the thread list ended, and what the list screen shows for it
// while it has no conversation to show: nothing for the first moment of the first load
// and "Loading conversations…" once it has run longer than that, "No conversations
// yet" after a load that came back empty, and an error panel with the reason and a
// retry after one that failed. Plain Kotlin (no Android, no Compose), so ThreadsLoadTest
// pins every table here.

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why the thread list could not be loaded, as far as the app can tell. */
enum class LoadFailure {
    /** DNS or connect failure: no network, the host does not resolve, nothing listens. */
    UNREACHABLE,
    /** The connection or the answer took too long, or the whole load did (THREADS_LOAD_TIMEOUT_MILLIS, Imsg.kt). */
    TIMED_OUT,
    /** TLS failed: a certificate the system does not trust, a hostname mismatch, no TLS on that port. */
    NO_TRUSTED_CERT,
    /** The relay answered 401: the token is not the relay's. */
    TOKEN_REJECTED,
    /** A 403, a redirect or a web page answered in the relay's place: Cloudflare Access. */
    ACCESS_REJECTED,
    /** A 5xx from the relay's address: the relay itself, or the route in front of a relay that is down. */
    RELAY_ERROR,
    /** Something answered, but not with a thread list: another service, or a path the relay does not serve. */
    NOT_A_RELAY,
    OTHER,
}

/** A failed load with a classified cause. Like [SendFailedException] it never carries a URL, a header or a body. */
class LoadFailedException(val reason: LoadFailure, detail: String) : IOException(detail)

/** True for a reply that is a web page: by its Content-Type, or by how its body starts (the test RelayProbe uses). */
fun looksLikeHtml(contentType: String?, body: String): Boolean =
    contentType?.contains("text/html", ignoreCase = true) == true || body.trimStart().startsWith("<")

/**
 * Classifies a reply to `GET /threads` that was not a thread list. The relay
 * answers 401 to a wrong token on every route but `/health`; Cloudflare Access
 * answers a wrong service-token pair with a 403 or a redirect to its login page
 * (which the shared client follows, so it arrives as a 2xx web page); a 5xx is
 * the relay failing or the route reporting that nothing answers behind it.
 */
fun loadFailureFor(httpCode: Int, html: Boolean): LoadFailure = when {
    httpCode == 401 -> LoadFailure.TOKEN_REJECTED
    httpCode == 403 || httpCode in 300..399 -> LoadFailure.ACCESS_REJECTED
    httpCode in 500..599 -> LoadFailure.RELAY_ERROR
    html -> LoadFailure.ACCESS_REJECTED
    else -> LoadFailure.NOT_A_RELAY
}

/** Classifies whatever a load threw. A throwable's own text is never used: it can quote a host or a body. */
fun loadFailureFor(t: Throwable?): LoadFailure = when (t) {
    is LoadFailedException -> t.reason
    // SSLHandshakeException, SSLPeerUnverifiedException and SSLProtocolException all extend it.
    is SSLException -> LoadFailure.NO_TRUSTED_CERT
    // A connect or read that timed out (SocketTimeoutException extends it), and a load that
    // ran into its overall bound: OkHttp's call timeout and loadThreads (Imsg.kt) both throw
    // a plain InterruptedIOException.
    is InterruptedIOException -> LoadFailure.TIMED_OUT
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> LoadFailure.UNREACHABLE
    else -> LoadFailure.OTHER
}

/** The reason line under "Couldn't load conversations". Names no host, no token and nothing from the reply. */
fun loadFailureMessage(reason: LoadFailure): String = when (reason) {
    LoadFailure.UNREACHABLE -> "Can't reach the relay"
    LoadFailure.TIMED_OUT -> "The relay did not answer in time"
    LoadFailure.NO_TRUSTED_CERT -> "No trusted certificate at the relay's address"
    LoadFailure.TOKEN_REJECTED -> "The relay rejected the token"
    LoadFailure.ACCESS_REJECTED -> "Cloudflare Access rejected the request"
    LoadFailure.RELAY_ERROR -> "The relay's address answered with a server error"
    LoadFailure.NOT_A_RELAY -> "The address did not answer like a relay"
    LoadFailure.OTHER -> "Connection failed"
}

/** The state of the list's last load (ChatVM.threadsLoad). */
sealed interface ThreadsLoad {
    /**
     * No load has finished yet (a fresh start, or the relay settings just changed).
     * One is in flight for as long as this lasts: the state is entered with a load
     * starting, and the first load to end after that ends it ([threadsLoadOnEndOf]),
     * which the one that started with it does within about THREADS_LOAD_TIMEOUT_MILLIS.
     */
    data object Loading : ThreadsLoad
    /** The last load returned a list, possibly an empty one. */
    data object Ok : ThreadsLoad
    /** The last load failed for [reason]; [retrying] while another one is in flight. */
    data class Failed(val reason: LoadFailure, val retrying: Boolean = false) : ThreadsLoad
}

/** A load starts: a failure shown on screen says it is being retried; nothing else changes (no flicker). */
fun threadsLoadOnStart(current: ThreadsLoad): ThreadsLoad =
    if (current is ThreadsLoad.Failed) current.copy(retrying = true) else current

/**
 * The load started last ended with [error] (null: it returned a list). A
 * cancelled load (the screen is gone) says nothing about the relay, so it only
 * ends the retry.
 */
fun threadsLoadOnEnd(current: ThreadsLoad, error: Throwable?): ThreadsLoad = when {
    error == null -> ThreadsLoad.Ok
    error is CancellationException -> if (current is ThreadsLoad.Failed) current.copy(retrying = false) else current
    else -> ThreadsLoad.Failed(loadFailureFor(error))
}

/**
 * A load that was not the one started last ended with [error]. The one started
 * last has the last word ([threadsLoadOnEnd]), so this one changes no verdict
 * that is on screen, with one exception: while the list still waits for its
 * first verdict ([ThreadsLoad.Loading]: the later load is still out) it gives
 * that verdict, and a failure reads as one that is being retried, which it is.
 *
 * Without the exception the wait has no end on a link where the list request
 * hangs and the socket does not: every message that arrives starts a refresh,
 * each one takes the last word from the load about to run out of time, and
 * "Loading conversations…" stays for as long as messages keep coming.
 */
fun threadsLoadOnEarlierEnd(current: ThreadsLoad, error: Throwable?): ThreadsLoad = when {
    current !is ThreadsLoad.Loading -> current
    error == null -> ThreadsLoad.Ok
    error is CancellationException -> current
    else -> ThreadsLoad.Failed(loadFailureFor(error), retrying = true)
}

/**
 * The whole rule for a load that ended, by its number. ChatVM numbers the loads
 * in the order they start: [load] is the one that ended with [error], [latest]
 * the one started last, and [forgotten] the one started last before the list
 * last began waiting anew (the relay settings changed; 0 on a fresh start).
 *
 * A load up to [forgotten] was started under the settings that were replaced
 * (another address, or the same address with another token): however it ends,
 * it says nothing about the relay in force. The load started last has the last
 * word ([threadsLoadOnEnd]). Any other ended while a later one is still out, or
 * after a later one had ended already ([threadsLoadOnEarlierEnd]).
 */
fun threadsLoadOnEndOf(load: Int, latest: Int, forgotten: Int, current: ThreadsLoad, error: Throwable?): ThreadsLoad = when {
    load <= forgotten -> current
    load == latest -> threadsLoadOnEnd(current, error)
    else -> threadsLoadOnEarlierEnd(current, error)
}

/**
 * True when a relay config change left the relay the list was loaded from:
 * [heldBase] is the relay address (RelayConfig.base) the list, the open chat and
 * the send-path state belong to, [newBase] the address now in force. A Save that
 * keeps the address (another token, another map URL) is not a move, and the list
 * stays on screen as it always did; after a move the previous relay's
 * conversations are dropped, so a failed load from the new one shows its error
 * panel instead of them.
 */
fun relayMoved(heldBase: String, newBase: String): Boolean = heldBase != newBase

/**
 * Whether a list that was loaded from the relay at [loadedFrom] may be shown
 * now that the relay in force is [heldBase]. Loads overlap with a Save in
 * Settings: one started against the previous address can end after the list was
 * emptied for the new one, and would put the old relay's conversations back.
 */
fun threadsLoadApplies(loadedFrom: String, heldBase: String): Boolean = loadedFrom == heldBase

/**
 * How long the list waits for its first load before it says that it is loading.
 * An ordinary launch has its list well inside this, so it never shows the row
 * (nothing flashes in front of the list); a load still in flight after it is one
 * the owner is already looking at an empty screen for, and an empty screen that
 * says nothing reads as an app with no conversations, or a broken one.
 */
const val LOADING_NOTICE_DELAY_MILLIS: Long = 1_200

/** What the thread list shows in place of rows it does not have. */
sealed interface ThreadListNotice {
    data object None : ThreadListNotice
    /** "Loading conversations…" beside a small progress indicator. */
    data object Loading : ThreadListNotice
    /** "No conversations yet". */
    data object Empty : ThreadListNotice
    /** "Couldn't load conversations", the reason line and "Try again". */
    data class Failed(val reason: LoadFailure, val retrying: Boolean) : ThreadListNotice
}

/**
 * The whole decision for the list screen. Nothing while there is a conversation
 * to show ([threadCount] counts every thread held, archived ones too: a failed
 * refresh never replaces a list) or while a search is typed. With no thread
 * held, in the inbox and the archived view alike: nothing for the first moment
 * of the first load and the loading row once it is [loadingSlow] (it has been in
 * flight for [LOADING_NOTICE_DELAY_MILLIS]; ChatVM.threadsLoadSlow keeps the
 * time); the failure after a load that failed, which says "Trying again…" on
 * its own button while another one runs. After a load that came back empty:
 * "No conversations yet" in the inbox (the archived view has its own line,
 * [showsNoArchivedLine]). [loadingSlow] means nothing in any other state.
 */
fun threadListNotice(
    threadCount: Int,
    searchActive: Boolean,
    archivedView: Boolean,
    load: ThreadsLoad,
    loadingSlow: Boolean,
): ThreadListNotice = when {
    threadCount > 0 || searchActive -> ThreadListNotice.None
    load is ThreadsLoad.Failed -> ThreadListNotice.Failed(load.reason, load.retrying)
    load is ThreadsLoad.Loading -> if (loadingSlow) ThreadListNotice.Loading else ThreadListNotice.None
    archivedView -> ThreadListNotice.None
    else -> ThreadListNotice.Empty
}

/**
 * Whether the archived view says "No archived conversations": it has no row to
 * show ([archivedCount]) and that is known, from a list that is held
 * ([threadCount] counts every thread, as above; a refresh that failed later
 * changes nothing about it) or from a load that came back empty. Not while the
 * first load is in flight and not after one that failed with nothing held: the
 * list was never fetched, so "nothing archived" would be a guess, and the
 * loading row or the failure panel stands there instead.
 */
fun showsNoArchivedLine(archivedView: Boolean, archivedCount: Int, threadCount: Int, load: ThreadsLoad): Boolean =
    archivedView && archivedCount == 0 && (threadCount > 0 || load is ThreadsLoad.Ok)
