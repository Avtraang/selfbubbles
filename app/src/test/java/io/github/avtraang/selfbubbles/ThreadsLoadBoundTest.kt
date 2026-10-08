package io.github.avtraang.selfbubbles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * A load of the thread list always ends (loadThreads, Imsg.kt; Api.threads is it
 * over the shared client, the live relay address and THREADS_LOAD_TIMEOUT_MILLIS).
 * The list screen says nothing final while its first load is in flight, so a load
 * that never ended was a list screen that stayed blank. Each case runs against a
 * stand-in on the loopback interface, or a name lookup that is held, with a bound
 * of a fraction of a second in place of the app's twenty: what is checked is that
 * the bound ends the load, long before any of the shared client's own timeouts
 * (10 s each) could have.
 */
class ThreadsLoadBoundTest {

    /** A stand-in for the relay: every connection it accepts is handed to [serve], on its own thread. */
    private class StubRelay(private val serve: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val base = "http://127.0.0.1:${server.localPort}"
        val accepted = AtomicInteger()
        private val acceptor = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                accepted.incrementAndGet()
                Thread { runCatching { socket.use(serve) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            acceptor.interrupt()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        /** Well below every timeout of the shared client, well above a loaded machine's jitter. */
        const val PROMPT_MILLIS = 5_000L

        /** Reads one request head; false when the peer hung up first. */
        fun readRequest(socket: Socket): Boolean {
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return false
                head.append(b.toChar())
            }
            return true
        }

        fun reply(status: String, contentType: String, body: String): (Socket) -> Unit = { socket ->
            if (readRequest(socket)) {
                val bytes = body.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    /** Runs one load; returns what it threw (null: it returned a list) and how long it took. */
    private fun timed(load: suspend () -> List<Thread>): Pair<Throwable?, Long> {
        val start = System.nanoTime()
        val thrown = runCatching { runBlocking { load() } }.exceptionOrNull()
        return thrown to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    private fun assertTimedOut(thrown: Throwable?) {
        assertTrue("$thrown", thrown is InterruptedIOException)
        // The load's own bound, not one step's: the connect and read timeouts throw the subclass.
        assertFalse("$thrown", thrown is SocketTimeoutException)
        assertEquals(LoadFailure.TIMED_OUT, loadFailureFor(thrown))
        assertEquals(ThreadsLoad.Failed(LoadFailure.TIMED_OUT), threadsLoadOnEnd(ThreadsLoad.Loading, thrown))
    }

    // ---- the bound ----

    @Test fun theListLoad_hasTwentySeconds_andNoOtherRequestChanged() {
        assertEquals(20_000L, THREADS_LOAD_TIMEOUT_MILLIS)
        // Longer than one step may take, so a connect or a read that times out still reports as that.
        assertTrue(THREADS_LOAD_TIMEOUT_MILLIS > http.connectTimeoutMillis)
        assertTrue(THREADS_LOAD_TIMEOUT_MILLIS > http.readTimeoutMillis)
        // "About 20 seconds": the caller's own wait ends a second later at the latest.
        assertTrue(THREADS_LOAD_GIVE_UP_MILLIS in 1L..2_000L)
        // The bound is on the list request alone. The shared client, and what is derived from it for
        // text sends (which may take 75 s) and uploads (minutes), have no overall timeout, as before.
        assertEquals(0, http.callTimeoutMillis)
        assertEquals(0, httpSend.callTimeoutMillis)
        assertEquals(0, httpUpload.callTimeoutMillis)
        assertEquals(10_000, http.connectTimeoutMillis)
        assertEquals(10_000, http.readTimeoutMillis)
    }

    @Test fun aRelayThatAcceptsAndNeverAnswers_endsAtTheBound() {
        // The request is read and nothing ever comes back. The shared client would wait 10 s for the first byte.
        StubRelay { socket -> if (readRequest(socket)) while (socket.getInputStream().read() >= 0) { /* until the app hangs up */ } }.use { relay ->
            val (thrown, took) = timed { loadThreads(http, relay.base, timeoutMillis = 400, giveUpMillis = 30_000) }
            assertTimedOut(thrown)
            assertEquals(1, relay.accepted.get())
            // OkHttp's call timeout ended it: not earlier than the bound, and long before the caller's own wait.
            assertTrue("took $took ms", took in 350L..PROMPT_MILLIS)
        }
    }

    @Test fun anAnswerThatTricklesIn_endsAtTheBound() {
        // Headers at once, then a byte every 50 ms for ever: no single read ever times out.
        val trickle: (Socket) -> Unit = { socket ->
            if (readRequest(socket)) {
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                out.flush()
                while (true) { out.write(' '.code); out.flush(); java.lang.Thread.sleep(50) }
            }
        }
        StubRelay(trickle).use { relay ->
            val (thrown, took) = timed { loadThreads(http, relay.base, timeoutMillis = 600, giveUpMillis = 30_000) }
            assertTimedOut(thrown)
            assertTrue("took $took ms", took in 550L..PROMPT_MILLIS)
        }
    }

    @Test fun aNameLookupThatNeverReturns_endsAtTheBoundToo() {
        // The platform resolver is a blocking call nothing can interrupt, and OkHttp's timeouts do not cover it:
        // its call timeout only marks the call cancelled and goes on waiting for the lookup. The caller does not.
        val held = CountDownLatch(1)
        val lookups = AtomicInteger()
        val stuck = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                // Held far longer than the load may take, but not for ever: a load that waited
                // for the lookup after all fails the test below instead of hanging the build.
                held.await(2 * PROMPT_MILLIS, TimeUnit.MILLISECONDS)
                throw UnknownHostException("released")
            }
        }
        val client = http.newBuilder().dns(stuck).build()
        try {
            val (thrown, took) = timed {
                loadThreads(client, "http://relay.example.test", timeoutMillis = 300, giveUpMillis = 300)
            }
            assertTimedOut(thrown)
            assertEquals(1, lookups.get())
            assertEquals("the lookup is still out", 1L, held.count)
            assertTrue("took $took ms", took in 550L..PROMPT_MILLIS)
        } finally {
            held.countDown()            // the abandoned lookup ends; its call was cancelled, so nothing connects
        }
    }

    @Test fun aCallerThatGoesAway_takesItsRequestWithIt() {
        // The screen is gone (its scope is cancelled) while the relay sits on the request: the caller is released
        // at once, and the connection is closed instead of being held for the rest of the bound.
        val hungUp = CountDownLatch(1)
        val silent: (Socket) -> Unit = { socket ->
            if (readRequest(socket)) {
                runCatching { while (socket.getInputStream().read() >= 0) { /* nothing more is sent */ } }
                hungUp.countDown()
            }
        }
        StubRelay(silent).use { relay ->
            runBlocking {
                val load = launch(Dispatchers.Default) { loadThreads(http, relay.base, timeoutMillis = 60_000, giveUpMillis = 60_000) }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROMPT_MILLIS)
                while (relay.accepted.get() == 0 && System.nanoTime() < deadline) java.lang.Thread.sleep(10)
                assertEquals(1, relay.accepted.get())
                val start = System.nanoTime()
                load.cancelAndJoin()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < PROMPT_MILLIS)
                assertTrue(load.isCancelled)
            }
            assertTrue("the request was not cancelled", hungUp.await(PROMPT_MILLIS, TimeUnit.MILLISECONDS))
        }
    }

    // ---- everything else about the load is as it was ----

    @Test fun aList_isReturned() {
        val body = """{"threads":[{"chat_guid":"iMessage;-;+15555550100","chat_name":"Sam Example","last_rowid":7,"unread":2},""" +
            """{"chat_guid":"iMessage;+;chat0000000000000000","last_rowid":3,"is_group":true,"archived":true}]}"""
        StubRelay(reply("200 OK", "application/json", body)).use { relay ->
            val (thrown, took) = timed { loadThreads(http, relay.base) }      // the app's own bound: an answer does not wait for it
            assertEquals(null, thrown)
            assertTrue("took $took ms", took < PROMPT_MILLIS)
            val threads = runBlocking { loadThreads(http, relay.base, timeoutMillis = 2_000, giveUpMillis = 2_000) }
            assertEquals(listOf("iMessage;-;+15555550100", "iMessage;+;chat0000000000000000"), threads.map { it.chat_guid })
            assertEquals("Sam Example", threads[0].chat_name)
            assertEquals(2, threads[0].unread)
            assertTrue(threads[1].archived)
        }
    }

    @Test fun anEmptyList_isAList() {
        StubRelay(reply("200 OK", "application/json", """{"threads":[]}""")).use { relay ->
            assertEquals(emptyList<Thread>(), runBlocking { loadThreads(http, relay.base) })
        }
    }

    @Test fun aReplyThatIsNotTheList_isClassifiedAsBefore() {
        data class Row(val status: String, val type: String, val body: String, val expected: LoadFailure)
        val rows = listOf(
            Row("401 Unauthorized", "application/json", """{"detail":"unauthorized"}""", LoadFailure.TOKEN_REJECTED),
            Row("403 Forbidden", "text/html", "<!DOCTYPE html><html><body>Forbidden</body></html>", LoadFailure.ACCESS_REJECTED),
            Row("200 OK", "text/html; charset=UTF-8", "<!DOCTYPE html><html><body>Sign in</body></html>", LoadFailure.ACCESS_REJECTED),
            Row("502 Bad Gateway", "text/html", "<html><body>Bad gateway</body></html>", LoadFailure.RELAY_ERROR),
            Row("404 Not Found", "application/json", """{"detail":"Not Found"}""", LoadFailure.NOT_A_RELAY),
            Row("200 OK", "application/json", """{"ok":true}""", LoadFailure.NOT_A_RELAY),
        )
        for (r in rows) {
            StubRelay(reply(r.status, r.type, r.body)).use { relay ->
                val (thrown, took) = timed { loadThreads(http, relay.base) }
                assertTrue("$r: $thrown", thrown is LoadFailedException)
                assertEquals("$r", r.expected, loadFailureFor(thrown))
                assertTrue("$r took $took ms", took < PROMPT_MILLIS)
                // The exception names the status and nothing of the reply.
                assertEquals("threads HTTP ${r.status.substringBefore(' ')}", thrown!!.message)
            }
        }
    }

    @Test fun nothingListening_failsAtOnce_asUnreachable() {
        // A port nothing listens on: refused long before the bound, and reported as that, not as a timeout.
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val (thrown, took) = timed { loadThreads(http, "http://127.0.0.1:$port") }
        assertEquals("$thrown", LoadFailure.UNREACHABLE, loadFailureFor(thrown))
        assertTrue("took $took ms", took < PROMPT_MILLIS)
    }

    @Test fun aNameThatDoesNotResolve_failsAtOnce_andTheListShowsItsPanel() {
        // What airplane mode is to the app: the lookup fails straight away. From the load to what the list
        // screen is told to show, with no thread held: the failure panel, and never the loading row before it.
        val offline = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        }
        val (thrown, took) = timed { loadThreads(http.newBuilder().dns(offline).build(), "http://relay.example.test") }
        assertTrue("$thrown", thrown is UnknownHostException)
        // At once, as far as a stand-in can say: the same generous bound as every other case here. (How long
        // the phone's own resolver takes to say no is not something a JVM test can measure.)
        assertTrue("took $took ms", took < PROMPT_MILLIS)
        val state = threadsLoadOnEnd(threadsLoadOnStart(ThreadsLoad.Loading), thrown)
        assertEquals(ThreadsLoad.Failed(LoadFailure.UNREACHABLE), state)
        assertEquals(
            ThreadListNotice.Failed(LoadFailure.UNREACHABLE, retrying = false),
            threadListNotice(threadCount = 0, searchActive = false, archivedView = false, load = state, loadingSlow = false),
        )
        assertEquals("Can't reach the relay", loadFailureMessage(LoadFailure.UNREACHABLE))
    }

    @Test fun noRelayConfigured_failsAtOnce_withoutARequest() {
        // BASE is "" in a build with no relay: the URL does not parse, as before, and the caller's runCatching has it.
        val (thrown, took) = timed { loadThreads(http, "") }
        assertTrue("$thrown", thrown is IllegalArgumentException)
        assertEquals(LoadFailure.OTHER, loadFailureFor(thrown))
        assertTrue("took $took ms", took < PROMPT_MILLIS)
    }

    @Test fun noRelayConfigured_theAddressIsNotEvenLookedAt_onTheCallersThread() {
        // ChatVM starts the first load while it is being constructed, and viewModelScope runs a coroutine in
        // place, on the caller's thread, up to its first suspension. A load that finds its unparsable address
        // right there has ended before it suspended once, and the rest of refreshThreads() runs inside the
        // constructor. So the load hands itself to the IO dispatcher before it does anything that can fail:
        // started here the way Main.immediate starts it, with no dispatcher in between, the failure is
        // raised on a worker thread.
        //
        // Whether the caller is also told "suspended" is not asserted. It is, short of a worker finishing
        // the whole load in the instant between the hand-off and the caller's return; that instant is the
        // dispatcher's, not this function's, which is why ChatVM does not depend on it at all
        // (ChatVMConstructionTest).
        val ended = LinkedBlockingQueue<Result<List<Thread>>>()
        val completion = object : Continuation<List<Thread>> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<List<Thread>>) { ended.put(result) }
        }
        val load: suspend () -> List<Thread> = { loadThreads(http, "") }
        val inPlace = runCatching { load.startCoroutineUninterceptedOrReturn(completion) }
        val thrown = inPlace.exceptionOrNull() ?: ended.poll(PROMPT_MILLIS, TimeUnit.MILLISECONDS)?.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalArgumentException)
        // Where the address was parsed: the exception as first thrown, under whatever the coroutine
        // machinery wrapped around it on the way back, has this test nowhere in its stack.
        val origin = generateSequence(thrown) { it.cause }.last()
        val frames = origin.stackTrace.map { it.className }
        assertTrue("$frames", frames.isNotEmpty())
        assertEquals(emptyList<String>(), frames.filter { it.startsWith(ThreadsLoadBoundTest::class.java.name) })
        assertTrue("$frames", frames.any { it.startsWith("kotlinx.coroutines.scheduling.") })
    }
}
