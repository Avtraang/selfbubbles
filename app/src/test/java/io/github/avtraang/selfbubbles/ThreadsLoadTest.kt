package io.github.avtraang.selfbubbles

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The thread list's last load (ThreadsLoad.kt): how a failure is classified,
 * the reason line it gets, how the state moves, which of several overlapping
 * loads may move it, and what the list screen shows when it has no
 * conversation to show: nothing for the first moment of the first load, then
 * that it is loading, then the list, "No conversations yet" or the failure.
 * (That a load always ends is ThreadsLoadBoundTest.)
 */
class ThreadsLoadTest {

    // ---- a reply that was not the list ----

    @Test fun replies_areClassifiedByStatusAndShape() {
        data class Row(val code: Int, val html: Boolean, val expected: LoadFailure)
        val rows = listOf(
            Row(401, false, LoadFailure.TOKEN_REJECTED),       // the relay's own answer to a wrong token
            Row(401, true, LoadFailure.TOKEN_REJECTED),
            Row(403, true, LoadFailure.ACCESS_REJECTED),       // Cloudflare Access, wrong service token
            Row(403, false, LoadFailure.ACCESS_REJECTED),
            Row(302, false, LoadFailure.ACCESS_REJECTED),      // a redirect that was not followed
            Row(200, true, LoadFailure.ACCESS_REJECTED),       // the Access login page, after the redirect
            Row(404, true, LoadFailure.ACCESS_REJECTED),       // any other web page below 500
            Row(500, false, LoadFailure.RELAY_ERROR),          // the relay failed
            Row(502, true, LoadFailure.RELAY_ERROR),           // the route says nothing answers behind it
            Row(530, true, LoadFailure.RELAY_ERROR),
            Row(524, false, LoadFailure.RELAY_ERROR),
            Row(404, false, LoadFailure.NOT_A_RELAY),          // a path the relay does not serve
            Row(200, false, LoadFailure.NOT_A_RELAY),          // 2xx, but not a thread list
            Row(204, false, LoadFailure.NOT_A_RELAY),
        )
        for (r in rows) assertEquals("$r", r.expected, loadFailureFor(r.code, r.html))
    }

    @Test fun html_isRecognisedByTypeOrByBody() {
        assertTrue(looksLikeHtml("text/html; charset=UTF-8", "anything"))
        assertTrue(looksLikeHtml("TEXT/HTML", ""))
        assertTrue(looksLikeHtml(null, "  \n<!DOCTYPE html><html>"))
        assertTrue(looksLikeHtml("application/json", "<html>"))
        assertFalse(looksLikeHtml("application/json", """{"threads":[]}"""))
        assertFalse(looksLikeHtml(null, "unauthorized"))
        assertFalse(looksLikeHtml("text/plain", ""))
    }

    // ---- no reply at all ----

    @Test fun throwables_areClassified() {
        data class Row(val thrown: Throwable?, val expected: LoadFailure)
        val rows = listOf(
            Row(UnknownHostException("relay.example.test"), LoadFailure.UNREACHABLE),
            Row(ConnectException("refused"), LoadFailure.UNREACHABLE),
            Row(NoRouteToHostException("no route"), LoadFailure.UNREACHABLE),
            // One step took too long: OkHttp's connect and read timeouts.
            Row(SocketTimeoutException("timeout"), LoadFailure.TIMED_OUT),
            Row(SocketTimeoutException("Read timed out"), LoadFailure.TIMED_OUT),
            // The whole load did: OkHttp's call timeout throws a plain InterruptedIOException("timeout") around
            // whatever the cancelled call was doing, and loadThreads throws the same when it stops waiting.
            Row(InterruptedIOException("timeout"), LoadFailure.TIMED_OUT),
            Row(InterruptedIOException("timeout").apply { initCause(SocketException("Socket closed")) }, LoadFailure.TIMED_OUT),
            Row(InterruptedIOException("timeout").apply { initCause(IOException("Canceled")) }, LoadFailure.TIMED_OUT),
            Row(InterruptedIOException("timeout").apply { initCause(UnknownHostException("relay.example.test")) }, LoadFailure.TIMED_OUT),
            Row(InterruptedIOException(), LoadFailure.TIMED_OUT),
            Row(SSLHandshakeException("untrusted"), LoadFailure.NO_TRUSTED_CERT),
            Row(SSLPeerUnverifiedException("hostname"), LoadFailure.NO_TRUSTED_CERT),
            Row(SSLException("closed"), LoadFailure.NO_TRUSTED_CERT),
            Row(IOException("reset"), LoadFailure.OTHER),
            Row(IOException("Canceled"), LoadFailure.OTHER),
            Row(SocketException("Socket closed"), LoadFailure.OTHER),
            Row(IllegalArgumentException("no relay configured"), LoadFailure.OTHER),
            Row(null, LoadFailure.OTHER),
        )
        for (r in rows) assertEquals("${r.thrown}", r.expected, loadFailureFor(r.thrown))
    }

    @Test fun aLoadThatRanOutOfTime_saysTheRelayDidNotAnswerInTime() {
        val overall = InterruptedIOException("timeout")
        assertEquals(ThreadsLoad.Failed(LoadFailure.TIMED_OUT), threadsLoadOnEnd(ThreadsLoad.Loading, overall))
        assertEquals("The relay did not answer in time", loadFailureMessage(loadFailureFor(overall)))
        // Not a cancelled load, which says nothing and would leave the list waiting for ever.
        assertFalse((overall as Throwable) is CancellationException)
    }

    @Test fun aClassifiedException_keepsItsReason() {
        for (reason in LoadFailure.values()) {
            assertEquals(reason, loadFailureFor(LoadFailedException(reason, "threads HTTP 000")))
        }
    }

    // ---- the reason line ----

    @Test fun everyReason_hasItsOwnShortLine() {
        val lines = LoadFailure.values().map { loadFailureMessage(it) }
        assertEquals(lines.size, lines.toSet().size)
        for (line in lines) {
            assertTrue(line, line.isNotBlank() && line.length <= 60)
            assertFalse(line, line.contains("http", ignoreCase = true))      // never a URL
            assertFalse(line, line.endsWith("."))
        }
    }

    @Test fun reasonLines_reuseTheTestConnectionWording() {
        assertEquals("The relay rejected the token", loadFailureMessage(LoadFailure.TOKEN_REJECTED))
        assertEquals(ProbeOutcome.TokenRejected.message, loadFailureMessage(LoadFailure.TOKEN_REJECTED))
        assertTrue(ProbeOutcome.AccessRejected.message.startsWith(loadFailureMessage(LoadFailure.ACCESS_REJECTED)))
        assertEquals("Can't reach the relay", loadFailureMessage(LoadFailure.UNREACHABLE))
        assertEquals("The relay did not answer in time", loadFailureMessage(LoadFailure.TIMED_OUT))
        assertEquals("No trusted certificate at the relay's address", loadFailureMessage(LoadFailure.NO_TRUSTED_CERT))
        assertEquals("The relay's address answered with a server error", loadFailureMessage(LoadFailure.RELAY_ERROR))
        assertEquals("The address did not answer like a relay", loadFailureMessage(LoadFailure.NOT_A_RELAY))
        assertEquals("Connection failed", loadFailureMessage(LoadFailure.OTHER))
    }

    // ---- the state ----

    private val failed = ThreadsLoad.Failed(LoadFailure.UNREACHABLE)

    @Test fun aLoadStarting_onlyMarksAShownFailureAsRetrying() {
        assertEquals(ThreadsLoad.Loading, threadsLoadOnStart(ThreadsLoad.Loading))
        assertEquals(ThreadsLoad.Ok, threadsLoadOnStart(ThreadsLoad.Ok))                  // no flicker of "No conversations yet"
        assertEquals(failed.copy(retrying = true), threadsLoadOnStart(failed))
        assertEquals(failed.copy(retrying = true), threadsLoadOnStart(failed.copy(retrying = true)))
    }

    @Test fun aLoadEnding_recordsHowItEnded() {
        for (before in listOf(ThreadsLoad.Loading, ThreadsLoad.Ok, failed, failed.copy(retrying = true))) {
            assertEquals(ThreadsLoad.Ok, threadsLoadOnEnd(before, null))
            assertEquals(
                ThreadsLoad.Failed(LoadFailure.TIMED_OUT),
                threadsLoadOnEnd(before, SocketTimeoutException("timeout")),
            )
            assertEquals(
                ThreadsLoad.Failed(LoadFailure.TOKEN_REJECTED),
                threadsLoadOnEnd(before, LoadFailedException(LoadFailure.TOKEN_REJECTED, "threads HTTP 401")),
            )
        }
    }

    @Test fun aCancelledLoad_saysNothingAboutTheRelay() {
        val cancelled = CancellationException("scope cancelled")
        assertEquals(ThreadsLoad.Loading, threadsLoadOnEnd(ThreadsLoad.Loading, cancelled))
        assertEquals(ThreadsLoad.Ok, threadsLoadOnEnd(ThreadsLoad.Ok, cancelled))
        assertEquals(failed, threadsLoadOnEnd(failed.copy(retrying = true), cancelled))
    }

    @Test fun tryAgain_roundTrip() {
        var s: ThreadsLoad = ThreadsLoad.Loading
        s = threadsLoadOnEnd(threadsLoadOnStart(s), UnknownHostException("relay.example.test"))
        assertEquals(ThreadsLoad.Failed(LoadFailure.UNREACHABLE, retrying = false), s)
        s = threadsLoadOnStart(s)                                                         // "Try again" tapped
        assertEquals(ThreadsLoad.Failed(LoadFailure.UNREACHABLE, retrying = true), s)
        s = threadsLoadOnEnd(s, null)
        assertEquals(ThreadsLoad.Ok, s)
    }

    // ---- refreshes overlap: which load speaks ----

    private val timedOut = InterruptedIOException("timeout")
    private val rejected = LoadFailedException(LoadFailure.TOKEN_REJECTED, "threads HTTP 401")
    private val gone = CancellationException("scope cancelled")

    @Test fun anEarlierLoad_givesTheFirstVerdict_andChangesNoOther() {
        // The list still waits (a later load is out): the first one to end says how it went, and a
        // failure is one that is being retried, so the panel's button reads "Trying again…".
        assertEquals(ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true), threadsLoadOnEarlierEnd(ThreadsLoad.Loading, timedOut))
        assertEquals(ThreadsLoad.Failed(LoadFailure.TOKEN_REJECTED, retrying = true), threadsLoadOnEarlierEnd(ThreadsLoad.Loading, rejected))
        assertEquals(ThreadsLoad.Ok, threadsLoadOnEarlierEnd(ThreadsLoad.Loading, null))
        assertEquals(ThreadsLoad.Loading, threadsLoadOnEarlierEnd(ThreadsLoad.Loading, gone))          // says nothing, as ever
        // A verdict is there already: only the load started last replaces it.
        for (current in listOf(ThreadsLoad.Ok, failed, failed.copy(retrying = true))) {
            for (error in listOf(null, timedOut, rejected, gone)) {
                assertEquals("$current $error", current, threadsLoadOnEarlierEnd(current, error))
            }
        }
    }

    @Test fun whichLoadSpeaks_table() {
        data class Row(val load: Int, val latest: Int, val forgotten: Int, val current: ThreadsLoad, val error: Throwable?, val expected: ThreadsLoad)
        val retrying = failed.copy(retrying = true)
        val rows = listOf(
            // The load started last: the last word, whatever was there.
            Row(1, 1, 0, ThreadsLoad.Loading, null, ThreadsLoad.Ok),
            Row(1, 1, 0, ThreadsLoad.Loading, timedOut, ThreadsLoad.Failed(LoadFailure.TIMED_OUT)),
            Row(3, 3, 0, ThreadsLoad.Ok, timedOut, ThreadsLoad.Failed(LoadFailure.TIMED_OUT)),
            Row(3, 3, 0, retrying, null, ThreadsLoad.Ok),
            Row(3, 3, 0, retrying, rejected, ThreadsLoad.Failed(LoadFailure.TOKEN_REJECTED)),
            Row(3, 3, 0, retrying, gone, failed),
            Row(3, 3, 2, ThreadsLoad.Loading, null, ThreadsLoad.Ok),                    // the first load after a Save
            // An earlier one while the list still waits: the first verdict, marked as being retried.
            Row(1, 2, 0, ThreadsLoad.Loading, timedOut, ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true)),
            Row(1, 5, 0, ThreadsLoad.Loading, rejected, ThreadsLoad.Failed(LoadFailure.TOKEN_REJECTED, retrying = true)),
            Row(1, 2, 0, ThreadsLoad.Loading, null, ThreadsLoad.Ok),
            Row(1, 2, 0, ThreadsLoad.Loading, gone, ThreadsLoad.Loading),
            Row(4, 5, 3, ThreadsLoad.Loading, timedOut, ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true)),
            // An earlier one once a verdict is there (from another earlier load, or from the last one,
            // which ended first): nothing.
            Row(2, 5, 0, ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true), rejected, ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true)),
            Row(1, 2, 0, ThreadsLoad.Ok, timedOut, ThreadsLoad.Ok),
            Row(1, 2, 0, ThreadsLoad.Ok, null, ThreadsLoad.Ok),
            Row(1, 2, 0, failed, null, failed),
            Row(1, 2, 0, failed, timedOut, failed),
            Row(1, 2, 0, retrying, timedOut, retrying),
            // One started before the relay settings changed: nothing, in any state, however it ends.
            Row(1, 2, 1, ThreadsLoad.Loading, timedOut, ThreadsLoad.Loading),
            Row(1, 2, 1, ThreadsLoad.Loading, rejected, ThreadsLoad.Loading),
            Row(1, 2, 1, ThreadsLoad.Loading, null, ThreadsLoad.Loading),
            Row(2, 4, 3, ThreadsLoad.Loading, timedOut, ThreadsLoad.Loading),
            Row(3, 4, 3, ThreadsLoad.Loading, null, ThreadsLoad.Loading),
            Row(1, 2, 1, ThreadsLoad.Ok, timedOut, ThreadsLoad.Ok),
            Row(1, 2, 1, failed, null, failed),
            Row(1, 2, 1, retrying, gone, retrying),
            // Also in the instant after the change in which no new load has started yet.
            Row(1, 1, 1, ThreadsLoad.Loading, timedOut, ThreadsLoad.Loading),
            Row(1, 1, 1, ThreadsLoad.Loading, null, ThreadsLoad.Loading),
        )
        for (r in rows) {
            assertEquals("$r", r.expected, threadsLoadOnEndOf(r.load, latest = r.latest, forgotten = r.forgotten, r.current, r.error))
        }
        // Every state under each of the three voices, with a list, a failure and a cancellation.
        for (current in listOf(ThreadsLoad.Loading, ThreadsLoad.Ok, failed, retrying)) for (error in listOf(null, timedOut, gone)) {
            assertEquals(threadsLoadOnEnd(current, error), threadsLoadOnEndOf(7, latest = 7, forgotten = 2, current, error))
            assertEquals(threadsLoadOnEarlierEnd(current, error), threadsLoadOnEndOf(5, latest = 7, forgotten = 2, current, error))
            assertEquals(current, threadsLoadOnEndOf(2, latest = 7, forgotten = 2, current, error))
            assertEquals(current, threadsLoadOnEndOf(1, latest = 7, forgotten = 2, current, error))
        }
    }

    /** ChatVM's bookkeeping, as plain values: the state, the number of the load started last, the forgotten ones. */
    private class Loads(var state: ThreadsLoad = ThreadsLoad.Loading) {
        var latest = 0
        var forgotten = 0
        fun start(): Int { state = threadsLoadOnStart(state); return ++latest }
        fun end(load: Int, error: Throwable?) { state = threadsLoadOnEndOf(load, latest, forgotten, state, error) }
        fun settingsChanged() { state = ThreadsLoad.Loading; forgotten = latest }
        fun notice(slow: Boolean = true) =
            threadListNotice(threadCount = 0, searchActive = false, archivedView = false, load = state, loadingSlow = slow)
    }

    @Test fun aListRequestThatHangs_whileRefreshesKeepComing_stillGetsAVerdict() {
        // The socket is up and the list request is not answered: every message that arrives starts another
        // load before the one in flight has run out of time, so the load started last never is the one ending.
        val loads = Loads()
        val first = loads.start()
        val second = loads.start()                       // the socket opened
        assertEquals(ThreadListNotice.Loading, loads.notice())
        val third = loads.start()                        // a message arrived
        loads.end(first, timedOut)                       // 20 s after it started; two later ones are still out
        assertEquals(ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = true), loads.state)
        assertEquals(ThreadListNotice.Failed(LoadFailure.TIMED_OUT, retrying = true), loads.notice())
        // It goes on like that: the panel stays, and says that it is trying.
        val fourth = loads.start()
        loads.end(second, timedOut)
        loads.end(third, timedOut)
        assertEquals(ThreadListNotice.Failed(LoadFailure.TIMED_OUT, retrying = true), loads.notice())
        // Until the load started last ends: it has the last word, either way.
        loads.end(fourth, null)
        assertEquals(ThreadsLoad.Ok, loads.state)
        val again = Loads()
        val a = again.start(); val b = again.start()
        again.end(a, timedOut); again.end(b, timedOut)
        assertEquals(ThreadsLoad.Failed(LoadFailure.TIMED_OUT, retrying = false), again.state)   // "Try again" is offered
    }

    @Test fun anOrdinaryLaunch_isWhatItWas_whicheverLoadEndsFirst() {
        // The launch's own load and the one the socket starts a moment later, both answered.
        val inOrder = Loads()
        val a = inOrder.start(); val b = inOrder.start()
        inOrder.end(a, null); assertEquals(ThreadsLoad.Ok, inOrder.state)
        inOrder.end(b, null); assertEquals(ThreadsLoad.Ok, inOrder.state)
        val crossed = Loads()
        val c = crossed.start(); val d = crossed.start()
        crossed.end(d, null); assertEquals(ThreadsLoad.Ok, crossed.state)
        crossed.end(c, null); assertEquals(ThreadsLoad.Ok, crossed.state)
        // With no network at all there is one load only (the socket never opens): the panel, with "Try again".
        val offline = Loads()
        offline.end(offline.start(), UnknownHostException("relay.example.test"))
        assertEquals(ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = false), offline.notice(slow = false))
        // The network comes back, the socket opens and reloads: "Trying again…", then the list.
        val retry = offline.start()
        assertEquals(ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = true), offline.notice(slow = false))
        offline.end(retry, null)
        assertEquals(ThreadsLoad.Ok, offline.state)
    }

    @Test fun aLoadStartedBeforeASave_saysNothingAboutTheNewSettings() {
        // A load is out to the relay with the old token when Settings saves a new one: the list waits anew.
        val loads = Loads()
        val old = loads.start()
        loads.settingsChanged()
        val new = loads.start()
        // The old one is refused (its token was the wrong one): that is not the new settings' failure.
        loads.end(old, rejected)
        assertEquals(ThreadsLoad.Loading, loads.state)
        assertEquals(ThreadListNotice.Loading, loads.notice())
        loads.end(new, null)
        assertEquals(ThreadsLoad.Ok, loads.state)
        // Nor does it take a verdict away that the new settings have.
        val later = Loads()
        val before = later.start()
        later.settingsChanged()
        later.end(later.start(), rejected)
        later.end(before, null)
        assertEquals(ThreadsLoad.Failed(LoadFailure.TOKEN_REJECTED), later.state)
    }

    // ---- what the list screen shows ----

    private val everyLoad = listOf(ThreadsLoad.Loading, ThreadsLoad.Ok, failed, failed.copy(retrying = true))
    private val both = listOf(false, true)

    @Test fun notice_table() {
        data class Row(
            val archived: Boolean, val load: ThreadsLoad, val slow: Boolean,
            val expected: ThreadListNotice,
        )
        val none = ThreadListNotice.None
        val loading = ThreadListNotice.Loading
        val empty = ThreadListNotice.Empty
        val failure = ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = false)
        val retrying = ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = true)
        // No thread held and no search typed: every load state, in both views, before and after the delay.
        val rows = listOf(
            // The first load is in flight. Its first moment: nothing, as an ordinary launch always looked.
            Row(archived = false, ThreadsLoad.Loading, slow = false, none),
            Row(archived = true, ThreadsLoad.Loading, slow = false, none),
            // Longer than that: the list says so, in the archived view as in the inbox.
            Row(archived = false, ThreadsLoad.Loading, slow = true, loading),
            Row(archived = true, ThreadsLoad.Loading, slow = true, loading),
            // A load that returned no thread at all. How long it took no longer matters.
            Row(archived = false, ThreadsLoad.Ok, slow = false, empty),
            Row(archived = false, ThreadsLoad.Ok, slow = true, empty),
            Row(archived = true, ThreadsLoad.Ok, slow = false, none),      // the archived view has its own line
            Row(archived = true, ThreadsLoad.Ok, slow = true, none),
            // A load that failed, with nothing to show in its place.
            Row(archived = false, failed, slow = false, failure),
            Row(archived = false, failed, slow = true, failure),
            Row(archived = true, failed, slow = false, failure),
            Row(archived = true, failed, slow = true, failure),
            // Another one is in flight: the panel stays and its button reads "Trying again…"; no loading row beside it.
            Row(archived = false, failed.copy(retrying = true), slow = false, retrying),
            Row(archived = false, failed.copy(retrying = true), slow = true, retrying),
            Row(archived = true, failed.copy(retrying = true), slow = false, retrying),
            Row(archived = true, failed.copy(retrying = true), slow = true, retrying),
        )
        assertEquals(both.size * everyLoad.size * both.size, rows.map { Triple(it.archived, it.load, it.slow) }.toSet().size)
        for (r in rows) {
            assertEquals(
                "$r", r.expected,
                threadListNotice(threadCount = 0, searchActive = false, archivedView = r.archived, load = r.load, loadingSlow = r.slow),
            )
        }
    }

    @Test fun withAListOnScreen_orASearchTyped_thereIsNeverANotice() {
        // Threads exist: a failed refresh never replaces the list, and nothing is "loading" over it.
        // A search is typed: its own results and "No results" own the screen.
        var checked = 0
        for (threads in listOf(0, 1, 3, 40)) for (search in both) {
            if (threads == 0 && !search) continue                       // notice_table
            for (archived in both) for (load in everyLoad) for (slow in both) {
                assertEquals(
                    "threads=$threads search=$search archived=$archived load=$load slow=$slow",
                    ThreadListNotice.None, threadListNotice(threads, search, archived, load, slow),
                )
                checked++
            }
        }
        assertEquals(7 * 2 * 4 * 2, checked)
    }

    @Test fun theLoadingRow_waitsLongEnoughForAnOrdinaryLaunch_andNotLongEnoughToLookEmpty() {
        // An ordinary launch has its list in well under a second; a second and a bit of nothing is the most a slow one gets.
        assertTrue(LOADING_NOTICE_DELAY_MILLIS in 1_000L..1_500L)
        // The row has most of the load's own bound left to be seen in before the verdict replaces it.
        assertTrue(LOADING_NOTICE_DELAY_MILLIS * 10 < THREADS_LOAD_TIMEOUT_MILLIS)
    }

    /** What the inbox shows with no thread held, from a fresh start until [ended] is the load's end. */
    private fun firstLoad(ended: Throwable?, slow: Boolean): List<ThreadListNotice> {
        fun notice(load: ThreadsLoad, loadingSlow: Boolean) =
            threadListNotice(threadCount = 0, searchActive = false, archivedView = false, load = load, loadingSlow = loadingSlow)
        var s: ThreadsLoad = ThreadsLoad.Loading
        val seen = mutableListOf<ThreadListNotice>()
        s = threadsLoadOnStart(s); seen += notice(s, false)              // the load starts
        if (slow) seen += notice(s, true)                                // the delay passes with the load still out
        s = threadsLoadOnEnd(s, ended); seen += notice(s, slow)          // the load ends; the flag stays what it was
        return seen
    }

    @Test fun anOrdinaryLaunch_neverShowsTheLoadingRow() {
        // The list arrived inside the delay: the row was never on screen (the screen then shows the list itself).
        assertFalse(ThreadListNotice.Loading in firstLoad(ended = null, slow = false))
        assertEquals(listOf(ThreadListNotice.None, ThreadListNotice.Empty), firstLoad(ended = null, slow = false))
        // Nor does a launch that fails at once, with no network at all: nothing, then the panel.
        assertEquals(
            listOf(ThreadListNotice.None, ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = false)),
            firstLoad(ended = UnknownHostException("relay.example.test"), slow = false),
        )
    }

    @Test fun aSlowFirstLoad_saysSo_untilItEnds() {
        val loading = ThreadListNotice.Loading
        // Ended well, with no conversation: the row gives way to "No conversations yet".
        assertEquals(listOf(ThreadListNotice.None, loading, ThreadListNotice.Empty), firstLoad(ended = null, slow = true))
        // Ran into the load's bound: the row gives way to the failure panel.
        assertEquals(
            listOf(ThreadListNotice.None, loading, ThreadListNotice.Failed(LoadFailure.TIMED_OUT, retrying = false)),
            firstLoad(ended = InterruptedIOException("timeout"), slow = true),
        )
        // Ended well with conversations: the list is there, and no notice with it.
        assertEquals(
            ThreadListNotice.None,
            threadListNotice(threadCount = 12, searchActive = false, archivedView = false, load = ThreadsLoad.Ok, loadingSlow = true),
        )
    }

    @Test fun tryAgain_keepsThePanel_withItsOwnWordsOnTheButton() {
        // "Try again" tapped (or the socket came back and reloaded): the panel says it is trying, and no loading
        // row replaces it, however long the retry takes.
        val retrying = threadsLoadOnStart(failed)
        for (slow in both) {
            assertEquals(
                ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = true),
                threadListNotice(threadCount = 0, searchActive = false, archivedView = false, load = retrying, loadingSlow = slow),
            )
        }
    }

    @Test fun noArchivedConversations_isSaidOnlyWhenItIsKnown() {
        data class Row(val archivedView: Boolean, val archived: Int, val threads: Int, val load: ThreadsLoad, val expected: Boolean)
        val rows = mutableListOf<Row>()
        for (load in everyLoad) {
            // The inbox never says it, whatever is held.
            rows += Row(archivedView = false, archived = 0, threads = 0, load, false)
            rows += Row(archivedView = false, archived = 0, threads = 5, load, false)
            rows += Row(archivedView = false, archived = 2, threads = 5, load, false)
            // The archived view with rows to show does not either.
            rows += Row(archivedView = true, archived = 2, threads = 5, load, false)
            // A list is held and none of it is archived: that is known, also when a later refresh failed.
            rows += Row(archivedView = true, archived = 0, threads = 5, load, true)
        }
        // Nothing held: known only after a load that came back empty.
        rows += Row(archivedView = true, archived = 0, threads = 0, ThreadsLoad.Ok, true)
        // The first load is still out (the loading row may be up), or it failed (the panel is): a guess.
        rows += Row(archivedView = true, archived = 0, threads = 0, ThreadsLoad.Loading, false)
        rows += Row(archivedView = true, archived = 0, threads = 0, failed, false)
        rows += Row(archivedView = true, archived = 0, threads = 0, failed.copy(retrying = true), false)
        for (r in rows) {
            assertEquals("$r", r.expected, showsNoArchivedLine(r.archivedView, r.archived, r.threads, r.load))
        }
    }

    @Test fun theArchivedView_neverShowsItsLineBesideTheLoadingRowOrThePanel() {
        for (load in everyLoad) for (slow in both) {
            val notice = threadListNotice(threadCount = 0, searchActive = false, archivedView = true, load = load, loadingSlow = slow)
            val line = showsNoArchivedLine(archivedView = true, archivedCount = 0, threadCount = 0, load = load)
            assertFalse("$load slow=$slow", line && notice != ThreadListNotice.None)
        }
    }

    // ---- whose list is it? ----

    @Test fun aSaveThatKeepsTheAddress_keepsTheList_aMoveDropsIt() {
        val base = "https://relay.example.test"
        assertFalse(relayMoved(base, base))                                   // the token or the map URL was edited
        assertTrue(relayMoved(base, "https://other.example.test"))            // another relay
        assertTrue(relayMoved(base, "https://relay.example.test:8443"))
        assertTrue(relayMoved(base, "https://relay.example.test/imsg"))
        assertTrue(relayMoved(base, ""))                                      // reset to a build with no relay
        assertTrue(relayMoved("", base))                                      // the first run: nothing was held anyway
        assertFalse(relayMoved("", ""))
    }

    @Test fun afterAMove_aFailedLoad_showsItsPanel_insteadOfThePreviousRelaysList() {
        // What ChatVM.relayConfigChanged leaves behind after a move: no thread, and the failure of the new load.
        val failed = ThreadsLoad.Failed(LoadFailure.UNREACHABLE)
        assertEquals(
            ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = false),
            threadListNotice(threadCount = 0, searchActive = false, archivedView = false, load = failed, loadingSlow = false),
        )
        // Kept (the address did not change), the list stays and the failure is not shown over it.
        assertEquals(
            ThreadListNotice.None,
            threadListNotice(threadCount = 12, searchActive = false, archivedView = false, load = failed, loadingSlow = false),
        )
    }

    @Test fun aListLoadedFromThePreviousAddress_isNotShownAfterAMove() {
        val old = "https://relay.example.test"
        val new = "https://other.example.test"
        // A refresh was in flight to the old address when Settings saved the new one: its list is dropped.
        assertFalse(threadsLoadApplies(loadedFrom = old, heldBase = new))
        assertFalse(threadsLoadApplies(loadedFrom = old, heldBase = ""))       // reset to a build with no relay
        assertFalse(threadsLoadApplies(loadedFrom = old, heldBase = "$old:8443"))
        // Every ordinary refresh, and a Save that kept the address, shows its list as before.
        assertTrue(threadsLoadApplies(loadedFrom = old, heldBase = old))
        assertTrue(threadsLoadApplies(loadedFrom = new, heldBase = new))
    }
}
