package io.github.avtraang.selfbubbles

import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Edit and Undo Send (EditUnsend.kt, and the two requests in Imsg.kt): one tap
 * is one POST; only the relay's own `{"ok": true}` is a change; a refusal is
 * called one only when nothing can have changed, and anything else says to
 * check the chat; the two entries show only on the owner's own recent iMessage;
 * the composer's edit mode never loses what was typed, before it or in it; and
 * a relay that cannot do one of them is not asked again this session.
 */
class EditUnsendTest {

    private val chat = "iMessage;-;+15555550100"
    private val group = "iMessage;+;chat000000000000000001"
    private val guid = "MADE-UP-0001"

    /** A moment to count from; messages are dated in Unix seconds before it. */
    private val now = 1_800_000_000_000L

    private fun thread(
        guid: String = chat,
        network: String? = null,
        service: String? = null,
        isGroup: Boolean = false,
    ) = Thread(chat_guid = guid, last_rowid = 1L, network = network, service = service, is_group = isGroup)

    private fun msg(
        guid: String = this.guid,
        text: String? = "see you at six",
        ageSeconds: Double? = 30.0,
        fromMe: Boolean = true,
        chatGuid: String = chat,
        network: String? = null,
        service: String? = "iMessage",
        attachments: List<Att> = emptyList(),
        hasAttachments: Boolean = false,
        link: LinkPreview? = null,
        assocType: Int? = null,
        isGroup: Boolean = false,
        dateEdited: Double? = null,
    ) = Msg(
        rowid = 7L, guid = guid, text = text, date = ageSeconds?.let { now / 1000.0 - it },
        date_edited = dateEdited, is_from_me = fromMe, chat_guid = chatGuid, is_group = isGroup,
        has_attachments = hasAttachments, assoc_type = assocType, attachments = attachments,
        link = link, network = network, service = service,
    )

    private val photo = Att(guid = "ATT-MADE-UP-1", mime_type = "image/jpeg", name = "photo.jpg", url = "/attachment/ATT-MADE-UP-1")

    private fun done(unchanged: Boolean = false): ChangeOutcome = ChangeOutcome.Done(unchanged)
    private fun refused(reason: ChangeRefusal): ChangeOutcome = ChangeOutcome.Refused(reason)
    private fun unconfirmed(reason: ChangeDoubt): ChangeOutcome = ChangeOutcome.Unconfirmed(reason)

    // ---- one tap, one POST ----

    @Test fun anUnsend_isOnePostOfTheContractsJson_writtenAtMostOnce() {
        val req = unsendRequest("https://relay.example.test", chat, guid)
        assertEquals("POST", req.method)
        assertEquals("https://relay.example.test/unsend", req.url.toString())
        val body = req.body!!
        assertTrue(body.isOneShot())                 // OkHttp never repeats it: not after a reset, a 408, a 503 or a 307
        assertEquals("application/json", body.contentType()!!.toString().substringBefore(';'))
        val sent = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals("""{"chat_guid":"$chat","guid":"$guid","part_index":0}""", sent)
        assertEquals(sent.toByteArray().size.toLong(), body.contentLength())
    }

    @Test fun anEdit_isOnePostOfTheContractsJson_writtenAtMostOnce() {
        val req = editRequest("https://relay.example.test", chat, guid, "see you at seven — \"sharp\"")
        assertEquals("POST", req.method)
        assertEquals("https://relay.example.test/edit", req.url.toString())
        val body = req.body!!
        assertTrue(body.isOneShot())
        val sent = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals("""{"chat_guid":"$chat","guid":"$guid","text":"see you at seven — \"sharp\"","part_index":0}""", sent)
    }

    /**
     * A stand-in for the relay on the loopback interface. Each request is read
     * whole and counted by its path; [answer] gives the raw reply for a path, or
     * null to drop the connection without a word (what the phone sees when the
     * link dies after the request has left).
     */
    private class StubRelay(private val answer: (String) -> String?) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val posts = AtomicInteger()
        private val acceptor = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
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
                val (method, path) = lines.first().split(' ')
                if (method == "POST") posts.incrementAndGet()
                val reply = answer(path) ?: return@use
                out.write(reply.toByteArray())
                out.flush()
            }
        }

        override fun close() { server.close(); acceptor.interrupt() }

        companion object {
            fun http(status: String, contentType: String, body: String): String =
                "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
        }
    }

    /** One request on the app's send client, so the POST after it rides a pooled connection as it does on the phone. */
    private fun warm(relay: StubRelay) {
        httpSend.newCall(Request.Builder().url("${relay.base}/warm").build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
    }

    @Test fun theRelaysOk_isDone_overTheSendClient() {
        StubRelay { path ->
            when (path) {
                "/unsend" -> StubRelay.http("200 OK", "application/json", """{"ok":true,"via":"bb"}""")
                "/edit" -> StubRelay.http("200 OK", "application/json", """{"ok":true,"via":null,"unchanged":true}""")
                else -> StubRelay.http("200 OK", "application/json", """{"ok":true}""")
            }
        }.use { relay ->
            assertEquals(done(), postChange(httpSend) { unsendRequest(relay.base, chat, guid) })
            assertEquals(done(unchanged = true), postChange(httpSend) { editRequest(relay.base, chat, guid, "same words") })
            assertEquals(2, relay.posts.get())
        }
    }

    @Test fun aWebPageInTheRelaysPlace_isNeverDone() {
        // Cloudflare Access answers a wrong or expired service token with its login page; after the redirect it is a 200.
        val login = "<!DOCTYPE html><html><head><title>Sign in</title></head><body>…</body></html>"
        StubRelay { StubRelay.http("200 OK", "text/html; charset=UTF-8", login) }.use { relay ->
            assertEquals(refused(ChangeRefusal.AUTH), postChange(httpSend) { unsendRequest(relay.base, chat, guid) })
            assertEquals(refused(ChangeRefusal.AUTH), postChange(httpSend) { editRequest(relay.base, chat, guid, "new words") })
        }
    }

    @Test fun theRelaysRefusal_isReadFromItsDetail() {
        StubRelay {
            StubRelay.http("409 Conflict", "application/json", """{"detail":"too late to unsend (Apple allows 2 minutes)"}""")
        }.use { relay ->
            assertEquals(refused(ChangeRefusal.TOO_LATE), postChange(httpSend) { unsendRequest(relay.base, chat, guid) })
        }
    }

    @Test fun aChangeWhoseConnectionDiesAfterItLeft_isNotSentASecondTime_andIsNotCalledFailed() {
        StubRelay { path -> if (path == "/warm") StubRelay.http("200 OK", "application/json", """{"ok":true}""") else null }.use { relay ->
            warm(relay)
            val unsend = postChange(httpSend) { unsendRequest(relay.base, chat, guid) }
            assertTrue("$unsend", unsend is ChangeOutcome.Unconfirmed)
            assertEquals(1, relay.posts.get())                       // ONE request reached the relay
            warm(relay)
            val edit = postChange(httpSend) { editRequest(relay.base, chat, guid, "new words") }
            assertTrue("$edit", edit is ChangeOutcome.Unconfirmed)
            assertEquals(2, relay.posts.get())
            assertEquals(CHANGE_UNCONFIRMED_MESSAGE, changeFailureMessage(ChangeAction.EDIT, edit))
        }
    }

    @Test fun aRequestThatNeverLeft_isRefusedAsUnreachable() {
        // Nothing listens on a port that was just closed.
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val nothing = postChange(httpSend) { unsendRequest("http://127.0.0.1:$port", chat, guid) }
        assertEquals(refused(ChangeRefusal.UNREACHABLE), nothing)
        // No relay configured: the address is "", which no request can be built from. An outcome, not a crash.
        assertEquals(refused(ChangeRefusal.UNREACHABLE), postChange(httpSend) { editRequest("", chat, guid, "new words") })
    }

    // ---- what the relay answered ----

    private val jsonType = "application/json"
    private val page = "<!DOCTYPE html><html><body>Sign in</body></html>"

    @Test fun replies_table() {
        data class Row(val code: Int, val type: String?, val body: String, val expected: ChangeOutcome)
        val rows = listOf(
            // The relay's own success, and nothing else, is a change.
            Row(200, jsonType, """{"ok":true,"via":"bb"}""", done()),
            Row(200, jsonType, """{"ok":true,"via":"imessage-cli"}""", done()),
            Row(200, jsonType, """{"ok":true,"via":null,"unchanged":true}""", done(unchanged = true)),
            Row(200, jsonType, """{"ok":true,"unchanged":false}""", done()),
            Row(201, null, """  {"ok":true}  """, done()),
            Row(200, "text/html; charset=UTF-8", page, refused(ChangeRefusal.AUTH)),          // Cloudflare's login page
            Row(200, null, page, refused(ChangeRefusal.AUTH)),
            Row(200, "text/html", """{"ok":true}""", refused(ChangeRefusal.AUTH)),            // a page, whatever it holds
            Row(200, jsonType, "OK", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(200, jsonType, "", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(200, jsonType, "[1,2]", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(200, jsonType, "true", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(200, jsonType, """{"ok":false}""", unconfirmed(ChangeDoubt.NO_ANSWER)),           // a 2xx that confirms nothing
            Row(200, jsonType, "{}", unconfirmed(ChangeDoubt.NO_ANSWER)),
            Row(202, jsonType, """{"status":"queued"}""", unconfirmed(ChangeDoubt.NO_ANSWER)),
            // The token, or Cloudflare Access.
            Row(302, null, "", refused(ChangeRefusal.AUTH)),
            Row(307, null, "", refused(ChangeRefusal.AUTH)),
            Row(401, jsonType, """{"detail":"unauthorized"}""", refused(ChangeRefusal.AUTH)),
            Row(401, "text/html", page, refused(ChangeRefusal.AUTH)),
            Row(403, "text/html", page, refused(ChangeRefusal.AUTH)),
            Row(403, "text/plain", "error code: 1020", refused(ChangeRefusal.AUTH)),
            // The relay's own refusals.
            Row(403, jsonType, """{"detail":"not your message"}""", refused(ChangeRefusal.NOT_YOURS)),
            Row(404, jsonType, """{"detail":"unknown message"}""", refused(ChangeRefusal.UNKNOWN_MESSAGE)),
            Row(404, jsonType, """{"detail":"Unknown message MADE-UP-0001"}""", refused(ChangeRefusal.UNKNOWN_MESSAGE)),
            Row(404, jsonType, """{"detail":"no such chat"}""", refused(ChangeRefusal.UNKNOWN_MESSAGE)),
            Row(404, jsonType, """{"detail":"Not Found"}""", refused(ChangeRefusal.RELAY_TOO_OLD)),    // FastAPI, for a route it lacks
            Row(404, jsonType, """{"detail":" not found "}""", refused(ChangeRefusal.RELAY_TOO_OLD)),
            Row(404, jsonType, "{}", refused(ChangeRefusal.UNKNOWN_MESSAGE)),
            Row(404, "text/plain", "Not Found", refused(ChangeRefusal.NOT_A_RELAY)),            // a proxy's, not the relay's JSON
            Row(404, "text/plain", "unknown message", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(404, "text/plain", "404 page not found", refused(ChangeRefusal.NOT_A_RELAY)),
            Row(404, "text/html", page, refused(ChangeRefusal.NOT_A_RELAY)),
            Row(405, jsonType, """{"detail":"Method Not Allowed"}""", refused(ChangeRefusal.RELAY_TOO_OLD)),
            Row(405, "text/html", page, refused(ChangeRefusal.NOT_A_RELAY)),
            Row(405, "text/plain", "Method Not Allowed", refused(ChangeRefusal.NOT_A_RELAY)),   // a proxy's, not the relay's JSON
            Row(409, jsonType, """{"detail":"too late to unsend (Apple allows 2 minutes)"}""", refused(ChangeRefusal.TOO_LATE)),
            Row(409, jsonType, """{"detail":"too late to edit (Apple allows 15 minutes)"}""", refused(ChangeRefusal.TOO_LATE)),
            Row(409, jsonType, """{"detail":"Too Late to edit"}""", refused(ChangeRefusal.TOO_LATE)),
            Row(409, jsonType, """{"detail":"already unsent"}""", refused(ChangeRefusal.ALREADY_UNSENT)),
            Row(409, jsonType, """{"detail":"this message has been edited 5 times already"}""", refused(ChangeRefusal.EDIT_LIMIT)),
            Row(409, jsonType, """{"detail":"something the app has no words for"}""", refused(ChangeRefusal.CONFLICT)),
            Row(409, jsonType, "{}", refused(ChangeRefusal.CONFLICT)),
            Row(409, jsonType, """{"detail":[{"msg":"too late"}]}""", refused(ChangeRefusal.CONFLICT)),   // not text: not read
            Row(422, jsonType, """{"detail":[{"loc":["body","guid"],"msg":"Field required"}]}""", refused(ChangeRefusal.BAD_REQUEST)),
            Row(400, jsonType, """{"detail":"bad"}""", refused(ChangeRefusal.BAD_REQUEST)),
            Row(413, jsonType, "{}", refused(ChangeRefusal.BAD_REQUEST)),
            Row(429, jsonType, "{}", refused(ChangeRefusal.BAD_REQUEST)),
            Row(501, jsonType, """{"detail":"no configured engine can unsend"}""", refused(ChangeRefusal.NOT_SUPPORTED)),
            Row(501, jsonType, """{"detail":"Google Messages chats cannot be edited"}""", refused(ChangeRefusal.NOT_SUPPORTED)),
            // Certain only when the relay says it watched the Mac's database and saw no change.
            Row(502, jsonType, """{"detail":"the Mac did not apply the change"}""", refused(ChangeRefusal.MAC_DID_NOT_APPLY)),
            Row(502, jsonType, """{"detail":"The Mac did not apply the edit"}""", refused(ChangeRefusal.MAC_DID_NOT_APPLY)),
            // Its 502 for an engine that failed or timed out says nothing of the database: the change may still land.
            Row(502, jsonType, """{"detail":"engine failed"}""", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(502, jsonType, """{"detail":"BlueBubbles timed out"}""", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(502, jsonType, "{}", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(502, jsonType, """{"detail":[{"msg":"did not apply"}]}""", unconfirmed(ChangeDoubt.SERVER_ERROR)),   // not text: not read
            // No answer, or the route answering for a relay it could not hear: the change may have happened.
            Row(502, "text/html", page, unconfirmed(ChangeDoubt.SERVER_ERROR)),               // the tunnel's Bad Gateway page
            Row(502, "text/plain", "error code: 502", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(502, "text/plain", "the Mac did not apply the change", unconfirmed(ChangeDoubt.SERVER_ERROR)),   // not the relay's JSON
            Row(501, "text/html", page, unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(501, "text/plain", "Not Implemented", unconfirmed(ChangeDoubt.SERVER_ERROR)),  // a proxy's, not the relay's JSON
            Row(500, jsonType, """{"detail":"Internal Server Error"}""", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(500, "text/plain", "Internal Server Error", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(503, jsonType, "{}", unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(520, "text/html", page, unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(530, "text/html", page, unconfirmed(ChangeDoubt.SERVER_ERROR)),
            Row(408, jsonType, "{}", unconfirmed(ChangeDoubt.NO_ANSWER)),
            Row(504, "text/html", page, unconfirmed(ChangeDoubt.NO_ANSWER)),
            Row(522, "text/html", page, unconfirmed(ChangeDoubt.NO_ANSWER)),
            Row(524, "text/html", page, unconfirmed(ChangeDoubt.NO_ANSWER)),                  // the tunnel gave up after about 100 s
        )
        for (r in rows) assertEquals("${r.code} ${r.type} ${r.body.take(40)}", r.expected, changeOutcomeFor(r.code, r.type, r.body))
    }

    @Test fun everyStatus_hasAVerdict_andOnlyA2xxOkIsDone() {
        for (code in 100..599) for (body in listOf("", "{}", """{"ok":true}""", page, """{"detail":"x"}""")) {
            val outcome = changeOutcomeFor(code, jsonType, body)
            val isOk = code in 200..299 && body == """{"ok":true}"""
            assertEquals("$code $body", isOk, outcome is ChangeOutcome.Done)
        }
    }

    @Test fun failures_table() {
        val lostAfterSending = UnknownHostException("relay.example.test").apply { addSuppressed(SocketException("Connection reset")) }
        val rows: List<Pair<Throwable?, ChangeOutcome>> = listOf(
            // Before anything was written: certain.
            UnknownHostException("relay.example.test") to refused(ChangeRefusal.UNREACHABLE),
            ConnectException("refused") to refused(ChangeRefusal.UNREACHABLE),
            NoRouteToHostException("no route") to refused(ChangeRefusal.UNREACHABLE),
            SSLHandshakeException("untrusted") to refused(ChangeRefusal.UNREACHABLE),
            SSLPeerUnverifiedException("hostname") to refused(ChangeRefusal.UNREACHABLE),
            IllegalArgumentException("Expected URL scheme") to refused(ChangeRefusal.UNREACHABLE),   // no relay configured
            // The request left, or may have: never called a failure.
            SocketTimeoutException("timeout") to unconfirmed(ChangeDoubt.NO_ANSWER),
            SocketException("Connection reset") to unconfirmed(ChangeDoubt.CONNECTION_LOST),
            IOException("unexpected end of stream") to unconfirmed(ChangeDoubt.CONNECTION_LOST),
            lostAfterSending to unconfirmed(ChangeDoubt.CONNECTION_LOST),
            IllegalStateException("anything else") to unconfirmed(ChangeDoubt.NO_ANSWER),
            null to unconfirmed(ChangeDoubt.NO_ANSWER),
        )
        for ((error, expected) in rows) assertEquals("$error", expected, changeOutcomeFor(error))
    }

    // ---- what the owner is told ----

    @Test fun refusals_wording_table() {
        data class Row(val reason: ChangeRefusal, val unsend: String, val edit: String)
        val rows = listOf(
            Row(ChangeRefusal.TOO_LATE, "Too late to unsend — Apple allows 2 minutes", "Too late to edit — Apple allows 15 minutes"),
            Row(ChangeRefusal.ALREADY_UNSENT, "This message was already unsent", "This message was already unsent"),
            Row(ChangeRefusal.EDIT_LIMIT, "This message has been edited 5 times already", "This message has been edited 5 times already"),
            Row(ChangeRefusal.CONFLICT, "This message can't be unsent now", "This message can't be edited now"),
            Row(ChangeRefusal.UNKNOWN_MESSAGE, "The relay doesn't know this message", "The relay doesn't know this message"),
            Row(ChangeRefusal.RELAY_TOO_OLD, "Update the relay to edit or unsend", "Update the relay to edit or unsend"),
            Row(ChangeRefusal.NOT_YOURS, "Only your own messages can be unsent", "Only your own messages can be edited"),
            Row(ChangeRefusal.NOT_SUPPORTED, "This chat can't edit or unsend messages", "This chat can't edit or unsend messages"),
            Row(ChangeRefusal.BAD_REQUEST, "The relay refused the request", "The relay refused the request"),
            Row(ChangeRefusal.MAC_DID_NOT_APPLY, "The Mac did not apply the change", "The Mac did not apply the change"),
            Row(ChangeRefusal.AUTH, "The relay or Cloudflare Access rejected the request", "The relay or Cloudflare Access rejected the request"),
            Row(ChangeRefusal.NOT_A_RELAY, "The address did not answer like a relay", "The address did not answer like a relay"),
            Row(ChangeRefusal.UNREACHABLE, "Can't reach the relay", "Can't reach the relay"),
        )
        assertEquals("a row for every reason", ChangeRefusal.entries.toSet(), rows.map { it.reason }.toSet())
        for (r in rows) {
            assertEquals(r.unsend, changeRefusalMessage(ChangeAction.UNSEND, r.reason))
            assertEquals(r.edit, changeRefusalMessage(ChangeAction.EDIT, r.reason))
            assertEquals(r.unsend, changeFailureMessage(ChangeAction.UNSEND, refused(r.reason)))
            assertEquals(r.edit, changeFailureMessage(ChangeAction.EDIT, refused(r.reason)))
        }
    }

    @Test fun anUnconfirmedChange_isNeverCalledAFailure_andADoneOneSaysNothing() {
        assertEquals("No answer from the relay — check the chat", CHANGE_UNCONFIRMED_MESSAGE)
        for (action in ChangeAction.entries) {
            for (doubt in ChangeDoubt.entries) {
                val line = changeFailureMessage(action, unconfirmed(doubt))!!
                assertEquals(CHANGE_UNCONFIRMED_MESSAGE, line)
                for (word in listOf("fail", "couldn't", "could not", "not sent", "refused", "did not")) {
                    assertFalse("$line / $word", line.contains(word, ignoreCase = true))
                }
            }
            assertNull(changeFailureMessage(action, done()))
            assertNull(changeFailureMessage(action, done(unchanged = true)))
        }
    }

    @Test fun aLogLine_namesTheActionAndTheVerdict_only() {
        assertEquals("unsend refused (TOO_LATE)", changeLogLine(ChangeAction.UNSEND, refused(ChangeRefusal.TOO_LATE)))
        assertEquals("edit unconfirmed (NO_ANSWER)", changeLogLine(ChangeAction.EDIT, unconfirmed(ChangeDoubt.NO_ANSWER)))
        assertEquals("edit done", changeLogLine(ChangeAction.EDIT, done()))
        assertEquals("edit done (unchanged)", changeLogLine(ChangeAction.EDIT, done(unchanged = true)))
        // Whatever the verdict, the line is built from enum names: nothing of a message, a guid or a chat can be in it.
        val outcomes = ChangeRefusal.entries.map { refused(it) } + ChangeDoubt.entries.map { unconfirmed(it) }
        for (action in ChangeAction.entries) for (o in outcomes) {
            assertTrue(changeLogLine(action, o), Regex("^[a-z]+ [a-z]+ \\([A-Z_]+\\)$").matches(changeLogLine(action, o)))
        }
    }

    @Test fun theReadme_listsEveryLineTheOwnerMayRead() {
        // Unit tests run in the app module's directory; the README is one up.
        val readme = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "README.md") }.first { it.isFile && File(it.parentFile, "app").isDirectory }.readText()
        val lines = ChangeAction.entries.flatMap { a -> ChangeRefusal.entries.map { changeRefusalMessage(a, it) } } +
            CHANGE_UNCONFIRMED_MESSAGE
        for (line in lines.distinct()) assertTrue("README.md lacks: $line", readme.contains(line))
    }

    // ---- which messages are offered a change ----

    @Test fun windows_areApplesLessAMargin() {
        assertEquals(120, UNSEND_WINDOW_SECONDS)
        assertEquals(10, UNSEND_MARGIN_SECONDS)
        assertEquals(900, EDIT_WINDOW_SECONDS)
        assertEquals(20, EDIT_MARGIN_SECONDS)
    }

    @Test fun eligibility_byAge() {
        data class Row(val age: Double?, val unsend: Boolean, val edit: Boolean)
        val rows = listOf(
            Row(0.0, true, true),
            Row(30.0, true, true),
            Row(109.9, true, true),
            Row(110.0, false, true),          // two minutes less the ten-second margin
            Row(119.0, false, true),
            Row(120.0, false, true),
            Row(600.0, false, true),
            Row(879.9, false, true),
            Row(880.0, false, false),         // fifteen minutes less the twenty-second margin
            Row(900.0, false, false),
            Row(86_400.0, false, false),
            Row(-5.0, true, true),            // the Mac's clock a little ahead of the phone's: the relay decides
            Row(null, false, false),          // no date, no known age
        )
        for (r in rows) {
            val m = msg(ageSeconds = r.age)
            assertEquals("unsend at ${r.age}", r.unsend, canUnsend(m, thread(), now))
            assertEquals("edit at ${r.age}", r.edit, canEdit(m, thread(), now))
        }
    }

    @Test fun eligibility_byWhoseAndWhere() {
        data class Row(val what: String, val m: Msg, val t: Thread?, val unsend: Boolean, val edit: Boolean)
        val rows = listOf(
            Row("my recent iMessage", msg(), thread(), true, true),
            Row("the same in a chat opened as a bare stub", msg(), null, true, true),
            Row("in a group", msg(chatGuid = group, isGroup = true), thread(group, isGroup = true), true, true),
            Row("a relay that names no service", msg(service = null), thread(), true, true),
            Row("someone else's", msg(fromMe = false), thread(), false, false),
            Row("no guid to name it by", msg(guid = ""), thread(), false, false),
            Row("a Google Messages thread", msg(service = null, network = "gmessages"), thread(network = "gmessages"), false, false),
            Row("a Google Messages message", msg(service = null, network = "gmessages"), thread(), false, false),
            Row("a bridge guid", msg(guid = "bp:made-up-1", service = null), thread(), false, false),
            Row("a bridge chat", msg(chatGuid = "bp:!room:example.test", service = null), thread(), false, false),
            Row("a bridge thread", msg(service = null), thread("bp:!room:example.test"), false, false),
            Row("an SMS thread on the Mac", msg(), thread(service = "SMS"), false, false),
            Row("an RCS thread on the Mac", msg(), thread(service = "RCS"), false, false),
            Row("an SMS message", msg(service = "SMS"), thread(), false, false),
            Row("an RCS message", msg(service = "RCS"), thread(), false, false),
            Row("a tapback", msg(assocType = 2000), thread(), false, false),
        )
        for (r in rows) {
            assertEquals("unsend: ${r.what}", r.unsend, canUnsend(r.m, r.t, now))
            assertEquals("edit: ${r.what}", r.edit, canEdit(r.m, r.t, now))
        }
    }

    @Test fun eligibility_byWhatTheMessageIs() {
        val url = "https://example.test/articles/42"
        data class Row(val what: String, val m: Msg, val unsend: Boolean, val edit: Boolean)
        val second = photo.copy(guid = "ATT-MADE-UP-2", name = "second.jpg", url = "/attachment/ATT-MADE-UP-2")
        val rows = listOf(
            Row("plain text", msg(text = "see you at six"), true, true),
            Row("already unsent: nothing left", msg(text = null), false, false),
            Row("blank text", msg(text = "  \n"), false, false),
            // One attachment and no words is one part: it can be taken back whole, and has no text to edit.
            Row("a photo alone", msg(text = "\uFFFC", attachments = listOf(photo), hasAttachments = true), true, false),
            Row("a photo alone, no placeholder", msg(text = null, attachments = listOf(photo), hasAttachments = true), true, false),
            // More than one part: the request names part 0 only, so neither is offered.
            Row("a photo with words", msg(text = "\uFFFClook", attachments = listOf(photo), hasAttachments = true), false, false),
            Row("two photos", msg(text = "\uFFFC\uFFFC", attachments = listOf(photo, second), hasAttachments = true), false, false),
            // An unknown number of parts: a placeholder in the text with no attachment listed for it.
            Row("the flag alone, nothing to show", msg(text = null, hasAttachments = true), false, false),
            Row("a placeholder without its attachment", msg(text = "look \uFFFC"), false, false),
            Row("more placeholders than attachments", msg(text = "\uFFFC\uFFFC", attachments = listOf(photo)), false, false),
            // A link is one part; on its own it is a preview card, not text.
            Row("a link and nothing else", msg(text = url, link = LinkPreview(url = url, title = "Forty-two")), true, false),
            Row("the same link, stored with a query", msg(text = url, link = LinkPreview(url = "$url?ref=1")), true, false),
            Row("one URL whose preview has not arrived", msg(text = url), true, false),
            Row("words with a link among them", msg(text = "read this $url tonight", link = LinkPreview(url = url, title = "Forty-two")), true, true),
            Row("words and a link, no preview", msg(text = "read this $url"), true, true),
            // The Mac flags a link whose preview it stored as an attachment (the relay does not list it): one
            // part, so it can be taken back, but not edited from here.
            Row("a link with a stored preview", msg(text = url, hasAttachments = true, link = LinkPreview(url = url)), true, false),
            Row("words and a link with a stored preview", msg(text = "read this $url", hasAttachments = true, link = LinkPreview(url = url)), true, false),
            Row("the Mac's flag on plain words", msg(text = "look", hasAttachments = true), true, false),
        )
        for (r in rows) {
            assertEquals("unsend: ${r.what}", r.unsend, canUnsend(r.m, thread(), now))
            assertEquals("edit: ${r.what}", r.edit, canEdit(r.m, thread(), now))
        }
    }

    @Test fun theTextToEdit_isTheMessagesText_withoutTheSpaceAroundIt() {
        assertEquals("see you at six", editableText(msg(text = "  see you at six\n")))
        assertEquals("two\nlines", editableText(msg(text = "two\nlines")))
        assertNull(editableText(msg(text = null)))
        assertNull(editableText(msg(text = "")))
        assertNull(editableText(msg(text = "￼")))
    }

    @Test fun onePart_isWordsAlone_orOneAttachmentAlone() {
        assertTrue(isOnePart(msg(text = "see you at six")))
        assertTrue(isOnePart(msg(text = "\uFFFC", attachments = listOf(photo))))
        assertTrue(isOnePart(msg(text = " \uFFFC\n", attachments = listOf(photo), hasAttachments = true)))
        assertFalse(isOnePart(msg(text = null)))
        assertFalse(isOnePart(msg(text = "caption \uFFFC", attachments = listOf(photo))))
        assertFalse(isOnePart(msg(text = "caption", attachments = listOf(photo))))
        assertFalse(isOnePart(msg(text = null, attachments = listOf(photo, photo))))
        assertFalse(isOnePart(msg(text = null, hasAttachments = true)))
        assertFalse(isOnePart(msg(text = "look \uFFFC", hasAttachments = true)))
        assertTrue(isOnePart(msg(text = "https://example.test/a", hasAttachments = true)))     // a link's stored preview
    }

    @Test fun anEmptiedMessage_isOneWithNothingToShow() {
        assertTrue(isEmptied(msg(text = null)))
        assertTrue(isEmptied(msg(text = "")))
        assertTrue(isEmptied(msg(text = " ￼ ")))
        assertFalse(isEmptied(msg(text = "hi")))
        assertFalse(isEmptied(msg(text = null, attachments = listOf(photo))))
        assertTrue(isEmptied(msg(text = null, hasAttachments = true)))               // the flag draws nothing
        assertFalse(isEmptied(msg(text = null, link = LinkPreview(url = "https://example.test"))))
    }

    // ---- what the app remembers about the relay ----

    @Test fun chatKinds() {
        assertEquals(ChatKind.IMESSAGE, chatKindOf(msg(), thread()))
        assertEquals(ChatKind.IMESSAGE, chatKindOf(null, thread()))
        assertEquals(ChatKind.IMESSAGE, chatKindOf(msg(), null))
        assertEquals(ChatKind.IMESSAGE_GROUP, chatKindOf(msg(), thread(group, isGroup = true)))
        assertEquals(ChatKind.IMESSAGE_GROUP, chatKindOf(msg(isGroup = true), thread()))      // a stub thread does not know
        assertEquals(ChatKind.TEXT, chatKindOf(msg(), thread(network = "gmessages")))
        assertEquals(ChatKind.TEXT, chatKindOf(msg(), thread(service = "SMS", isGroup = true)))
        assertEquals(ChatKind.TEXT, chatKindOf(msg(service = "RCS"), thread()))
        assertEquals(ChatKind.TEXT, chatKindOf(msg(guid = "bp:made-up-1"), null))
        assertEquals(ChatKind.TEXT, chatKindOf(null, thread("bp:!room:example.test")))
    }

    @Test fun aRelayThatCannot_isRemembered_forThatActionAndKindOfChat_only() {
        val fresh = ChangeMemory()
        for (action in ChangeAction.entries) for (kind in ChatKind.entries) assertFalse(fresh.hides(action, kind))

        for (reason in listOf(ChangeRefusal.NOT_SUPPORTED, ChangeRefusal.RELAY_TOO_OLD)) {
            val after = changeMemoryAfter(fresh, ChangeAction.EDIT, ChatKind.IMESSAGE, refused(reason))
            assertTrue("$reason", after.hides(ChangeAction.EDIT, ChatKind.IMESSAGE))
            assertFalse(after.hides(ChangeAction.EDIT, ChatKind.IMESSAGE_GROUP))     // another kind of chat is asked once itself
            assertFalse(after.hides(ChangeAction.UNSEND, ChatKind.IMESSAGE))         // the other action has its own engine
            // Remembered once, however often it is said.
            assertEquals(after, changeMemoryAfter(after, ChangeAction.EDIT, ChatKind.IMESSAGE, refused(reason)))
        }
    }

    @Test fun nothingElse_isRemembered() {
        val fresh = ChangeMemory()
        val others = ChangeRefusal.entries.filter { it != ChangeRefusal.NOT_SUPPORTED && it != ChangeRefusal.RELAY_TOO_OLD }.map { refused(it) } +
            ChangeDoubt.entries.map { unconfirmed(it) } + done() + done(unchanged = true)
        for (o in others) {
            assertFalse("$o", hidesFromNowOn(o))
            assertSame(fresh, changeMemoryAfter(fresh, ChangeAction.UNSEND, ChatKind.IMESSAGE, o))
        }
        assertTrue(hidesFromNowOn(refused(ChangeRefusal.NOT_SUPPORTED)))
        assertTrue(hidesFromNowOn(refused(ChangeRefusal.RELAY_TOO_OLD)))
    }

    @Test fun theRelaysCapabilities_seedTheMemory() {
        // A list is the relay's word: what it does not name is not offered, in any kind of chat.
        val both = changeMemoryReported(listOf("edit", "unsend"))
        for (action in ChangeAction.entries) for (kind in ChatKind.entries) assertFalse(both.hides(action, kind))
        val unsendOnly = changeMemoryReported(listOf("unsend", "something-newer"))
        for (kind in ChatKind.entries) {
            assertTrue(unsendOnly.hides(ChangeAction.EDIT, kind))
            assertFalse(unsendOnly.hides(ChangeAction.UNSEND, kind))
        }
        val neither = changeMemoryReported(emptyList())
        for (action in ChangeAction.entries) for (kind in ChatKind.entries) assertTrue(neither.hides(action, kind))
        assertEquals(ChangeMemory(), changeMemoryReported(listOf(" Edit ", "UNSEND")))
        // A relay that sends no list says nothing: everything is offered, and the first try tells.
        assertEquals(ChangeMemory(), changeMemoryReported(null))
    }

    @Test fun anAnswerFromTheRelaysHealth_startsTheMemoryOver() {
        // "Update the relay" was said for Edit; the relay was updated, and Test connection (or a Save) reached it.
        val learned = changeMemoryAfter(ChangeMemory(), ChangeAction.EDIT, ChatKind.IMESSAGE, refused(ChangeRefusal.RELAY_TOO_OLD))
            .let { changeMemoryAfter(it, ChangeAction.UNSEND, ChatKind.IMESSAGE_GROUP, refused(ChangeRefusal.NOT_SUPPORTED)) }
        assertTrue(learned.hides(ChangeAction.EDIT, ChatKind.IMESSAGE))
        // What was learned is not carried into the new memory, whether or not the relay sends a list.
        for (reported in listOf(null, listOf("edit", "unsend"))) {
            val after = changeMemoryReported(reported)
            for (action in ChangeAction.entries) for (kind in ChatKind.entries) assertFalse("$reported", after.hides(action, kind))
        }
        val editOnly = changeMemoryReported(listOf("edit"))
        assertFalse(editOnly.hides(ChangeAction.EDIT, ChatKind.IMESSAGE))
        assertTrue(editOnly.hides(ChangeAction.UNSEND, ChatKind.IMESSAGE))
        // And a refusal after it is learned again.
        val again = changeMemoryAfter(editOnly, ChangeAction.EDIT, ChatKind.IMESSAGE, refused(ChangeRefusal.NOT_SUPPORTED))
        assertTrue(again.hides(ChangeAction.EDIT, ChatKind.IMESSAGE))
    }

    @Test fun whatAnotherRelayCouldNotDo_saysNothingAboutThisOne() {
        val learned = changeMemoryAfter(ChangeMemory(), ChangeAction.EDIT, ChatKind.IMESSAGE, refused(ChangeRefusal.NOT_SUPPORTED))
        assertSame(learned, changeMemoryFor("https://relay.example.test", learned, "https://relay.example.test"))
        assertEquals(ChangeMemory(), changeMemoryFor("https://relay.example.test", learned, "https://other.example.test"))
        assertEquals(ChangeMemory(), changeMemoryFor("https://relay.example.test", learned, ""))
    }

    // ---- the long-press panel ----

    @Test fun thePanel_showsWhatIsEligible_andNothingForAnyoneElsesMessage() {
        val memory = ChangeMemory()
        assertEquals(PanelChanges(edit = true, unsend = true), panelChangesFor(msg(), thread(), now, memory))
        assertEquals(PanelChanges(edit = true, unsend = false), panelChangesFor(msg(ageSeconds = 300.0), thread(), now, memory))
        assertEquals(PanelChanges(edit = false, unsend = true), panelChangesFor(msg(text = "￼", attachments = listOf(photo)), thread(), now, memory))
        // Exactly as today: nothing added.
        val none = PanelChanges(edit = false, unsend = false)
        assertEquals(none, panelChangesFor(msg(fromMe = false), thread(), now, memory))
        assertEquals(none, panelChangesFor(msg(ageSeconds = 3_600.0), thread(), now, memory))
        assertEquals(none, panelChangesFor(msg(service = null, network = "gmessages"), thread(network = "gmessages"), now, memory))
        assertEquals(none, panelChangesFor(msg(), thread(service = "SMS"), now, memory))
    }

    @Test fun thePanel_followsWhatTheRelaySaidItCannotDo() {
        val noEdit = changeMemoryAfter(ChangeMemory(), ChangeAction.EDIT, ChatKind.IMESSAGE, refused(ChangeRefusal.NOT_SUPPORTED))
        assertEquals(PanelChanges(edit = false, unsend = true), panelChangesFor(msg(), thread(), now, noEdit))
        // Learned in a one-to-one chat; a group is another kind and is still offered it.
        val inGroup = panelChangesFor(msg(chatGuid = group, isGroup = true), thread(group, isGroup = true), now, noEdit)
        assertEquals(PanelChanges(edit = true, unsend = true), inGroup)
        val noUnsend = changeMemoryReported(listOf("edit"))
        assertEquals(PanelChanges(edit = true, unsend = false), panelChangesFor(msg(), thread(), now, noUnsend))
        assertEquals(PanelChanges(edit = false, unsend = false), panelChangesFor(msg(), thread(), now, changeMemoryReported(emptyList())))
    }

    @Test fun thePanel_offersNothingTwice() {
        val memory = ChangeMemory()
        // This message's unsend is on its way.
        assertEquals(PanelChanges(false, false), panelChangesFor(msg(), thread(), now, memory, unsending = setOf(guid)))
        assertEquals(PanelChanges(true, true), panelChangesFor(msg(), thread(), now, memory, unsending = setOf("ANOTHER-0002")))
        // The composer is already editing (this message or another): one edit at a time, Undo Send still there.
        val editing = EditMode(1, chat, "ANOTHER-0002", original = "a", text = "ab")
        assertEquals(PanelChanges(edit = false, unsend = true), panelChangesFor(msg(), thread(), now, memory, editing = editing))
        assertEquals(PanelChanges(edit = false, unsend = true), panelChangesFor(msg(), thread(), now, memory, editing = editing.copy(guid = guid)))
        // The edit of this very message is on its way: no second change to it meanwhile.
        val saving = editing.copy(guid = guid, sending = true)
        assertEquals(PanelChanges(edit = false, unsend = false), panelChangesFor(msg(), thread(), now, memory, editing = saving))
        assertEquals(PanelChanges(edit = false, unsend = true), panelChangesFor(msg(), thread(), now, memory, editing = editing.copy(sending = true)))
        // The same edit after its chat was left and opened again: the edit mode is gone, the request is not.
        assertEquals(PanelChanges(false, false), panelChangesFor(msg(), thread(), now, memory, saving = setOf(guid)))
        assertEquals(PanelChanges(true, true), panelChangesFor(msg(), thread(), now, memory, saving = setOf("ANOTHER-0002")))
    }

    // ---- Undo Send ----

    @Test fun aSecondTapOnUndoSend_isNoSecondRequest() {
        val one = unsendStarted(emptySet(), guid)
        assertEquals(setOf(guid), one)
        assertNull(unsendStarted(one!!, guid))                               // already on its way: ignored
        assertEquals(setOf(guid, "ANOTHER-0002"), unsendStarted(one, "ANOTHER-0002"))
        assertEquals(emptySet<String>(), unsendEnded(one, guid))
        assertEquals(one, unsendEnded(one, "NEVER-STARTED"))
        // Once it has ended, the message may be tried again.
        assertEquals(setOf(guid), unsendStarted(unsendEnded(one, guid), guid))
    }

    // ---- after a change ----

    @Test fun theChatIsLookedAtAgain_onlyWhereThereIsSomethingToLookFor() {
        assertEquals(listOf(3_000L), changeRecheckDelaysMillis(done()))                 // one look, three seconds on
        assertEquals(emptyList<Long>(), changeRecheckDelaysMillis(done(unchanged = true)))
        // "The Mac did not apply the change" speaks of the seconds the relay watched for: one look, in case it landed just after.
        assertEquals(listOf(3_000L), changeRecheckDelaysMillis(refused(ChangeRefusal.MAC_DID_NOT_APPLY)))
        for (reason in ChangeRefusal.entries - ChangeRefusal.MAC_DID_NOT_APPLY) {
            assertEquals("$reason", emptyList<Long>(), changeRecheckDelaysMillis(refused(reason)))
        }
        for (doubt in ChangeDoubt.entries) {
            val waits = changeRecheckDelaysMillis(unconfirmed(doubt))
            assertEquals(listOf(3_000L, 7_000L, 20_000L), waits)                        // at 3 s, 10 s and 30 s
            assertEquals(30_000L, waits.sum())
        }
    }

    @Test fun aChangeIsPending_untilTheHeldMessageShowsIt() {
        val before = msg(text = "see you at six")
        val other = msg(guid = "ANOTHER-0002", text = "unrelated")
        // Undo Send: pending while the bubble still has something to show.
        assertTrue(changeStillPending(ChangeAction.UNSEND, before, listOf(other, before)))
        assertFalse(changeStillPending(ChangeAction.UNSEND, before, listOf(other, before.copy(text = null))))
        // Edit: pending while the text and the edit date are the old ones.
        assertTrue(changeStillPending(ChangeAction.EDIT, before, listOf(before, other)))
        assertTrue(changeStillPending(ChangeAction.EDIT, before, listOf(before.copy(date_read = 1.0))))
        assertFalse(changeStillPending(ChangeAction.EDIT, before, listOf(before.copy(text = "see you at seven", date_edited = 5.0))))
        assertFalse(changeStillPending(ChangeAction.EDIT, before, listOf(before.copy(date_edited = 5.0))))
        // Not held (the chat was reloaded without it): nothing to wait for.
        assertFalse(changeStillPending(ChangeAction.UNSEND, before, listOf(other)))
        assertFalse(changeStillPending(ChangeAction.EDIT, before, emptyList()))
    }

    @Test fun theSecondLook_replacesThatOneMessage_andTouchesNothingElse() {
        val a = msg(guid = "A-0001", text = "first")
        val b = msg(guid = "B-0002", text = "second")
        val c = msg(guid = "C-0003", text = "third")
        val held = listOf(a, b, c)
        val unsentB = b.copy(text = null)
        val newer = msg(guid = "D-0004", text = "arrived meanwhile")
        // The page carries a changed A as well, and a message not held: only B is taken.
        val page = listOf(a.copy(text = "changed elsewhere"), unsentB, c, newer)
        assertEquals(listOf(a, unsentB, c), withMessageFrom(held, page, "B-0002"))
        // The page lacks it, or holds the same: the held list itself.
        assertSame(held, withMessageFrom(held, listOf(a, c), "B-0002"))
        assertSame(held, withMessageFrom(held, listOf(a, b, c), "B-0002"))
        assertSame(held, withMessageFrom(held, page, "NOT-HELD"))
    }

    // ---- the composer's edit mode ----

    private val target = msg(guid = guid, text = "see you at six")
    private val replyTo = msg(guid = "THEIRS-0009", text = "when?", fromMe = false)

    private fun entered(draft: String = "", reply: Msg? = null, seq: Int = 1): EditMode =
        editEnter(null, seq, target, chat, draft, reply).mode!!

    @Test fun enter_stashesTheDraftAndTheReply_andShowsTheMessagesText() {
        val step = editEnter(null, 4, target, chat, draft = "half a thought", reply = replyTo)
        val expected = EditMode(
            seq = 4, chatGuid = chat, guid = guid, original = "see you at six", text = "see you at six",
            stashedDraft = "half a thought", stashedReply = replyTo, sending = false,
        )
        assertEquals(expected, step.mode)
        assertEquals("see you at six", step.field)          // the field shows the message, cursor at the end
        assertTrue(step.setsReply)
        assertNull(step.reply)                              // the reply banner gives way to the edit banner
        assertNull(step.request)
        assertNull(step.toast)
        assertTrue(expected.canSubmit)
    }

    @Test fun enter_isNothing_forAMessageWithNoTextToEdit_andWhileAlreadyEditing() {
        val nothing = EditStep(null)
        assertEquals(nothing, editEnter(null, 1, msg(text = null), chat, "draft", null))
        assertEquals(nothing, editEnter(null, 1, msg(text = "￼", attachments = listOf(photo)), chat, "draft", null))
        // Already editing in this chat: the tap changes nothing, so what is typed there is not thrown away
        // and the first stash is not overwritten by the edit text.
        val mode = editTyped(entered(draft = "half a thought"), "see you at seven")!!
        assertEquals(EditStep(mode), editEnter(mode, 2, msg(guid = "ANOTHER-0002", text = "other"), chat, "see you at seven", null))
        assertEquals(EditStep(mode), editEnter(mode, 2, target, chat, "see you at seven", null))
    }

    @Test fun typing_isMirrored_exceptWhileTheEditIsOnItsWay() {
        val mode = entered()
        assertEquals("see you at seven", editTyped(mode, "see you at seven")!!.text)
        assertSame(mode, editTyped(mode, mode.text))
        assertNull(editTyped(null, "typed in an ordinary composer"))
        val sending = mode.copy(text = "see you at seven", sending = true)
        assertSame(sending, editTyped(sending, "something else"))
        // Cleared: nothing to submit, the send button is off.
        assertFalse(editTyped(mode, "")!!.canSubmit)
        assertFalse(editTyped(mode, "  \n ")!!.canSubmit)
        assertFalse(sending.canSubmit)
    }

    @Test fun cancel_putsTheDraftAndTheReplyBack() {
        val mode = editTyped(entered(draft = "half a thought", reply = replyTo), "see you at seven")
        assertEquals(EditStep(null, field = "half a thought", setsReply = true, reply = replyTo), editCancel(mode))
        // Nothing was stashed: the field is emptied, no reply banner.
        assertEquals(EditStep(null, field = "", setsReply = true, reply = null), editCancel(entered()))
        // Not in edit mode, or the edit is on its way (it cannot be called back): nothing.
        assertEquals(EditStep(null), editCancel(null))
        val sending = mode!!.copy(sending = true)
        assertEquals(EditStep(sending), editCancel(sending))
    }

    @Test fun submit_table() {
        val mode = entered(draft = "half a thought", reply = replyTo, seq = 6)
        val closed = EditStep(null, field = "half a thought", setsReply = true, reply = replyTo)
        data class Row(val typed: String, val expected: EditStep)
        fun request(text: String) = EditStep(
            mode.copy(text = text, sending = true),
            request = EditRequest(6, chat, guid, text.trim()),
        )
        val rows = listOf(
            Row("", EditStep(mode.copy(text = ""))),                             // empty: not submitted
            Row("   \n", EditStep(mode.copy(text = "   \n"))),                   // whitespace: not submitted
            Row("see you at six", closed),                                       // unchanged: closes, no request
            Row("  see you at six\n", closed),                                   // the same words in other spacing
            Row("see you at seven", request("see you at seven")),
            Row(" see you at seven \n", request(" see you at seven \n")),        // sent without the space around it
            Row("See you at six", request("See you at six")),                    // a capital is a change
        )
        for (r in rows) assertEquals(r.typed, r.expected, editSubmit(editTyped(mode, r.typed)))
        assertEquals(EditStep(null), editSubmit(null))
        // A second tap while the first is on its way: no second request.
        val sending = mode.copy(text = "see you at seven", sending = true)
        assertEquals(EditStep(sending), editSubmit(sending))
    }

    @Test fun submit_afterAnEditThatMayHaveLanded_asksTheRelay_evenForTheOriginalText() {
        // "see you at seven" was sent and ended without an answer; the Mac may have made that edit.
        val sent = editSubmit(editTyped(entered(draft = "half a thought", seq = 6), "see you at seven"))
        val after = editOutcome(sent.mode, sent.request!!, unconfirmed(ChangeDoubt.NO_ANSWER)).mode!!
        assertTrue(after.maybeApplied)
        assertFalse(after.sending)
        assertEquals("see you at seven", after.text)
        // The owner types the first wording back and saves: that is a change again, for all the app knows.
        val back = editSubmit(editTyped(after, "see you at six"))
        assertEquals(EditRequest(6, chat, guid, "see you at six"), back.request)
        assertTrue(back.mode!!.sending)
        // The relay then says "unchanged" itself when nothing differs, and edit mode ends as after any success.
        assertEquals(EditStep(null, field = "half a thought", setsReply = true, reply = null),
            editOutcome(back.mode, back.request!!, done(unchanged = true)))
        // Blank is still not submitted, and the X still leaves without a request.
        assertNull(editSubmit(editTyped(after, "  ")).request)
        assertEquals(EditStep(null, field = "half a thought", setsReply = true, reply = null), editCancel(after))
    }

    @Test fun onlyAnOutcomeThatLeavesDoubt_marksTheEditAsMaybeLanded() {
        for (doubt in ChangeDoubt.entries) assertTrue("$doubt", editMayHaveLanded(unconfirmed(doubt)))
        assertTrue(editMayHaveLanded(refused(ChangeRefusal.MAC_DID_NOT_APPLY)))
        for (reason in ChangeRefusal.entries - ChangeRefusal.MAC_DID_NOT_APPLY) assertFalse("$reason", editMayHaveLanded(refused(reason)))
        assertFalse(editMayHaveLanded(done()))
        // A certain refusal keeps the shortcut: nothing was changed, so the original text is still the message's.
        val sent = editSubmit(editTyped(entered(seq = 2), "see you at seven"))
        val after = editOutcome(sent.mode, sent.request!!, refused(ChangeRefusal.UNREACHABLE)).mode!!
        assertFalse(after.maybeApplied)
        assertNull(editSubmit(editTyped(after, "see you at six")).request)
        // Once in doubt, a later certain refusal does not clear it.
        val doubted = editOutcome(sent.mode, sent.request!!, unconfirmed(ChangeDoubt.CONNECTION_LOST)).mode!!
        val again = editSubmit(editTyped(doubted, "see you at eight"))
        assertTrue(editOutcome(again.mode, again.request!!, refused(ChangeRefusal.UNREACHABLE)).mode!!.maybeApplied)
    }

    @Test fun outcome_table() {
        val sending = editSubmit(editTyped(entered(draft = "half a thought", reply = replyTo, seq = 3), "see you at seven")).let {
            assertEquals(EditRequest(3, chat, guid, "see you at seven"), it.request)
            it.mode!!
        }
        val request = EditRequest(3, chat, guid, "see you at seven")
        val closed = EditStep(null, field = "half a thought", setsReply = true, reply = replyTo)
        fun stays(toast: String) = EditStep(sending.copy(sending = false), toast = toast)
        // The same, with the mode marked: this edit may be in the message all the same (editSubmit).
        fun staysInDoubt(toast: String) = EditStep(sending.copy(sending = false, maybeApplied = true), toast = toast)
        // Edit mode ends, the typed text stays as an ordinary draft after the one that was stashed; no reply banner.
        fun ends(toast: String) = EditStep(null, field = "half a thought\nsee you at seven", toast = toast)

        data class Row(val outcome: ChangeOutcome, val expected: EditStep)
        val rows = listOf(
            Row(done(), closed),
            Row(done(unchanged = true), closed),
            Row(refused(ChangeRefusal.TOO_LATE), ends("Too late to edit — Apple allows 15 minutes")),
            Row(refused(ChangeRefusal.ALREADY_UNSENT), ends("This message was already unsent")),
            Row(refused(ChangeRefusal.EDIT_LIMIT), ends("This message has been edited 5 times already")),
            Row(refused(ChangeRefusal.NOT_SUPPORTED), ends("This chat can't edit or unsend messages")),
            Row(refused(ChangeRefusal.RELAY_TOO_OLD), ends("Update the relay to edit or unsend")),
            Row(refused(ChangeRefusal.CONFLICT), stays("This message can't be edited now")),
            Row(refused(ChangeRefusal.UNKNOWN_MESSAGE), stays("The relay doesn't know this message")),
            Row(refused(ChangeRefusal.NOT_YOURS), stays("Only your own messages can be edited")),
            Row(refused(ChangeRefusal.BAD_REQUEST), stays("The relay refused the request")),
            Row(refused(ChangeRefusal.MAC_DID_NOT_APPLY), staysInDoubt("The Mac did not apply the change")),
            Row(refused(ChangeRefusal.AUTH), stays("The relay or Cloudflare Access rejected the request")),
            Row(refused(ChangeRefusal.NOT_A_RELAY), stays("The address did not answer like a relay")),
            Row(refused(ChangeRefusal.UNREACHABLE), stays("Can't reach the relay")),
            Row(unconfirmed(ChangeDoubt.NO_ANSWER), staysInDoubt("No answer from the relay — check the chat")),
            Row(unconfirmed(ChangeDoubt.CONNECTION_LOST), staysInDoubt("No answer from the relay — check the chat")),
            Row(unconfirmed(ChangeDoubt.SERVER_ERROR), staysInDoubt("No answer from the relay — check the chat")),
        )
        assertEquals("a row for every refusal", ChangeRefusal.entries.toSet(),
            rows.mapNotNull { (it.outcome as? ChangeOutcome.Refused)?.reason }.toSet())
        for (r in rows) assertEquals("${r.outcome}", r.expected, editOutcome(sending, request, r.outcome))
        // Whatever happens, the typed text is in the mode or in the field afterwards, except after a success.
        for (r in rows.filter { it.outcome !is ChangeOutcome.Done }) {
            val kept = r.expected.mode?.text ?: r.expected.field!!
            assertTrue("${r.outcome}", kept.contains("see you at seven"))
        }
    }

    @Test fun aRefusalThatEndsEditMode_leavesTheTypedTextAlone_whenNothingWasStashed() {
        val sending = editSubmit(editTyped(entered(reply = replyTo), " see you at seven")).mode!!
        val step = editOutcome(sending, EditRequest(1, chat, guid, "see you at seven"), refused(ChangeRefusal.TOO_LATE))
        assertNull(step.mode)
        assertEquals(" see you at seven", step.field)       // as typed, an ordinary draft now
        // The stashed reply target is not put back: this draft was never a reply, and must not go out as one.
        assertFalse(step.setsReply)
        assertNull(step.reply)
    }

    @Test fun anAnswerToAnotherRequest_changesNothing_andIsStillTold() {
        val request = EditRequest(3, chat, guid, "see you at seven")
        val tooLate = "Too late to edit — Apple allows 15 minutes"
        // The chat was left while the edit was on its way.
        assertEquals(EditStep(null, toast = tooLate), editOutcome(null, request, refused(ChangeRefusal.TOO_LATE)))
        assertEquals(EditStep(null), editOutcome(null, request, done()))
        // The chat was left and the same message is being edited again: the earlier answer is not this mode's.
        val later = entered(seq = 4).copy(text = "see you at eight", sending = true)
        assertEquals(EditStep(later, toast = tooLate), editOutcome(later, request, refused(ChangeRefusal.TOO_LATE)))
        assertEquals(EditStep(later), editOutcome(later, request, done()))
        // A mode that is not waiting for anything is not ended by a stray answer either.
        val idle = entered(seq = 3)
        assertEquals(EditStep(idle), editOutcome(idle, request, done()))
    }

    @Test fun aReplyChosenWhileEditing_waitsWithTheStash() {
        val mode = entered(draft = "half a thought")
        val chosen = editReplyChosen(mode, replyTo)
        assertEquals(mode.copy(stashedReply = replyTo), chosen)
        assertEquals(replyTo, editCancel(chosen).reply)
    }

    @Test fun textSharedWhileEditing_joinsTheStashedDraft_notTheEdit() {
        val mode = editTyped(entered(draft = "half a thought"), "see you at seven")!!
        val shared = editDraftAppended(mode, "https://example.test/a")
        assertEquals("see you at seven", shared.text)
        assertEquals("half a thought\nhttps://example.test/a", shared.stashedDraft)
        assertEquals("https://example.test/a", editDraftAppended(entered(), "https://example.test/a").stashedDraft)
    }

    @Test fun editMode_belongsToItsChat() {
        val mode = entered()
        assertSame(mode, editModeFor(mode, chat))
        assertNull(editModeFor(mode, group))
        assertNull(editModeFor(mode, null))
        assertNull(editModeFor(null, chat))
        // A mode left over from another chat does not stop this one from starting its own.
        val here = editEnter(editModeFor(mode, group), 2, msg(guid = "IN-GROUP-1", chatGuid = group), group, "", null)
        assertEquals(group, here.mode!!.chatGuid)
    }

    // ---- a toast about a chat that is not the one on screen ----

    @Test fun aToastShownAfterItsChatWasLeft_namesTheChat() {
        val tooLate = refused(ChangeRefusal.TOO_LATE)
        val noAnswer = unconfirmed(ChangeDoubt.NO_ANSWER)
        // In the message's own chat: the plain line.
        assertEquals("Too late to edit — Apple allows 15 minutes", changeToast(ChangeAction.EDIT, tooLate))
        assertEquals("No answer from the relay — check the chat", changeToast(ChangeAction.UNSEND, noAnswer, otherChat = null))
        // The chat was left while the request was out (an edit may take the Mac twenty seconds).
        assertEquals("Edit in the chat with Ada Example: Too late to edit — Apple allows 15 minutes",
            changeToast(ChangeAction.EDIT, tooLate, "Ada Example"))
        assertEquals("Undo Send in the chat with Ada Example: Too late to unsend — Apple allows 2 minutes",
            changeToast(ChangeAction.UNSEND, tooLate, "Ada Example"))
        assertEquals("No answer from the relay — check the chat with Ada Example", changeToast(ChangeAction.EDIT, noAnswer, "Ada Example"))
        // A chat with no name of its own (its title would be a number or an address).
        assertEquals("Edit in another chat: The Mac did not apply the change",
            changeToast(ChangeAction.EDIT, refused(ChangeRefusal.MAC_DID_NOT_APPLY), ""))
        assertEquals("No answer from the relay — check that chat", changeToast(ChangeAction.UNSEND, noAnswer, ""))
        for (action in ChangeAction.entries) {
            // Done says nothing, wherever the owner is.
            for (where in listOf(null, "", "Ada Example")) assertNull(changeToast(action, done(), where))
            // A refusal keeps its own wording behind the chat's name; an unconfirmed end still never says "failed".
            for (reason in ChangeRefusal.entries) {
                assertTrue(changeToast(action, refused(reason), "Ada Example")!!.endsWith(": " + changeRefusalMessage(action, reason)))
            }
            for (doubt in ChangeDoubt.entries) for (where in listOf("", "Ada Example")) {
                val line = changeToast(action, unconfirmed(doubt), where)!!
                assertTrue(line, line.startsWith("No answer from the relay — check "))
                for (word in listOf("fail", "couldn't", "could not", "not sent", "refused", "did not")) {
                    assertFalse("$line / $word", line.contains(word, ignoreCase = true))
                }
            }
        }
    }

    @Test fun theAnswerToAnEditWhoseChatWasLeft_isToldWithTheChatsName() {
        val request = EditRequest(3, chat, guid, "see you at seven")
        val named = "Edit in the chat with Ada Example: The Mac did not apply the change"
        // Edit mode ended with the chat; the answer changes nothing and is told, naming the chat.
        assertEquals(EditStep(null, toast = named),
            editOutcome(null, request, refused(ChangeRefusal.MAC_DID_NOT_APPLY), otherChat = "Ada Example"))
        // Another chat's composer is editing meanwhile: its mode is not touched.
        val elsewhere = EditMode(4, group, "IN-GROUP-1", original = "a", text = "ab")
        assertEquals(EditStep(elsewhere, toast = named),
            editOutcome(elsewhere, request, refused(ChangeRefusal.MAC_DID_NOT_APPLY), otherChat = "Ada Example"))
        assertEquals(EditStep(null), editOutcome(null, request, done(), otherChat = "Ada Example"))
    }

    // ---- what an unsent message leaves on screen ----

    @Test fun theEditedCaption_isNotLeftUnderMyOwnUnsentMessage() {
        data class Row(val what: String, val m: Msg, val expected: Boolean)
        val rows = listOf(
            Row("never edited", msg(), false),
            Row("my edited message", msg(dateEdited = 5.0), true),
            Row("their edited message", msg(fromMe = false, dateEdited = 5.0), true),
            // The Mac stamps the edit date on an unsent message: nothing left to caption.
            Row("my unsent message", msg(text = null, dateEdited = 5.0), false),
            Row("my unsent message, blank text", msg(text = " ", dateEdited = 5.0), false),
            Row("my unsent photo, flag and placeholder left", msg(text = "\uFFFC", hasAttachments = true, dateEdited = 5.0), false),
            // Someone else's unsent message keeps the caption it always had: the one trace that something was there.
            Row("their unsent message", msg(text = null, fromMe = false, dateEdited = 5.0), true),
            // A caption taken back from under a photo that stays: still an edited message with something to show.
            Row("my photo whose words were unsent", msg(text = "\uFFFC", attachments = listOf(photo), dateEdited = 5.0), true),
            Row("my edited link card", msg(text = null, link = LinkPreview(url = "https://example.test/a"), dateEdited = 5.0), true),
            Row("nothing to show and no edit date", msg(text = null), false),
        )
        for (r in rows) assertEquals(r.what, r.expected, showsEditedCaption(r.m))
    }

    @Test fun unsending_isSaid_untilTheBubbleHasEmptied() {
        val m = msg()
        assertTrue(showsUnsending(m, setOf(guid)))
        assertFalse(showsUnsending(m, emptySet()))
        assertFalse(showsUnsending(m, setOf("ANOTHER-0002")))
        // The update arrived while the mark was still held: nothing left to unsend, nothing said.
        assertFalse(showsUnsending(m.copy(text = null, date_edited = 5.0), setOf(guid)))
    }

    @Test fun aConfirmedChange_staysMarked_untilItShows_andAnyOtherEndClearsTheMarkAtOnce() {
        assertTrue(changeHeldUntilShown(done()))
        assertTrue(changeHeldUntilShown(done(unchanged = true)))          // nothing to wait for: its recheck is empty
        assertEquals(emptyList<Long>(), changeRecheckDelaysMillis(done(unchanged = true)))
        // Refused or unconfirmed: the owner may try again straight away.
        for (reason in ChangeRefusal.entries) assertFalse("$reason", changeHeldUntilShown(refused(reason)))
        for (doubt in ChangeDoubt.entries) assertFalse("$doubt", changeHeldUntilShown(unconfirmed(doubt)))
        // What the mark does meanwhile: "Unsending…" stays under the untouched bubble, and the panel offers it nothing.
        val before = msg()
        val held = listOf(before)
        assertTrue(changeStillPending(ChangeAction.UNSEND, before, held))
        assertTrue(showsUnsending(before, setOf(guid)))
        assertEquals(PanelChanges(false, false), panelChangesFor(before, thread(), now, ChangeMemory(), unsending = setOf(guid)))
        assertEquals(PanelChanges(false, false), panelChangesFor(before, thread(), now, ChangeMemory(), saving = setOf(guid)))
        // Once the update shows, the wait is over (and the mark goes with it).
        assertFalse(changeStillPending(ChangeAction.UNSEND, before, listOf(before.copy(text = null))))
        assertFalse(changeStillPending(ChangeAction.EDIT, before, listOf(before.copy(text = "see you at seven", date_edited = 5.0))))
    }

    // ---- what the relay may be remembered for ----

    @Test fun onlyTheRelaysOwnJson_hidesAnEntryForTheSession() {
        // Whatever stands at the address (a proxy, another service, an error page) says nothing about the relay.
        val notTheRelays = listOf(
            Triple(405, "text/plain", "Method Not Allowed"),
            Triple(405, "text/html", page),
            Triple(501, "text/plain", "Not Implemented"),
            Triple(501, "text/html", page),
            Triple(404, "text/plain", "Not Found"),
            Triple(404, "text/html", page),
        )
        val fresh = ChangeMemory()
        for ((code, type, body) in notTheRelays) {
            val outcome = changeOutcomeFor(code, type, body)
            assertFalse("$code $type", hidesFromNowOn(outcome))
            assertSame("$code $type", fresh, changeMemoryAfter(fresh, ChangeAction.EDIT, ChatKind.IMESSAGE, outcome))
        }
        val theRelays = listOf(
            Triple(405, jsonType, """{"detail":"Method Not Allowed"}"""),
            Triple(501, jsonType, """{"detail":"no configured engine can edit"}"""),
            Triple(404, jsonType, """{"detail":"Not Found"}"""),
        )
        for ((code, type, body) in theRelays) assertTrue("$code", hidesFromNowOn(changeOutcomeFor(code, type, body)))
        // And its 502 never does, whatever it says.
        assertFalse(hidesFromNowOn(changeOutcomeFor(502, jsonType, """{"detail":"the Mac did not apply the change"}""")))
        assertFalse(hidesFromNowOn(changeOutcomeFor(502, jsonType, """{"detail":"engine failed"}""")))
    }

    // ---- the field, its saved note and edit mode ----

    @Test fun composerSettle_table() {
        val mode = entered(draft = "half a thought")
        data class Row(val what: String, val saved: String?, val mode: EditMode?, val pending: ComposerText?, val expected: ComposerSettle)
        val rows = listOf(
            Row("an ordinary composer", null, null, null, ComposerSettle(null, null)),
            Row("entering edit mode: the message's text in the field, the draft noted",
                null, mode, ComposerText(chat, "see you at six", 1), ComposerSettle("see you at six", "half a thought")),
            Row("in edit mode, nothing new", "half a thought", mode, null, ComposerSettle(null, "half a thought")),
            Row("a rotation, or coming back from another screen, in edit mode", "half a thought", mode.copy(text = "see you at seven"), null,
                ComposerSettle(null, "half a thought")),
            Row("a text shared meanwhile joined the stash: the note follows",
                "half a thought", editDraftAppended(mode, "https://example.test/a"), null,
                ComposerSettle(null, "half a thought\nhttps://example.test/a")),
            Row("nothing was typed before: an empty stash is still a note", null, entered(), null, ComposerSettle(null, "")),
            Row("the X, or a saved edit: the stash is back and the note goes",
                "half a thought", null, ComposerText(chat, "half a thought", 2), ComposerSettle("half a thought", null)),
            Row("an edit that can no longer work: both texts as a draft",
                "half a thought", null, ComposerText(chat, "half a thought\nsee you at seven", 3),
                ComposerSettle("half a thought\nsee you at seven", null)),
            // The process was killed in the background: the field came back with the edit text, edit mode did not.
            Row("edit mode did not survive", "half a thought", null, null, ComposerSettle("half a thought", null)),
            Row("edit mode did not survive, nothing had been typed before", "", null, null, ComposerSettle("", null)),
        )
        for (r in rows) assertEquals(r.what, r.expected, composerSettle(r.saved, r.mode, r.pending))
    }

    @Test fun afterTheProcessDied_theFieldGetsTheStashedDraftBack_neverTheEditTextAsADraft() {
        // A draft is in the field; Edit is tapped; the note is saved beside the field from that tap on.
        val enter = editEnter(null, 1, target, chat, draft = "half a thought", reply = replyTo)
        val onEnter = composerSettle(null, enter.mode, ComposerText(chat, enter.field!!, 1))
        assertEquals(ComposerSettle("see you at six", "half a thought"), onEnter)
        // A: the edit is half typed, the app goes to the background and its process is killed.
        // B: the check mark was tapped, the app went to the background with the edit text still in the
        //    field, the relay's answer ended edit mode in memory, and then the process was killed.
        // Either way the chat composes again with the saved field ("see you at sev…"), the saved note,
        // a fresh ChatVM with no edit mode, and nothing pending:
        val restored = composerSettle(onEnter.stash, null, null)
        assertEquals("half a thought", restored.field)          // the owner's own draft, not text he believes is already in the message
        assertNull(restored.stash)
        // Settled once: the next pass changes nothing, so what he types from now on is left alone.
        assertEquals(ComposerSettle(null, null), composerSettle(restored.stash, null, null))
        // Without a process death, B is the ordinary path: the answer's own text is pending and wins, to the same end.
        val answered = editOutcome(enter.mode!!.copy(text = "see you at seven", sending = true),
            EditRequest(1, chat, guid, "see you at seven"), done())
        assertEquals(ComposerSettle("half a thought", null),
            composerSettle(onEnter.stash, answered.mode, ComposerText(chat, answered.field!!, 2)))
    }

    @Test fun aTextSharedAfterAnEditWasAnsweredOffScreen_joinsTheDraft_andIsNotReplacedByIt() {
        // An edit was on its way when a share opened the chat picker; the relay said Done meanwhile, so
        // the stash is pending for a field that still holds the edit text. The owner picks the same chat.
        val pending = ComposerText(chat, "half a thought", 2)
        // Settled first, as the share's effect does: the pending text goes in, then the share after it.
        val settled = composerSettle("half a thought", null, pending)
        val field = appendToDraft(settled.field!!, "https://example.test/a")
        assertEquals("half a thought\nhttps://example.test/a", field)
        // Nothing is left pending that could replace the field afterwards.
        assertEquals(ComposerSettle(null, null), composerSettle(settled.stash, null, null))
        // The same after a process death: the stash is put back first, and the share joins it.
        val restored = composerSettle("half a thought", null, null)
        assertEquals("half a thought\nhttps://example.test/a", appendToDraft(restored.field!!, "https://example.test/a"))
    }

    @Test fun systemBack_leavesEditMode_beforeItLeavesTheChat() {
        assertFalse(editBackCancels(null))                           // an ordinary composer: Back leaves the chat, as ever
        val mode = editTyped(entered(draft = "half a thought", reply = replyTo), "see you at seven")!!
        assertTrue(editBackCancels(mode))
        // What it then does is the X's: the draft and the reply are back.
        assertEquals(EditStep(null, field = "half a thought", setsReply = true, reply = replyTo), editCancel(mode))
        // The edit is on its way and cannot be called back: Back is the chat's again.
        assertFalse(editBackCancels(mode.copy(sending = true)))
    }

    // ---- where the rules are wired in (the ViewModel and the requests cannot be built on the JVM) ----

    private fun mainSource(name: String): List<String> {
        // Unit tests run in the app module's directory.
        val path = "src/main/java/io/github/avtraang/selfbubbles/$name"
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, path), File(it, "app/$path")) }
            .first { it.isFile }
            .readLines()
    }

    /** The lines of the member function of ChatVM that starts with [head], up to its closing brace. */
    private fun vmFunction(lines: List<String>, head: String): List<String> {
        val start = lines.indexOfFirst { it.startsWith(head) }
        assertTrue("$head not found", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return lines.subList(start, end)
    }

    @Test fun editMode_endsWhereverAChatIsLeft() {
        val vm = mainSource("ChatVM.kt")
        // Leaving by Back, another chat taking the screen, and the relay's address changing: the three
        // places a reply target is dropped because its chat is gone. Edit mode goes with it.
        for (head in listOf("    fun back() {", "    fun open(t: Thread) {", "    private fun leaveRelay() {")) {
            val body = vmFunction(vm, head)
            assertTrue("$head drops the reply", body.any { it.contains("cancelReply()") })
            assertTrue("$head ends edit mode", body.any { it.contains("endEdit()") })
        }
        // A send does not: it drops the reply it used and nothing else.
        assertFalse(vmFunction(vm, "    fun send(text: String) {").any { it.contains("endEdit()") })
    }

    @Test fun aChangeTheRelayConfirmed_keepsItsMark_untilTheRecheckHasEnded() {
        val vm = mainSource("ChatVM.kt")
        // Both flows: the toast (or the edit mode's step) first, then the wait for the change to show, then the mark goes.
        for ((head, clears) in listOf(
            "    fun unsend(m: Msg) {" to "unsending = unsendEnded(unsending, m.guid)",
            "    private fun sendEdit(req: EditRequest) {" to "savingEdits = savingEdits - req.guid",
        )) {
            val body = vmFunction(vm, head)
            val waits = body.indexOfFirst { it.contains("if (changeHeldUntilShown(outcome)) recheck?.join()") }
            val cleared = body.indexOfFirst { it.contains(clears) }
            assertTrue("$head waits for the change to show", waits >= 0)
            assertTrue("$head clears its mark after the wait", cleared > waits)
            assertEquals("$head clears its mark in one place", 1, body.count { it.contains(clears) })
        }
        // The edit's mark is set before the request leaves, and the panel is given both sets.
        val sendEdit = vmFunction(vm, "    private fun sendEdit(req: EditRequest) {")
        assertTrue(sendEdit.indexOfFirst { it.contains("savingEdits = savingEdits + req.guid") } in
            0 until sendEdit.indexOfFirst { it.contains("Api.edit(") })
        val screen = mainSource("ConversationScreen.kt").joinToString("\n")
        assertTrue(screen.contains("unsending = vm.unsending, editing = vm.editing, saving = vm.savingEdits"))
        assertTrue(screen.contains("note = if (showsUnsending(m, vm.unsending))"))
    }

    @Test fun theComposer_isSettledBeforeAShareIsAppended_andItsNoteIsSavedWithTheField() {
        val screen = mainSource("ConversationScreen.kt")
        // The note lives in the same saveable scope as the field, so it comes back with it after a process death.
        assertTrue(screen.any { it.contains("var editStash by rememberSaveable { mutableStateOf<String?>(null) }") })
        // The share's effect: what edit mode left for the field goes in first, then the shared text.
        val start = screen.indexOfFirst { it.contains("LaunchedEffect(draftAppend) {") }
        val end = (start until screen.size).first { screen[it].contains("vm.consumeDraftAppend(draftAppend)") }
        val effect = screen.subList(start, end)
        val settles = effect.indexOfFirst { it.contains("settleComposer()") }
        val appends = effect.indexOfFirst { it.contains("appendToDraft(") }
        assertTrue("the share's effect settles the composer", settles >= 0)
        assertTrue("and only then appends", appends > settles)
        // System Back: edit mode's handler is registered before the search handler, so search still closes first.
        val editBack = screen.indexOfFirst { it.contains("BackHandler(enabled = editBackCancels(edit))") }
        val searchBack = screen.indexOfFirst { it.contains("BackHandler(enabled = searchMode)") }
        assertTrue(editBack in 0 until searchBack)
    }

    @Test fun theBubble_asksTheRuleForItsEditedCaption() {
        val bubble = mainSource("Bubble.kt").joinToString("\n")
        assertTrue(bubble.contains("if (showsEditedCaption(m)) {"))
        assertFalse(bubble.contains("if (m.date_edited != null)"))
    }

    @Test fun bothRequests_goThroughTheSendClient_andNeitherThroughTheOutbox() {
        val api = mainSource("Imsg.kt").joinToString("\n")
        // The 75 s client: the relay answers only once the Mac has made the change.
        assertTrue(api.contains("postChange(httpSend) { unsendRequest(BASE, chatGuid, guid) }"))
        assertTrue(api.contains("postChange(httpSend) { editRequest(BASE, chatGuid, guid, text) }"))
        assertFalse(mainSource("EditUnsend.kt").any { it.contains("Outbox.") })
        // The outbox knows nothing of either.
        val outbox = mainSource("Outbox.kt").joinToString("\n")
        for (word in listOf("unsend", "Api.edit", "EditMode")) assertFalse(word, outbox.contains(word))
    }

    // ---- /health "capabilities" (RelayProbe.kt) ----

    @Test fun health_capabilities_areReadWhenTheRelaySendsThem() {
        fun probe(extra: String) =
            RelayProbe.classify(200, "application/json", """{"ok":true,"cursor":12,"protocol":1,"engines":["bluebubbles"]$extra}""")
                as ProbeOutcome.Connected
        assertNull(probe("").capabilities)                                           // an older relay: nothing said
        assertEquals(listOf("edit", "unsend"), probe(""","capabilities":["edit","unsend"]""").capabilities)
        assertEquals(listOf("unsend"), probe(""","capabilities":["unsend"]""").capabilities)
        assertEquals(emptyList<String>(), probe(""","capabilities":[]""").capabilities)
        // Not a list of names: nothing said, which is not "cannot".
        assertNull(probe(""","capabilities":"edit"""").capabilities)
        assertNull(probe(""","capabilities":{"edit":true}""").capabilities)
        assertNull(probe(""","capabilities":null""").capabilities)
        // Only plain identifiers are kept, as for engines.
        assertEquals(listOf("edit"), probe(""","capabilities":["edit","not a name!",""]""").capabilities)
        // The Connected line the owner reads is what it was.
        assertEquals(probe("").message, probe(""","capabilities":["edit","unsend"]""").message)
        assertNotEquals(probe(""), probe(""","capabilities":[]"""))
    }
}
