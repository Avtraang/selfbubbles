package io.github.avtraang.selfbubbles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * The voice screen ("Voice Text") talks to the relay three times: the spoken
 * sentence, the answer to the relay's question, and a "cancel" when nothing
 * was heard. Each call used to run bare inside the activity's coroutine: a
 * network failure, or a send the Mac took more than ten seconds over, threw,
 * nothing caught it, and Android ended the app's process while the Mac went
 * on delivering (audit A3-F6). [voiceCall] is what those calls go through now:
 * whatever a call throws becomes a line to show and say.
 */
class VoiceCallsTest {

    private fun line(step: VoiceStep, call: suspend () -> String?): String = runBlocking { voiceCall(step, call = call) }

    private val failures: List<Throwable> = listOf(
        SocketTimeoutException("timeout"), UnknownHostException("relay.invalid"), ConnectException("refused"),
        SSLHandshakeException("no trusted certificate"), IOException("connection reset"),
        IllegalStateException("no relay configured"), RuntimeException("anything else"),
    )

    @Test fun a_reply_is_passed_on_as_it_is() {
        assertEquals("Send hello to Alice Anders?", line(VoiceStep.PREPARE) { "Send hello to Alice Anders?" })
        assertEquals("Sent to Alice Anders.", line(VoiceStep.ANSWER) { "Sent to Alice Anders." })
        assertEquals("Cancelled.", line(VoiceStep.CANCEL) { "Cancelled." })
    }

    @Test fun no_failure_of_any_call_is_thrown_at_the_activity() {
        for (step in VoiceStep.values()) for (error in failures) {
            val said = line(step) { throw error }
            assertTrue("$step / ${error.javaClass.simpleName}", said.isNotBlank())
        }
    }

    @Test fun a_line_about_a_failure_is_never_a_question() {
        // The screen listens for an answer after a line that ends in "?"; a failure must end the conversation.
        for (step in VoiceStep.values()) {
            for (error in failures) assertFalse(line(step) { throw error }.trim().endsWith("?"))
            assertFalse(line(step) { null }.trim().endsWith("?"))
        }
    }

    @Test fun an_answer_the_relay_may_have_acted_on_says_so() {
        // The "yes" left the phone, or may have: the Mac can be sending while the app hears nothing.
        for (error in listOf(SocketTimeoutException("timeout"), IOException("connection reset"), RuntimeException("?"))) {
            assertEquals(VOICE_ANSWER_UNHEARD, line(VoiceStep.ANSWER) { throw error })
        }
        assertEquals(VOICE_ANSWER_UNHEARD, line(VoiceStep.ANSWER) { null })      // a status that was not the relay's reply
        assertTrue(VOICE_ANSWER_UNHEARD.contains("check the chat", ignoreCase = true))
    }

    @Test fun an_answer_that_never_left_the_phone_says_nothing_was_sent() {
        for (error in listOf(UnknownHostException("relay.invalid"), ConnectException("refused"), SSLHandshakeException("x"))) {
            assertEquals(VOICE_NOTHING_SENT, line(VoiceStep.ANSWER) { throw error })
        }
    }

    // ---- a status in place of the relay's line (review of 2026-10-08) ----

    private fun status(code: Int) = SendFailedException(sendFailureFor(code), "voice HTTP $code", code)

    @Test fun an_answer_the_relay_refused_says_that_nothing_was_sent() {
        // A rejected token, Cloudflare Access in the way, a route that is not there: refused before
        // anything was handed to an engine. It used to read "check the chat", as if it might have gone out.
        for (code in listOf(401, 403, 404, 405, 422, 501)) {
            assertEquals("HTTP $code", VOICE_REFUSED, line(VoiceStep.ANSWER) { throw status(code) })
        }
        assertTrue(VOICE_REFUSED.endsWith("Nothing was sent."))
        assertFalse(VOICE_REFUSED.contains("check the chat", ignoreCase = true))
    }

    @Test fun an_answer_met_by_a_server_error_or_a_timeout_status_is_still_uncertain() {
        // The relay's own 502 includes engines that deliver a moment later; a 504 or 524 is the route giving up.
        for (code in listOf(500, 502, 503, 504, 522, 524, 408)) {
            assertEquals("HTTP $code", VOICE_ANSWER_UNHEARD, line(VoiceStep.ANSWER) { throw status(code) })
        }
    }

    @Test fun a_refused_sentence_says_so_and_a_refused_cancel_is_still_a_cancel() {
        assertEquals(VOICE_REFUSED, line(VoiceStep.PREPARE) { throw status(401) })
        assertEquals(VOICE_NOTHING_SENT, line(VoiceStep.PREPARE) { throw status(502) })   // nothing is sent before a yes
        assertEquals(VOICE_CANCELLED, line(VoiceStep.CANCEL) { throw status(401) })
    }

    @Test fun the_status_of_a_voice_call_is_what_it_fails_with() {
        assertEquals("Sent to Alice Anders.", voiceReply(200, "  Sent to Alice Anders.\n"))
        assertEquals(null, voiceReply(200, null))
        for (code in listOf(301, 401, 403, 500, 524)) {
            try {
                voiceReply(code, "whatever the route answered")
                fail("HTTP $code is not the relay's line")
            } catch (e: SendFailedException) {
                assertEquals(code, e.httpCode)
                assertFalse(e.message.orEmpty().contains("whatever"))       // the body is not carried along
            }
        }
    }

    @Test fun a_sentence_that_did_not_reach_the_relay_sent_nothing() {
        for (error in failures) assertEquals(VOICE_NOTHING_SENT, line(VoiceStep.PREPARE) { throw error })
        assertEquals(VOICE_NOTHING_SENT, line(VoiceStep.PREPARE) { null })
    }

    @Test fun a_cancel_that_was_not_heard_is_still_a_cancel() {
        // Nothing is sent without a yes, and no yes was given.
        for (error in failures) assertEquals(VOICE_CANCELLED, line(VoiceStep.CANCEL) { throw error })
        assertEquals(VOICE_CANCELLED, line(VoiceStep.CANCEL) { null })
    }

    @Test fun leaving_the_screen_still_cancels_the_conversation() {
        try {
            line(VoiceStep.ANSWER) { throw CancellationException("the activity was closed") }
            fail("a cancellation has to pass through")
        } catch (expected: CancellationException) {
        }
    }

    @Test fun no_line_repeats_what_the_failure_said() {
        // A throwable's own text can carry the relay's address.
        val said = line(VoiceStep.ANSWER) { throw UnknownHostException("relay.private.example") }
        assertFalse(said.contains("relay.private.example"))
    }

    @Test fun the_answer_waits_as_long_as_a_text_send_and_is_posted_once() {
        // /v/confirm sends while it is being answered, and empties its slot first: a POST repeated by
        // OkHttp would be told "nothing waiting" for a message that went out.
        val req = voiceConfirmRequest("https://relay.example", "yes")
        assertEquals("POST", req.method)
        assertEquals("https://relay.example/v/confirm", req.url.toString())
        assertTrue(req.body!!.isOneShot())
        assertTrue(httpSend.readTimeoutMillis >= 60_000)
    }
}
