package io.github.avtraang.selfbubbles

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Around a send (SendRecovery.kt, and the send request in Imsg.kt): one tap is
 * one POST; a text is kept until the relay says it was delivered or the owner
 * discards it; a failure is called "not sent" only when that is certain, and
 * otherwise says to check the chat; the composer is never part of any of it;
 * and the two toasts about the relay's send path follow a change from
 * BlueBubbles to AppleScript, never a relay that simply has no BlueBubbles.
 */
class SendRecoveryTest {

    // ---- was it the relay that answered the send? ----

    @Test fun theRelaysOwnReplies_areReplies() {
        assertTrue(isRelaySendReply("application/json", """{"ok":true,"via":"bb","bb":{"status":200}}"""))
        assertTrue(isRelaySendReply("application/json", """{"ok":true,"via":"applescript"}"""))
        assertTrue(isRelaySendReply("application/json", """{"ok":true}"""))              // a BlueBubbles success without `via`
        assertTrue(isRelaySendReply(null, """  {"ok":true,"via":"gmessages"}  """))
        assertTrue(isRelaySendReply("application/json", "{}"))
    }

    @Test fun aWebPageInTheRelaysPlace_isNotADelivery() {
        // Cloudflare Access answers a wrong or expired service token with its login page; after the
        // redirect it is a 200.
        val login = "<!DOCTYPE html><html><head><title>Sign in</title></head><body>…</body></html>"
        assertFalse(isRelaySendReply("text/html; charset=UTF-8", login))
        assertFalse(isRelaySendReply(null, login))
        assertFalse(isRelaySendReply("application/json", "  \n<html>"))
        assertFalse(isRelaySendReply("text/html", """{"ok":true}"""))                    // a page, whatever it holds
    }

    @Test fun a2xxThatIsNotAJsonObject_isNotADelivery() {
        for (body in listOf("", "   ", "OK", "true", "[1,2]", "\"sent\"", "42", "{\"ok\":", "unauthorized")) {
            assertFalse(body, isRelaySendReply("application/json", body))
        }
    }

    @Test fun textSends_waitLongerThanTheRelayMayTake_andLessThanTheTunnel() {
        assertEquals(55, RELAY_SEND_BUDGET_SECONDS)                       // 15 s BlueBubbles + 2 x 20 s osascript
        assertTrue(SEND_READ_TIMEOUT_SECONDS > RELAY_SEND_BUDGET_SECONDS)
        assertTrue(SEND_READ_TIMEOUT_SECONDS < 100)                       // a Cloudflare Tunnel answers 524 at about 100 s
        assertEquals(SEND_READ_TIMEOUT_SECONDS * 1000, httpSend.readTimeoutMillis.toLong())
        // Nothing else changed: the same interceptors (the only code that attaches credentials), redirects as before.
        assertEquals(http.interceptors, httpSend.interceptors)
        assertEquals(http.networkInterceptors, httpSend.networkInterceptors)
        assertEquals(http.connectTimeoutMillis, httpSend.connectTimeoutMillis)
        assertEquals(http.followRedirects, httpSend.followRedirects)
        assertEquals(10_000, http.readTimeoutMillis)                      // every other call keeps the short wait
        // Connecting may still be tried on the relay's other addresses; what stops a repeat is the body (below).
        assertTrue(httpSend.retryOnConnectionFailure)
    }

    // ---- one tap, one POST ----

    private val chat = "iMessage;-;+15555550100"

    @Test fun aTextSend_isWrittenAtMostOnce() {
        val req = textSendRequest("https://relay.example.test", chat, "on my way", "guid-7")
        assertEquals("POST", req.method)
        assertEquals("https://relay.example.test/send", req.url.toString())
        val body = req.body!!
        // OkHttp never repeats a one-shot body: not after a connection that failed with the request
        // already out, not after a 408, a 503 with Retry-After: 0, a 421 or a 307/308.
        assertTrue(body.isOneShot())
        assertEquals("application/json", body.contentType()!!.toString().substringBefore(';'))
        val sent = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals("""{"chat_guid":"$chat","text":"on my way","reply_to_guid":"guid-7"}""", sent)
        assertEquals(sent.toByteArray().size.toLong(), body.contentLength())
    }

    @Test fun sentOnce_changesNothingButTheRepeat() {
        val plain = "payload".toRequestBody("text/plain".toMediaType())
        assertFalse(plain.isOneShot())
        val once = plain.sentOnce()
        assertTrue(once.isOneShot())
        assertEquals(plain.contentType(), once.contentType())
        assertEquals(plain.contentLength(), once.contentLength())
        assertEquals("payload", Buffer().also { once.writeTo(it) }.readUtf8())
    }

    /**
     * A stand-in for the relay behind a kept-alive connection: it answers
     * `/warm`, and on `/send` it reads the whole request and then drops the
     * connection without a word, which is what the phone sees when the link dies
     * after the request has left. [sends] counts the POSTs to `/send` it received.
     */
    private class DroppingRelay : java.io.Closeable {
        private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val sends = AtomicInteger()
        val base = "http://127.0.0.1:${server.localPort}"
        private val acceptor = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        private fun serve(socket: Socket) = socket.use { s ->
            val input = s.getInputStream()
            val out = s.getOutputStream()
            while (true) {
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return@use
                    head.append(b.toChar())
                }
                val lines = head.toString().split("\r\n")
                val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                var read = 0
                while (read < length) { if (input.read() < 0) return@use; read++ }
                if (lines.first().startsWith("POST /send ")) {
                    sends.incrementAndGet()
                    return@use                                   // the answer never comes
                }
                val reply = """{"ok":true}"""
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.length}\r\n\r\n$reply").toByteArray())
                out.flush()
            }
        }

        override fun close() { server.close(); acceptor.interrupt() }
    }

    /** One request on the app's send client; the pooled connection it leaves behind is what the next call rides. */
    private fun warm(relay: DroppingRelay) {
        httpSend.newCall(Request.Builder().url("${relay.base}/warm").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("""{"ok":true}""", it.body!!.string())     // read to the end, so the connection goes back to the pool
        }
    }

    @Test fun aSendWhoseConnectionDiesAfterItLeft_isNotSentASecondTime() {
        DroppingRelay().use { relay ->
            warm(relay)
            val failure = runCatching {
                httpSend.newCall(textSendRequest(relay.base, chat, "on my way", null)).execute().close()
            }.exceptionOrNull()
            // The failure reaches the caller, which reports it as "no answer", and the relay got ONE message.
            assertTrue("$failure", failure is IOException)
            assertEquals(1, relay.sends.get())
            assertFalse(unsentWhyFor(failure).certain)
        }
    }

    @Test fun theSameSendWithAnOrdinaryBody_isRepeatedByOkHttp() {
        // The behaviour sentOnce() exists to stop, shown on the same stand-in: the test above would
        // pass for the wrong reason if this client did not repeat a replayable POST by itself.
        DroppingRelay().use { relay ->
            warm(relay)
            val replayable = """{"chat_guid":"$chat","text":"on my way"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("${relay.base}/send").post(replayable).build()
            assertTrue(runCatching { httpSend.newCall(request).execute().close() }.isFailure)
            assertEquals(2, relay.sends.get())
        }
    }

    // ---- is it certain that nothing was sent? ----

    @Test fun statuses_table() {
        data class Row(val code: Int, val html: Boolean, val expected: UnsentWhy)
        val rows = listOf(
            // Refused before any engine ran: certain.
            Row(400, false, UnsentWhy.REFUSED),
            Row(401, false, UnsentWhy.TOKEN_REJECTED),
            Row(403, false, UnsentWhy.ACCESS_REJECTED),
            Row(404, false, UnsentWhy.REFUSED),
            Row(413, false, UnsentWhy.TOO_LARGE),
            Row(422, false, UnsentWhy.REFUSED),
            Row(429, false, UnsentWhy.REFUSED),
            Row(501, false, UnsentWhy.NO_ENGINE),                 // the relay's "no configured engine can…"
            Row(302, false, UnsentWhy.ACCESS_REJECTED),           // a redirect that was not followed
            Row(307, false, UnsentWhy.ACCESS_REJECTED),
            Row(200, true, UnsentWhy.ACCESS_REJECTED),            // Cloudflare's login page, after the redirect
            Row(200, false, UnsentWhy.NOT_A_RELAY),
            // The Mac may have sent it, or still may.
            Row(408, false, UnsentWhy.NO_ANSWER),
            Row(504, false, UnsentWhy.NO_ANSWER),
            Row(522, false, UnsentWhy.NO_ANSWER),
            Row(524, false, UnsentWhy.NO_ANSWER),
            Row(500, false, UnsentWhy.SERVER_ERROR),
            Row(502, false, UnsentWhy.SERVER_ERROR),              // the relay: every engine failed, or merely timed out
            Row(502, true, UnsentWhy.SERVER_ERROR),               // the route: the relay could not be heard
            Row(503, false, UnsentWhy.SERVER_ERROR),
            Row(520, true, UnsentWhy.SERVER_ERROR),
            Row(530, true, UnsentWhy.SERVER_ERROR),
        )
        for (r in rows) assertEquals("$r", r.expected, unsentWhyFor(r.code, r.html))
        for (code in 500..599) assertFalse("$code", unsentWhyFor(code, html = false).certain && code != 501)
    }

    @Test fun throwables_table() {
        data class Row(val error: Throwable?, val expected: UnsentWhy)
        val rows = listOf(
            // Something answered: the status decides.
            Row(SendFailedException(SendFailure.OTHER, "send HTTP 502", 502), UnsentWhy.SERVER_ERROR),
            Row(SendFailedException(SendFailure.OTHER, "send HTTP 401", 401), UnsentWhy.TOKEN_REJECTED),
            Row(SendFailedException(SendFailure.TIMED_OUT, "send HTTP 524", 524), UnsentWhy.NO_ANSWER),
            Row(SendFailedException(SendFailure.TOO_LARGE, "send HTTP 413", 413), UnsentWhy.TOO_LARGE),
            Row(SendFailedException(SendFailure.OTHER, "send HTTP 200, not the relay's reply", 200, html = true), UnsentWhy.ACCESS_REJECTED),
            // Without a status, only the size check before an upload is certain.
            Row(SendFailedException(SendFailure.TOO_LARGE, "attachment is 500 bytes"), UnsentWhy.TOO_LARGE),
            Row(SendFailedException(SendFailure.TIMED_OUT, "timed out"), UnsentWhy.NO_ANSWER),
            Row(SendFailedException(SendFailure.OTHER, "?"), UnsentWhy.NO_ANSWER),
            // Nothing left the phone.
            Row(UnknownHostException("relay.example.test"), UnsentWhy.UNREACHABLE),
            Row(ConnectException("refused"), UnsentWhy.UNREACHABLE),
            Row(NoRouteToHostException("no route"), UnsentWhy.UNREACHABLE),
            Row(SSLHandshakeException("untrusted"), UnsentWhy.NO_TRUSTED_CERT),
            Row(SSLPeerUnverifiedException("hostname"), UnsentWhy.NO_TRUSTED_CERT),
            Row(IllegalArgumentException("no relay configured"), UnsentWhy.NOT_BUILT),
            Row(java.io.FileNotFoundException("cannot open the picked file"), UnsentWhy.NOT_BUILT),   // an attachment that could not be read
            // The request left, or may have, and no answer came back: the Mac may still deliver.
            Row(SocketTimeoutException("timeout"), UnsentWhy.NO_ANSWER),
            Row(IOException("unexpected end of stream"), UnsentWhy.CONNECTION_LOST),
            Row(SocketException("Connection reset"), UnsentWhy.CONNECTION_LOST),
            Row(SSLException("Read error: connection reset by peer"), UnsentWhy.CONNECTION_LOST),   // mid-stream, not a handshake
            // Nothing is known at all.
            Row(null, UnsentWhy.NO_ANSWER),
            Row(IllegalStateException("unexpected"), UnsentWhy.NO_ANSWER),
        )
        for (r in rows) assertEquals("${r.error}", r.expected, unsentWhyFor(r.error))
    }

    @Test fun onlyRefusalsAndFailuresBeforeTheRequestLeft_areCertain() {
        val certain = UnsentWhy.values().filter { it.certain }.toSet()
        assertEquals(
            setOf(
                UnsentWhy.UNREACHABLE, UnsentWhy.NO_TRUSTED_CERT, UnsentWhy.TOKEN_REJECTED, UnsentWhy.ACCESS_REJECTED,
                UnsentWhy.TOO_LARGE, UnsentWhy.REFUSED, UnsentWhy.NO_ENGINE, UnsentWhy.NOT_A_RELAY, UnsentWhy.NOT_BUILT,
            ),
            certain,
        )
    }

    @Test fun aConnectFailureThatHidesAnAttemptWhichWasSent_isNotCertain() {
        // OkHttp retried a send whose connection was reset after the request left, found no network,
        // and reports the retry's failure with the reset only as a suppressed exception.
        val masked = UnknownHostException("relay.example.test").apply { addSuppressed(SocketException("Connection reset")) }
        assertEquals(UnsentWhy.CONNECTION_LOST, unsentWhyFor(masked))
        assertFalse(unsentWhyFor(masked).certain)
        val refused = ConnectException("Network is unreachable").apply { addSuppressed(IOException("stream was reset: CANCEL")) }
        assertEquals(UnsentWhy.CONNECTION_LOST, unsentWhyFor(refused))
        // Earlier attempts that never connected sent nothing: still certain.
        val twoAddresses = ConnectException("refused").apply {
            addSuppressed(ConnectException("Network is unreachable"))
            addSuppressed(SocketTimeoutException("failed to connect"))       // the only timeout OkHttp retries: a connect timeout
        }
        assertEquals(UnsentWhy.UNREACHABLE, unsentWhyFor(twoAddresses))
        assertTrue(unsentWhyFor(twoAddresses).certain)
        // A suppressed non-I/O throwable (a close() that failed) says nothing about the request.
        val closing = UnknownHostException("relay.example.test").apply { addSuppressed(IllegalStateException("closed")) }
        assertEquals(UnsentWhy.UNREACHABLE, unsentWhyFor(closing))
    }

    // ---- what the owner reads ----

    @Test fun aCertainFailure_saysNotSent_andWhy() {
        assertEquals("Not sent — can't reach the relay", unsentLine(UnsentWhy.UNREACHABLE))
        assertEquals("Not sent — no trusted certificate at the relay's address", unsentLine(UnsentWhy.NO_TRUSTED_CERT))
        assertEquals("Not sent — the relay rejected the token", unsentLine(UnsentWhy.TOKEN_REJECTED))
        assertEquals("Not sent — Cloudflare Access rejected the request", unsentLine(UnsentWhy.ACCESS_REJECTED))
        assertEquals("Not sent — the message is too large", unsentLine(UnsentWhy.TOO_LARGE))
        assertEquals("Not sent — the relay refused the message", unsentLine(UnsentWhy.REFUSED))
        assertEquals("Not sent — the relay cannot send into this chat", unsentLine(UnsentWhy.NO_ENGINE))
        assertEquals("Not sent — the address did not answer like a relay", unsentLine(UnsentWhy.NOT_A_RELAY))
        assertEquals("Not sent", unsentLine(UnsentWhy.NOT_BUILT))
    }

    @Test fun anUncertainOne_neverSaysNotSent_andSaysToCheckTheChat() {
        assertEquals("No answer from the relay — check the chat before sending again", unsentLine(UnsentWhy.NO_ANSWER))
        assertEquals(
            "Connection lost before the relay answered — check the chat before sending again",
            unsentLine(UnsentWhy.CONNECTION_LOST),
        )
        assertEquals(
            "The relay's address answered with a server error — check the chat before sending again",
            unsentLine(UnsentWhy.SERVER_ERROR),
        )
        assertEquals(
            "The app closed before the relay answered — check the chat before sending again",
            unsentLine(UnsentWhy.INTERRUPTED),
        )
        for (why in UnsentWhy.values()) {
            val line = unsentLine(why)
            assertEquals("$why", why.certain, line.startsWith("Not sent"))
            assertEquals("$why", !why.certain, line.endsWith("before sending again"))
        }
    }

    @Test fun aLineShownOutsideItsChat_namesTheChat() {
        assertEquals("Message to Sam Example not sent — can't reach the relay", unsentLine(UnsentWhy.UNREACHABLE, "Sam Example"))
        assertEquals("Message to Sam Example not sent", unsentLine(UnsentWhy.NOT_BUILT, "Sam Example"))
        assertEquals(
            "No answer from the relay — check the chat with Sam Example before sending again",
            unsentLine(UnsentWhy.NO_ANSWER, "Sam Example"),
        )
        // A chat with no name at hand is still not taken for the one on screen.
        assertEquals("Message in another chat not sent — the relay rejected the token", unsentLine(UnsentWhy.TOKEN_REJECTED, ""))
        assertEquals("No answer from the relay — check that chat before sending again", unsentLine(UnsentWhy.NO_ANSWER, " "))
    }

    // ---- the outbox ----

    private fun text(id: Long, words: String = "on my way", chatGuid: String = chat, why: UnsentWhy? = null, title: String = "Sam Example") =
        UnsentText(id = id, chatGuid = chatGuid, chatTitle = title, text = words, why = why, createdAtMillis = 1_700_000_000_000 + id)

    @Test fun aText_isInTheOutbox_fromTheTap_untilTheRelayHasIt() {
        val a = text(1)
        var box = outboxAdd(emptyList(), a)
        assertEquals(listOf(a), box)
        assertTrue(box.single().sending)
        // Delivered: gone.
        box = outboxSettle(box, 1, why = null)
        assertTrue(box.isEmpty())
    }

    @Test fun aTextThatFailed_stays_withTheReason_untilTheOwnerDealsWithIt() {
        var box = outboxAdd(emptyList(), text(1))
        box = outboxSettle(box, 1, UnsentWhy.UNREACHABLE)
        assertEquals(UnsentWhy.UNREACHABLE, box.single().why)
        assertFalse(box.single().sending)
        assertEquals("on my way", box.single().text)
        // Nothing but the owner removes it: another text coming and going changes nothing.
        box = outboxAdd(box, text(2, "second"))
        box = outboxSettle(box, 2, why = null)
        assertEquals(listOf(1L), box.map { it.id })
        // "Send again": in flight once more, same text, same reply target, one more attempt.
        val again = outboxRetry(box, 1)
        assertTrue(again.single().sending)
        assertEquals(2, again.single().attempts)
        assertEquals(box.single().text, again.single().text)
        // "Discard".
        assertTrue(outboxDiscard(box, 1).isEmpty())
    }

    @Test fun nothingIsEverDroppedToMakeRoom() {
        var box = emptyList<UnsentText>()
        for (i in 1L..500L) { box = outboxAdd(box, text(i, "t$i")); box = outboxSettle(box, i, UnsentWhy.UNREACHABLE) }
        assertEquals(500, box.size)
        assertEquals((1L..500L).toList(), box.map { it.id })
    }

    @Test fun twoTapsOnSendAgain_areOneSend_andATextInFlightCannotBeDiscarded() {
        val failed = listOf(text(1, why = UnsentWhy.NO_ANSWER))
        val once = outboxRetry(failed, 1)
        assertSame(once.single(), outboxRetry(once, 1).single())          // already in flight: untouched
        assertEquals(2, outboxRetry(once, 1).single().attempts)
        assertEquals(once, outboxDiscard(once, 1))                         // its send would still deliver it
        // An id that is not there changes nothing.
        assertEquals(failed, outboxRetry(failed, 9))
        assertEquals(failed, outboxDiscard(failed, 9))
        assertEquals(failed, outboxSettle(failed, 9, UnsentWhy.UNREACHABLE))
        assertEquals(failed, outboxSettle(failed, 9, null))
    }

    @Test fun twoTextsWithTheSameWords_areTwoTexts() {
        var box = outboxAdd(outboxAdd(emptyList(), text(1, "ok")), text(2, "ok"))
        box = outboxSettle(box, 1, why = null)                             // the first was delivered
        box = outboxSettle(box, 2, UnsentWhy.CONNECTION_LOST)              // the second ended without an answer
        assertEquals(listOf(2L), box.map { it.id })                        // and is still there: nothing matched it by its words
    }

    @Test fun aSendTheLastProcessNeverSawTheEndOf_comesBackUnconfirmed() {
        val stored = listOf(text(1), text(2, "second", why = UnsentWhy.UNREACHABLE), text(3, "third"))
        val now = outboxAfterRestart(stored)
        assertEquals(listOf(UnsentWhy.INTERRUPTED, UnsentWhy.UNREACHABLE, UnsentWhy.INTERRUPTED), now.map { it.why })
        assertEquals(stored.map { it.text }, now.map { it.text })          // every text is kept
        assertFalse(UnsentWhy.INTERRUPTED.certain)                         // it may have been delivered
    }

    @Test fun theStoredOutbox_roundTrips_andSurvivesGarbage() {
        val box = listOf(
            text(1, "line one\nline two \"quoted\" — ok", why = UnsentWhy.SERVER_ERROR).copy(replyToGuid = "guid-7", attempts = 3),
            text(2, "in flight"),
        )
        val back = outboxDecode(outboxEncode(box))
        assertEquals(box, back)
        assertEquals("guid-7", back.first().replyToGuid)
        assertEquals("[]", outboxEncode(emptyList()))
        for (bad in listOf(null, "", "  ", "not json", "{", "{\"a\":1}", "[1,2]", "[{\"id\":1}]")) {
            assertEquals("$bad", emptyList<UnsentText>(), outboxDecode(bad))
        }
        // A reason this build does not know (a newer one wrote it) is not a reason to lose the text.
        val newer = """[{"id":5,"chatGuid":"$chat","text":"kept","why":"SOMETHING_NEW"}]"""
        assertEquals("kept", outboxAfterRestart(outboxDecode(newer)).single().text)
        assertEquals(UnsentWhy.INTERRUPTED, outboxAfterRestart(outboxDecode(newer)).single().why)
        // "Sending for a while" is this process's observation, not a stored fact.
        assertFalse(outboxDecode(outboxEncode(listOf(text(1).copy(slow = true)))).single().slow)
        assertFalse(outboxEncode(listOf(text(1).copy(slow = true))).contains("slow"))
    }

    @Test fun oneStoredTextThatCannotBeRead_costsOnlyItself() {
        val first = """{"id":1,"chatGuid":"$chat","text":"first"}"""
        val third = """{"id":3,"chatGuid":"$chat","text":"third","why":"UNREACHABLE"}"""
        for (bad in listOf("""{"id":2}""", "7", "\"words\"", "null", "[]", """{"id":"x","chatGuid":"$chat","text":"t"}""")) {
            val read = outboxDecode("[$first,$bad,$third]")
            assertEquals(bad, listOf(1L, 3L), read.map { it.id })
            assertEquals(bad, listOf("first", "third"), read.map { it.text })
            assertEquals(bad, listOf(null, UnsentWhy.UNREACHABLE), read.map { it.why })
        }
        // Something that is not a list at all still reads as nothing.
        assertTrue(outboxDecode("""{"texts":[$first]}""").isEmpty())
    }

    // ---- a text sent again after a send that may have gone out ----

    @Test fun aCertainFailureAfterATryThatMayHaveGoneOut_isNeverCalledNotSent() {
        // "Leaving now", walking out of Wi-Fi: the request left and the answer was lost. No bubble can
        // load in the garage, so "Send again" there, and that send fails before it leaves the phone.
        var box = outboxAdd(emptyList(), text(1, "Leaving now"))
        box = outboxSettle(box, 1, UnsentWhy.CONNECTION_LOST)
        box = outboxRetry(box, 1)
        val sentAgain = box.single()                                       // the entry Outbox.deliver hands to unsentAlert
        assertTrue(sentAgain.maybeSent)
        box = outboxSettle(box, 1, UnsentWhy.UNREACHABLE)
        val entry = box.single()
        assertEquals(UnsentWhy.UNREACHABLE, entry.why)
        assertTrue(entry.why!!.certain)                                    // this send sent nothing…
        assertFalse(entry.certainlyNotSent)                                // …but the one before it may have
        // The row above the composer.
        val row = unsentLine(entry.why!!, maybeSent = entry.maybeSent)
        assertEquals("Can't reach the relay, but an earlier try may have gone out — check the chat before sending again", row)
        // The list's notice: "may not have been sent", not "not sent".
        assertEquals(listOf(UnsentChat(chat, "Sam Example", notSent = 0, unconfirmed = 1)), unsentChats(box))
        // The toast, in the chat and outside it, and the notification.
        assertEquals(UnsentAlert.Toast(row), unsentAlert(sentAgain, UnsentWhy.UNREACHABLE, appInFront = true, locked = false, openChat = chat))
        assertEquals(
            UnsentAlert.Toast("Can't reach the relay, but an earlier try may have gone out — check the chat with Sam Example before sending again"),
            unsentAlert(sentAgain, UnsentWhy.UNREACHABLE, appInFront = true, locked = false, openChat = null),
        )
        assertEquals(
            UnsentAlert.Notification("Sam Example", row),
            unsentAlert(sentAgain, UnsentWhy.UNREACHABLE, appInFront = false, locked = false, openChat = null),
        )
        val nameless = unsentAlert(sentAgain.copy(chatTitle = ""), UnsentWhy.TOKEN_REJECTED, false, false, null) as UnsentAlert.Notification
        assertEquals("Message may not have been sent", nameless.title)
        assertEquals("The relay rejected the token, but an earlier try may have gone out — check the chat before sending again", nameless.text)
    }

    @Test fun whateverTheTwoReasons_anEarlierUnconfirmedTry_keepsTheTextUnconfirmed() {
        val unconfirmed = UnsentWhy.values().filter { !it.certain }
        val certain = UnsentWhy.values().filter { it.certain }
        for (first in unconfirmed) for (second in certain) {
            // INTERRUPTED is how a text comes back from a process that ended while it was being sent.
            var box = if (first == UnsentWhy.INTERRUPTED) outboxAfterRestart(listOf(text(1))) else outboxSettle(listOf(text(1)), 1, first)
            assertEquals(first, box.single().why)
            box = outboxSettle(outboxRetry(box, 1), 1, second)
            val entry = box.single()
            assertEquals(second, entry.why)
            assertFalse("$first then $second", entry.certainlyNotSent)
            for (other in listOf(null, "", "Sam Example")) {
                val line = unsentLine(second, other, entry.maybeSent)
                assertFalse("$first then $second: $line", line.contains("not sent", ignoreCase = true))
                assertTrue("$first then $second: $line", line.contains("an earlier try may have gone out", ignoreCase = true))
                assertTrue("$first then $second: $line", line.endsWith("before sending again"))
            }
            assertEquals("$first then $second", UnsentChat(chat, "Sam Example", notSent = 0, unconfirmed = 1), unsentChats(box).single())
        }
        // A cause that has no words of its own, and a chat without a name.
        assertEquals("An earlier try may have gone out — check the chat before sending again", unsentLine(UnsentWhy.NOT_BUILT, maybeSent = true))
        assertEquals(
            "Cloudflare Access rejected the request, but an earlier try may have gone out — check that chat before sending again",
            unsentLine(UnsentWhy.ACCESS_REJECTED, "", maybeSent = true),
        )
        // A send that itself ended unconfirmed reads as it always did.
        for (why in unconfirmed) assertEquals("$why", unsentLine(why), unsentLine(why, maybeSent = true))
    }

    @Test fun theDoubt_staysWithTheText_throughMoreTries_aRestart_andTheFile() {
        var box = outboxSettle(listOf(text(1)), 1, UnsentWhy.NO_ANSWER)
        box = outboxSettle(outboxRetry(box, 1), 1, UnsentWhy.UNREACHABLE)
        box = outboxSettle(outboxRetry(box, 1), 1, UnsentWhy.TOKEN_REJECTED)   // a second certain failure does not clear it
        assertTrue(box.single().maybeSent)
        assertEquals(3, box.single().attempts)
        assertFalse(box.single().certainlyNotSent)
        // Stored and read back; and through a process that ended while the text was being sent yet again.
        assertEquals(box, outboxDecode(outboxEncode(box)))
        val restarted = outboxAfterRestart(outboxDecode(outboxEncode(outboxRetry(box, 1)))).single()
        assertEquals(UnsentWhy.INTERRUPTED, restarted.why)
        assertTrue(restarted.maybeSent)
        // Only a delivery ends it: the text leaves the outbox. (Or the owner's Discard.)
        assertTrue(outboxSettle(outboxRetry(box, 1), 1, why = null).isEmpty())
    }

    @Test fun aTextWhoseEverySendCertainlyFailed_isStillCalledNotSent() {
        var box = outboxSettle(listOf(text(1)), 1, UnsentWhy.UNREACHABLE)
        repeat(3) { box = outboxSettle(outboxRetry(box, 1), 1, UnsentWhy.TOKEN_REJECTED) }
        val entry = box.single()
        assertFalse(entry.maybeSent)
        assertTrue(entry.certainlyNotSent)
        assertEquals("Not sent — the relay rejected the token", unsentLine(entry.why!!, maybeSent = entry.maybeSent))
        assertEquals(listOf(UnsentChat(chat, "Sam Example", notSent = 1, unconfirmed = 0)), unsentChats(box))
        val alert = unsentAlert(entry.copy(chatTitle = ""), UnsentWhy.TOKEN_REJECTED, false, false, null) as UnsentAlert.Notification
        assertEquals("Message not sent", alert.title)
        // A text in flight has no verdict yet.
        assertFalse(text(2).certainlyNotSent)
        // A text stored before the mark existed, with an unconfirmed reason: sent again, it carries the doubt.
        val older = outboxDecode("""[{"id":7,"chatGuid":"$chat","text":"kept","why":"NO_ANSWER"}]""")
        assertFalse(older.single().maybeSent)
        assertTrue(outboxRetry(older, 7).single().maybeSent)
        // The mark is written only once it is set.
        assertFalse(outboxEncode(listOf(text(1, why = UnsentWhy.UNREACHABLE))).contains("maybeSent"))
        assertTrue(outboxEncode(outboxRetry(older, 7)).contains("\"maybeSent\":true"))
    }

    @Test fun theOutboxFile_isExcludedFromBackupAndDeviceTransfer() {
        // The file holds message text. Unit tests run in the app module's directory.
        val res = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(java.io.File(it, "src/main/res/xml"), java.io.File(it, "app/src/main/res/xml")) }
            .first { java.io.File(it, "backup_rules.xml").isFile }
        val exclude = """<exclude domain="sharedpref" path="$OUTBOX_PREFS_FILE.xml" />"""
        assertEquals(1, java.io.File(res, "backup_rules.xml").readText().split(exclude).size - 1)
        // Once under <cloud-backup> and once under <device-transfer>.
        assertEquals(2, java.io.File(res, "data_extraction_rules.xml").readText().split(exclude).size - 1)
    }

    @Test fun ids_neverRepeat() {
        assertEquals(1_000L, nextOutboxId(emptyList(), last = 0, nowMillis = 1_000))
        assertEquals(1_001L, nextOutboxId(emptyList(), last = 1_000, nowMillis = 1_000))      // two sends in one millisecond
        assertEquals(5_001L, nextOutboxId(listOf(text(5_000)), last = 0, nowMillis = 1_000))  // the clock went back
        assertEquals(2_000L, nextOutboxId(listOf(text(5)), last = 7, nowMillis = 2_000))
    }

    // ---- which rows show ----

    @Test fun anOrdinarySend_showsNoRow_aSlowOneShowsSending_aFailedOneShowsItsReason() {
        val fresh = text(1)
        assertFalse(unsentShows(fresh))
        assertTrue(unsentRowsFor(listOf(fresh), chat).shown.isEmpty())
        val slow = outboxMarkSlow(listOf(fresh), 1)
        assertTrue(slow.single().slow)
        assertEquals(listOf(1L), unsentRowsFor(slow, chat).shown.map { it.id })
        // The mark belongs to that send: it goes when the send ends, and a failed text is not "slow".
        assertFalse(outboxSettle(slow, 1, UnsentWhy.NO_ANSWER).single().slow)
        assertEquals(listOf(text(1, why = UnsentWhy.NO_ANSWER)), outboxMarkSlow(listOf(text(1, why = UnsentWhy.NO_ANSWER)), 1))
        // A text being sent again keeps its row from the first moment: it was already on screen.
        assertTrue(unsentShows(outboxRetry(listOf(text(1, why = UnsentWhy.NO_ANSWER)), 1).single()))
        assertTrue(unsentShows(text(1, why = UnsentWhy.UNREACHABLE)))
        assertTrue(OUTBOX_SLOW_MILLIS in 2_000..10_000)
    }

    @Test fun aChatShowsItsOwnTexts_oldestFirst_andCountsTheRest() {
        val other = "iMessage;-;+15555550101"
        val box = listOf(
            text(1, "one", why = UnsentWhy.UNREACHABLE),
            text(2, "elsewhere", chatGuid = other, why = UnsentWhy.UNREACHABLE),
            text(3, "two", why = UnsentWhy.NO_ANSWER),
            text(4, "three", why = UnsentWhy.UNREACHABLE),
            text(5, "four", why = UnsentWhy.UNREACHABLE),
            text(6, "five", why = UnsentWhy.UNREACHABLE),
            text(7, "just tapped"),                                      // in flight, not slow: no row
        )
        val rows = unsentRowsFor(box, chat)
        assertEquals(listOf(1L, 3L), rows.shown.map { it.id })
        assertEquals(3, rows.more)
        assertEquals(UNSENT_ROWS_SHOWN, rows.shown.size)
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), unsentRowsFor(box, chat, limit = 10).shown.map { it.id })   // every one is kept
        assertEquals(UnsentRows(listOf(box[1]), 0), unsentRowsFor(box, other))
        assertEquals(UnsentRows(emptyList(), 0), unsentRowsFor(box, "iMessage;-;+15555550199"))
        assertEquals("1 more unsent message", unsentMoreLine(1))
        assertEquals("2 more unsent messages", unsentMoreLine(2))
    }

    @Test fun theListsNotice_isPerChat_andSkipsSendsInFlight() {
        val other = "iMessage;-;+15555550101"
        val box = listOf(
            text(1, why = UnsentWhy.UNREACHABLE, title = ""),
            text(2, chatGuid = other, why = UnsentWhy.NO_ANSWER, title = "Ada Example"),
            text(3, why = UnsentWhy.NO_ANSWER, title = "Sam Example"),
            text(4, chatGuid = "iMessage;-;+15555550102"),               // in flight only: not news
        )
        assertEquals(
            listOf(UnsentChat(chat, "Sam Example", notSent = 1, unconfirmed = 1), UnsentChat(other, "Ada Example", 0, 1)),
            unsentChats(box),
        )
        assertTrue(unsentChats(emptyList()).isEmpty())
        assertEquals("1 message not sent", unsentChatLine(1, 0))
        assertEquals("3 messages not sent", unsentChatLine(3, 0))
        assertEquals("1 message may not have been sent", unsentChatLine(0, 1))
        assertEquals("2 messages may not have been sent", unsentChatLine(0, 2))
        assertEquals("2 messages not sent or not confirmed", unsentChatLine(1, 1))
    }

    @Test fun aChatWithoutAName_isStoredWithoutOne_notUnderItsIdentifier() {
        assertEquals("Sam Example", unsentChatTitle("Sam Example", chat))
        assertEquals("", unsentChatTitle(chat, chat))                    // Thread.title falls back to the identifier
        assertEquals("Message in another chat not sent", unsentLine(UnsentWhy.NOT_BUILT, unsentChatTitle(chat, chat)))
    }

    // ---- how the owner is told ----

    @Test fun whileTheAppIsOnScreen_aToast_namingTheChatUnlessItIsTheOpenOne() {
        val entry = text(1)
        assertEquals(
            UnsentAlert.Toast("Not sent — can't reach the relay"),
            unsentAlert(entry, UnsentWhy.UNREACHABLE, appInFront = true, locked = false, openChat = chat),
        )
        // Another chat is open, or the list: the toast cannot be read as being about what is on screen.
        assertEquals(
            UnsentAlert.Toast("Message to Sam Example not sent — can't reach the relay"),
            unsentAlert(entry, UnsentWhy.UNREACHABLE, appInFront = true, locked = false, openChat = "iMessage;-;+15555550101"),
        )
        assertEquals(
            UnsentAlert.Toast("No answer from the relay — check the chat with Sam Example before sending again"),
            unsentAlert(entry, UnsentWhy.NO_ANSWER, appInFront = true, locked = false, openChat = null),
        )
    }

    @Test fun whenNobodyIsLooking_aNotification_withoutTheMessageText() {
        val entry = text(1, "the secret words")
        for ((front, locked) in listOf(false to false, false to true, true to true)) {
            val alert = unsentAlert(entry, UnsentWhy.UNREACHABLE, appInFront = front, locked = locked, openChat = chat)
            assertEquals(UnsentAlert.Notification("Sam Example", "Not sent — can't reach the relay"), alert)
        }
        val unknown = unsentAlert(entry, UnsentWhy.CONNECTION_LOST, appInFront = false, locked = false, openChat = null) as UnsentAlert.Notification
        assertEquals("Connection lost before the relay answered — check the chat before sending again", unknown.text)
        assertFalse(unknown.text.contains("secret"))
        assertFalse(unknown.title.contains("secret"))
        // A reply typed in a notification whose chat had no name.
        val nameless = text(2, title = "")
        assertEquals("Message not sent", (unsentAlert(nameless, UnsentWhy.UNREACHABLE, false, false, null) as UnsentAlert.Notification).title)
        assertEquals(
            "Message may not have been sent",
            (unsentAlert(nameless, UnsentWhy.NO_ANSWER, false, false, null) as UnsentAlert.Notification).title,
        )
    }

    // ---- after a send that ended without an answer ----

    @Test fun theChatIsReadAgain_atOnce_andUntilTheRelaysOwnTimeHasPassed() {
        // The connection was lost two seconds after the tap: the Mac may take 53 more.
        val early = recheckDelaysMillis(2_000)
        assertEquals(0L, early.first())
        assertEquals(listOf(0L, 3_000L, 7_000L, 48_000L), early)
        assertEquals(RELAY_SEND_BUDGET_SECONDS * 1000L + 5_000L - 2_000L, early.sum())
        // The app's own timeout ran out: the relay finished long ago, one look says all there is.
        assertEquals(listOf(0L), recheckDelaysMillis(SEND_READ_TIMEOUT_SECONDS * 1000))
        assertEquals(listOf(0L), recheckDelaysMillis(600_000))
        // Late, but inside the relay's time: the last look still waits for its end.
        assertEquals(listOf(0L, 3_000L, 2_000L), recheckDelaysMillis(55_000))
        // A clock that moved backwards is not a reason to look for ever.
        assertEquals(recheckDelaysMillis(0), recheckDelaysMillis(-90_000))
        for (since in listOf(0L, 2_000L, 30_000L, 54_000L, 59_999L, 60_000L, 75_000L)) {
            val waits = recheckDelaysMillis(since)
            assertTrue("$since", waits.all { it >= 0 })
            assertTrue("$since", waits.size in 1..4)
            assertTrue("$since", waits.sum() <= RELAY_SEND_BUDGET_SECONDS * 1000L + 5_000L)
        }
    }

    @Test fun aChatOpenedWhileTheMacMayStillBeSending_isReadAgain() {
        val sentAt = 1_700_000_000_000L
        fun box(why: UnsentWhy?) = listOf(text(1, why = why).copy(createdAtMillis = sentAt))
        // The app was closed a few seconds into a send and is opened again eight seconds after the tap.
        val interrupted = outboxAfterRestart(box(null))
        assertEquals(sentAt, recheckOnOpenSince(interrupted, chat, nowMillis = sentAt + 8_000))
        assertEquals(listOf(0L, 3_000L, 7_000L, 42_000L), recheckDelaysMillis(8_000))     // the looks ChatVM then takes
        assertEquals(sentAt, recheckOnOpenSince(box(UnsentWhy.CONNECTION_LOST), chat, sentAt + 59_999))
        // The relay's time is over: the page loaded on opening says all there is.
        assertNull(recheckOnOpenSince(interrupted, chat, sentAt + (RELAY_SEND_BUDGET_SECONDS + 5) * 1000L))
        assertNull(recheckOnOpenSince(interrupted, chat, sentAt + 86_400_000))
        // Nothing to look for: a certain failure, a send still in flight, another chat, a clock that went back, no text.
        assertNull(recheckOnOpenSince(box(UnsentWhy.UNREACHABLE), chat, sentAt + 8_000))
        assertNull(recheckOnOpenSince(box(null), chat, sentAt + 8_000))
        assertNull(recheckOnOpenSince(interrupted, "iMessage;-;+15555550101", sentAt + 8_000))
        assertNull(recheckOnOpenSince(interrupted, chat, sentAt - 1))
        assertNull(recheckOnOpenSince(emptyList(), chat, sentAt))
        // Of several, the newest decides.
        val two = listOf(
            text(1, why = UnsentWhy.NO_ANSWER).copy(createdAtMillis = sentAt - 300_000),
            text(2, why = UnsentWhy.INTERRUPTED).copy(createdAtMillis = sentAt),
        )
        assertEquals(sentAt, recheckOnOpenSince(two, chat, sentAt + 1_000))
        assertNull(recheckOnOpenSince(two.take(1), chat, sentAt + 1_000))
    }

    // ---- attachments and new conversations ----

    @Test fun anAttachmentFailure_saysItsCause_orThatItMayHaveGoneOut() {
        assertEquals("Too large to send (limit is about 100 MB)", attachmentFailureMessage(SendFailedException(SendFailure.TOO_LARGE, "x")))
        // A timeout is the app or the route giving up on waiting, not the relay giving up on sending (A3-F4).
        assertEquals(
            ATTACHMENT_TIMED_OUT_MESSAGE,
            attachmentFailureMessage(SendFailedException(SendFailure.TIMED_OUT, "send_attachment HTTP 524", 524)),
        )
        assertEquals(ATTACHMENT_TIMED_OUT_MESSAGE, attachmentFailureMessage(SocketTimeoutException("timeout")))
        // Certainly not sent: the plain words.
        assertEquals("Couldn't send attachment", attachmentFailureMessage(SendFailedException(SendFailure.OTHER, "send_attachment HTTP 501", 501)))
        assertEquals("Couldn't send attachment", attachmentFailureMessage(UnknownHostException("relay.example.test")))
        assertEquals("Couldn't send attachment", attachmentFailureMessage(ConnectException("refused")))
        assertEquals("Couldn't send attachment", attachmentFailureMessage(java.io.FileNotFoundException("cannot open the picked file")))
        // The upload left and nothing certain came back: picking the file again could send it twice.
        assertEquals(ATTACHMENT_UNCONFIRMED_MESSAGE, attachmentFailureMessage(SendFailedException(SendFailure.OTHER, "send_attachment HTTP 502", 502)))
        assertEquals(ATTACHMENT_UNCONFIRMED_MESSAGE, attachmentFailureMessage(SocketException("Connection reset")))
        assertEquals("The attachment may not have been sent — check the chat before sending it again", ATTACHMENT_UNCONFIRMED_MESSAGE)
    }

    @Test fun aNewConversationThatFailed_saysSo_orThatItMayHaveBeenStarted() {
        assertEquals("Couldn't start the conversation", createChatFailureMessage(UnknownHostException("relay.example.test")))
        assertEquals("Couldn't start the conversation", createChatFailureMessage(SendFailedException(SendFailure.OTHER, "create_chat HTTP 400", 400)))
        assertEquals("Couldn't start the conversation", createChatFailureMessage(SendFailedException(SendFailure.OTHER, "create_chat HTTP 501", 501)))
        // The relay delivers the first text through the same engines as a send, and goes on after the phone gave up.
        for (error in listOf(SocketTimeoutException("timeout"), IOException("reset"), SendFailedException(SendFailure.OTHER, "create_chat HTTP 502", 502), null)) {
            assertEquals("$error", CREATE_CHAT_UNCONFIRMED_MESSAGE, createChatFailureMessage(error))
        }
        assertEquals(
            "The conversation may have been started — check your conversations before sending again",
            CREATE_CHAT_UNCONFIRMED_MESSAGE,
        )
    }

    @Test fun theFaceTimeLink_isCalledNotTexted_onlyWhenThatIsCertain() {
        assertEquals("Couldn't text the link — opening yours", faceTimeLinkFailureMessage(UnknownHostException("relay.example.test")))
        assertEquals(FACETIME_LINK_FAILED_MESSAGE, faceTimeLinkFailureMessage(SendFailedException(SendFailure.OTHER, "send HTTP 501", 501)))
        // The link travels like any send: without a certain refusal it may have arrived.
        for (error in listOf(SocketTimeoutException("timeout"), SocketException("Connection reset"), SendFailedException(SendFailure.OTHER, "send HTTP 502", 502), null)) {
            assertEquals("$error", FACETIME_LINK_UNCONFIRMED_MESSAGE, faceTimeLinkFailureMessage(error))
        }
        assertEquals("The link may have been sent — check the chat before sending it again. Opening yours", FACETIME_LINK_UNCONFIRMED_MESSAGE)
    }

    // ---- the reply banner ----

    @Test fun aReply_isSentOnlyIntoTheChatItWasStartedIn() {
        val target = Msg(rowid = 7, guid = "guid-7", chat_guid = chat)
        assertEquals("guid-7", replyTargetGuid(target, replyChat = chat, chatGuid = chat))
        // Started in one chat, left without sending, another chat opened: a plain message there.
        assertNull(replyTargetGuid(target, replyChat = chat, chatGuid = "iMessage;-;+15555550101"))
        assertNull(replyTargetGuid(target, replyChat = null, chatGuid = chat))
        assertNull(replyTargetGuid(null, replyChat = chat, chatGuid = chat))
    }

    // ---- a reply typed in a notification ----

    @Test fun theBroadcastOfAnInlineReply_isHeldForLessThanTheSystemAllows() {
        assertTrue(inlineReplyWaitIsSafe(INLINE_REPLY_WAIT_MILLIS))
        assertTrue(INLINE_REPLY_WAIT_MILLIS < 10_000)                         // the system's limit for a foreground broadcast
        assertTrue(INLINE_REPLY_WAIT_MILLIS >= OUTBOX_SLOW_MILLIS)            // an ordinary send still ends inside it
        assertFalse(inlineReplyWaitIsSafe(SEND_READ_TIMEOUT_SECONDS * 1000))  // what the receiver waited before: the whole send
        assertFalse(inlineReplyWaitIsSafe(0))
    }

    // ---- the send path state machine ----

    private data class Row(val state: SendPathState, val via: String?, val next: SendPathState, val notice: SendPathNotice)

    private val fresh = SendPathState()
    private val seen = SendPathState(seenBlueBubbles = true)
    private val down = SendPathState(seenBlueBubbles = true, fallback = true)

    @Test fun sendPath_table() {
        val rows = listOf(
            // A fresh session: nothing is known about the relay's engines.
            Row(fresh, "bb", seen, SendPathNotice.NONE),
            Row(fresh, "applescript", fresh, SendPathNotice.NONE),          // may be a relay with no BlueBubbles: say nothing
            Row(fresh, "gmessages", fresh, SendPathNotice.NONE),
            Row(fresh, null, fresh, SendPathNotice.NONE),
            // BlueBubbles has delivered in this session.
            Row(seen, "bb", seen, SendPathNotice.NONE),
            Row(seen, "applescript", down, SendPathNotice.FALLBACK),        // the owner's "down" toast
            Row(seen, "gmessages", seen, SendPathNotice.NONE),
            Row(seen, null, seen, SendPathNotice.NONE),
            // The "down" notice is up.
            Row(down, "applescript", down, SendPathNotice.NONE),            // said once, not on every send
            Row(down, "bb", seen, SendPathNotice.RESTORED),                 // the owner's "restored" toast
            Row(down, "gmessages", down, SendPathNotice.NONE),
            Row(down, null, down, SendPathNotice.NONE),
            // Values the relay does not send change nothing.
            Row(seen, "", seen, SendPathNotice.NONE),
            Row(seen, "BB", seen, SendPathNotice.NONE),
            Row(seen, "AppleScript", seen, SendPathNotice.NONE),
        )
        for (r in rows) {
            assertEquals("$r", SendPathStep(r.next, r.notice), sendPathAfter(r.state, r.via))
        }
    }

    private fun notices(vararg vias: String?): List<SendPathNotice> {
        var state = SendPathState()
        return vias.map { via -> sendPathAfter(state, via).also { state = it.state }.notice }
    }

    @Test fun aRelayWithNoBlueBubbles_neverSaysDown_andNeverShowsFallbackMode() {
        var state = SendPathState()
        repeat(5) {
            val step = sendPathAfter(state, "applescript")
            assertEquals(SendPathNotice.NONE, step.notice)
            assertFalse(step.state.fallback)
            state = step.state
        }
    }

    @Test fun theOwnersSequence_downThenRestored_isSaidOnceEach() {
        assertEquals(
            listOf(
                SendPathNotice.NONE,        // bb
                SendPathNotice.FALLBACK,    // applescript: BlueBubbles went down
                SendPathNotice.NONE,        // applescript again
                SendPathNotice.NONE,        // a failed send in between
                SendPathNotice.RESTORED,    // bb: back
                SendPathNotice.NONE,        // bb
                SendPathNotice.FALLBACK,    // applescript: down again
            ),
            notices("bb", "applescript", "applescript", null, "bb", "bb", "applescript"),
        )
    }

    @Test fun restored_isNeverSaidWithoutADownNotice() {
        // The session starts on AppleScript (down already, or no BlueBubbles): no "down", so no "restored".
        assertEquals(
            listOf(SendPathNotice.NONE, SendPathNotice.NONE, SendPathNotice.NONE, SendPathNotice.FALLBACK),
            notices("applescript", "applescript", "bb", "applescript"),
        )
    }

    // ---- what is remembered of the relay across restarts ----

    private val known = SendPathState(seenBlueBubbles = true)

    @Test fun seeding_addsOnlyWhatIsRemembered() {
        assertEquals(known, sendPathSeeded(SendPathState(), relayHasBlueBubbles = true))
        assertEquals(SendPathState(), sendPathSeeded(SendPathState(), relayHasBlueBubbles = false))
        // What this session already saw is never taken away, and the notice that is up stays up.
        assertEquals(known, sendPathSeeded(known, relayHasBlueBubbles = false))
        assertEquals(down, sendPathSeeded(down, relayHasBlueBubbles = true))
        assertEquals(down, sendPathSeeded(down, relayHasBlueBubbles = false))
    }

    @Test fun sendPath_table_seeded() {
        // A fresh start of the app, on a relay that was seen to deliver through BlueBubbles before.
        val seeded = sendPathSeeded(SendPathState(), relayHasBlueBubbles = true)
        val rows = listOf(
            Row(seeded, "applescript", down, SendPathNotice.FALLBACK),      // BlueBubbles was down already: said on the first send
            Row(seeded, "bb", seen, SendPathNotice.NONE),
            Row(seeded, "gmessages", seeded, SendPathNotice.NONE),
            Row(seeded, null, seeded, SendPathNotice.NONE),
        )
        for (r in rows) assertEquals("$r", SendPathStep(r.next, r.notice), sendPathAfter(r.state, r.via))
    }

    @Test fun theOwnersColdStart_withBlueBubblesDown_isSaid_andRestoredFollows() {
        var state = sendPathSeeded(SendPathState(), relayHasBlueBubbles = true)
        val said = listOf("applescript", "applescript", "bb", "bb").map { via ->
            sendPathAfter(state, via).also { state = it.state }.notice
        }
        assertEquals(listOf(SendPathNotice.FALLBACK, SendPathNotice.NONE, SendPathNotice.RESTORED, SendPathNotice.NONE), said)
    }

    @Test fun aRelayNeverSeenOnBlueBubbles_staysSilent_acrossRestartsToo() {
        // Every start seeds from what was remembered; nothing ever is, because no send went through BlueBubbles.
        repeat(3) {
            var state = sendPathSeeded(SendPathState(), relayHasBlueBubbles = blueBubblesKnownFor(null, "https://relay.example.test"))
            repeat(3) {
                val step = sendPathAfter(state, "applescript")
                assertEquals(SendPathNotice.NONE, step.notice)
                assertFalse(step.state.fallback)
                state = step.state
            }
        }
    }

    @Test fun whatIsRemembered_belongsToOneRelay() {
        val base = "https://relay.example.test"
        val mark = relayMark(base)
        assertTrue(blueBubblesKnownFor(mark, base))
        assertFalse(blueBubblesKnownFor(mark, "https://other.example.test"))     // another relay is never taken for it
        assertFalse(blueBubblesKnownFor(mark, "https://relay.example.test:8443"))
        assertFalse(blueBubblesKnownFor(null, base))
        assertFalse(blueBubblesKnownFor("", base))
        assertFalse(blueBubblesKnownFor(relayMark(""), ""))                      // no relay at all
        assertFalse(blueBubblesKnownFor(mark, ""))
    }

    @Test fun theMark_isADigest_notTheAddress() {
        val mark = relayMark("https://relay.example.test")
        assertEquals(64, mark.length)
        assertTrue(mark.all { it in "0123456789abcdef" })
        assertFalse(mark.contains("relay"))
        assertFalse(mark.contains("example"))
        assertEquals(mark, relayMark("https://relay.example.test"))
        assertNotEquals(mark, relayMark("https://relay.example.test/"))
        assertEquals("", relayMark(""))
    }

    @Test fun viaConstants_areTheRelaysSpelling() {
        assertEquals("bb", SEND_VIA_BLUEBUBBLES)
        assertEquals("applescript", SEND_VIA_APPLESCRIPT)
    }
}
