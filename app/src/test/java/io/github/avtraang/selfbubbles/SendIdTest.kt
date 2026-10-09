package io.github.avtraang.selfbubbles

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * One send is one message. The relay could not tell a repeated request from a
 * second message, so "Send again" on a text whose first send had ended without
 * an answer could deliver it twice. Every text now carries an id from the
 * moment it enters the outbox; the relay keeps the id and what became of the
 * send, and does not send the same id twice.
 *
 * These are the app's rules around that (the relay's are in its own suite):
 * when the id is made and that it is stored with the text, what each row
 * offers, what happens before "Send again" on a text that may have gone out,
 * and that sending such a text after all is a separate, explicit action under
 * a new id. The first text of a new conversation has no stored entry: its id
 * lives with the compose screen.
 */
class SendIdTest {
    private val chat = "iMessage;-;+15550100200"

    private fun text(why: UnsentWhy? = null, sendId: String = "0f8fad5b-d9cb-469f-a165-70867728950e", maybeSent: Boolean = false) =
        UnsentText(7, chat, "Alice Anders", "synthetic text", why = why, sendId = sendId, maybeSent = maybeSent, createdAtMillis = 1_000)

    private fun body(req: okhttp3.Request): String = Buffer().also { req.body!!.writeTo(it) }.readUtf8()

    // ---- the id itself ----

    @Test fun an_id_is_what_the_relay_accepts_and_no_two_are_alike() {
        val ids = (1..200).map { newSendId() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { Regex("[A-Za-z0-9_-]{8,64}").matches(it) })
    }

    @Test fun the_id_is_stored_with_the_text() {
        val stored = outboxEncode(listOf(text()))
        assertTrue(stored.contains("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertEquals(listOf(text()), outboxDecode(stored))
    }

    @Test fun a_text_stored_by_a_build_from_before_the_id_is_kept_and_has_none() {
        val old = """[{"id":7,"chatGuid":"$chat","chatTitle":"Alice Anders","text":"synthetic text","why":"NO_ANSWER","createdAtMillis":1000}]"""
        val entry = outboxDecode(old).single()
        assertEquals("", entry.sendId)
        assertEquals(UnsentWhy.NO_ANSWER, entry.why)
    }

    @Test fun send_again_is_the_same_message_and_keeps_its_id() {
        val again = outboxRetry(listOf(text(UnsentWhy.NO_ANSWER)), 7).single()
        assertEquals(text().sendId, again.sendId)
        assertTrue(again.sending)
    }

    @Test fun send_anyway_is_a_new_message_under_a_new_id_and_the_doubt_stays() {
        val anyway = outboxSendAnyway(listOf(text(UnsentWhy.RELAY_UNSURE)), 7, "a-new-id-0001").single()
        assertEquals("a-new-id-0001", anyway.sendId)
        assertTrue(anyway.sending)
        assertTrue(anyway.maybeSent)                    // whatever this send ends with, an earlier one may be in the chat
        assertEquals(2, anyway.attempts)
    }

    @Test fun send_anyway_leaves_a_text_alone_that_is_in_flight_or_is_another_one() {
        val inFlight = listOf(text(why = null))
        assertEquals(inFlight, outboxSendAnyway(inFlight, 7, "a-new-id-0001"))
        val other = listOf(text(UnsentWhy.RELAY_UNSURE))
        assertEquals(other, outboxSendAnyway(other, 8, "a-new-id-0001"))
    }

    // ---- what goes to the relay ----

    @Test fun a_text_send_names_its_id() {
        val req = textSendRequest("https://relay.example", chat, "hello", null, "a-send-id-0001")
        assertTrue(body(req).contains("\"client_id\":\"a-send-id-0001\""))
        assertTrue(req.body!!.isOneShot())
    }

    @Test fun a_text_without_an_id_is_sent_as_it_always_was() {
        val req = textSendRequest("https://relay.example", chat, "hello", null, null)
        assertFalse(body(req).contains("client_id"))
        assertEquals("""{"chat_guid":"$chat","text":"hello"}""", body(req))
    }

    @Test fun the_first_text_of_a_new_conversation_names_its_id_too() {
        val req = newChatRequest("https://relay.example", listOf("+15550100200"), "hello", "a-send-id-0002")
        assertEquals("https://relay.example/create_chat", req.url.toString())
        assertTrue(body(req).contains("\"client_id\":\"a-send-id-0002\""))
        assertTrue(req.body!!.isOneShot())
        assertFalse(body(newChatRequest("https://relay.example", listOf("+15550100200"), "hello", null)).contains("client_id"))
    }

    // ---- what the relay answers ----

    @Test fun the_relays_reason_is_read_from_its_answer_and_from_nothing_else() {
        assertEquals("send_outcome_unknown", relayErrorCode("""{"detail":{"code":"send_outcome_unknown","message":"x"}}"""))
        assertEquals("send_id_reused", relayErrorCode("""{"detail":{"code":"send_id_reused"}}"""))
        for (other in listOf(null, "", "unauthorized", "<html><body>Sign in</body></html>", """{"detail":"plain words"}""",
                             """{"detail":{"code":42}}""", """{"code":"send_outcome_unknown"}""", """[1,2]""", "{ not json")) {
            assertNull(other, relayErrorCode(other))
        }
    }

    @Test fun a_send_the_relay_cannot_account_for_is_not_called_failed() {
        val unsure = unsentWhyFor(SendFailedException(SendFailure.OTHER, "send HTTP 409", 409, relayCode = "send_outcome_unknown"))
        assertEquals(UnsentWhy.RELAY_UNSURE, unsure)
        assertFalse(unsure.certain)
        val line = unsentLine(unsure)
        assertFalse(line.startsWith("Not sent"))
        assertTrue(line.contains("check the chat"))
    }

    @Test fun the_relays_other_refusals_are_certain() {
        // An id that stands for another message, and a relay that could not write the id down: nothing was sent.
        for (code in listOf("send_id_reused" to 409, "send_ids_unavailable" to 503)) {
            val why = unsentWhyFor(SendFailedException(SendFailure.OTHER, "send HTTP ${code.second}", code.second, relayCode = code.first))
            assertTrue(code.first, why.certain)
        }
        assertEquals(UnsentWhy.REFUSED, unsentWhyFor(SendFailedException(SendFailure.OTHER, "send HTTP 409", 409)))
        assertEquals(UnsentWhy.SERVER_ERROR, unsentWhyFor(SendFailedException(SendFailure.OTHER, "send HTTP 503", 503)))
    }

    // ---- what a row offers ----

    @Test fun a_text_that_certainly_did_not_go_out_offers_send_again() {
        assertEquals(UnsentSendAction.SEND_AGAIN, unsentSendAction(text(UnsentWhy.UNREACHABLE)))
        assertEquals(UnsentSendAction.SEND_AGAIN, unsentSendAction(text(UnsentWhy.REFUSED, sendId = "")))
    }

    @Test fun a_text_that_may_have_gone_out_offers_send_again_only_under_its_id() {
        for (why in listOf(UnsentWhy.NO_ANSWER, UnsentWhy.CONNECTION_LOST, UnsentWhy.SERVER_ERROR, UnsentWhy.INTERRUPTED)) {
            assertEquals(why.name, UnsentSendAction.SEND_AGAIN, unsentSendAction(text(why)))
            // Without an id nothing tells the relay that it is the same message: only the warned way is left.
            assertEquals(why.name, UnsentSendAction.SEND_ANYWAY, unsentSendAction(text(why, sendId = "")))
        }
        // A certain failure after a send that may have gone out is no safer.
        assertEquals(UnsentSendAction.SEND_ANYWAY, unsentSendAction(text(UnsentWhy.UNREACHABLE, sendId = "", maybeSent = true)))
        assertEquals(UnsentSendAction.SEND_AGAIN, unsentSendAction(text(UnsentWhy.UNREACHABLE, maybeSent = true)))
    }

    @Test fun a_text_the_relay_cannot_account_for_offers_only_send_anyway() {
        assertEquals(UnsentSendAction.SEND_ANYWAY, unsentSendAction(text(UnsentWhy.RELAY_UNSURE)))
        assertEquals(UnsentSendAction.SEND_ANYWAY, unsentSendAction(text(UnsentWhy.RELAY_UNSURE, sendId = "")))
    }

    @Test fun send_anyway_says_what_it_risks() {
        assertTrue(SEND_ANYWAY_WARNING.contains("twice"))
        assertTrue(SEND_ANYWAY_WARNING.contains("may already have been sent"))
        assertEquals("Send anyway", SEND_ANYWAY_LABEL)
    }

    // ---- "Send again" on a text that may have gone out: ask the relay first ----

    @Test fun a_certain_failure_is_sent_again_without_asking() {
        assertEquals(SendAgainPlan.SEND, sendAgainPlan(text(UnsentWhy.UNREACHABLE), relayKeepsIds = null))
        assertEquals(SendAgainPlan.SEND, sendAgainPlan(text(UnsentWhy.UNREACHABLE), relayKeepsIds = false))
    }

    @Test fun a_doubtful_text_is_sent_under_its_id_only_to_a_relay_that_keeps_ids() {
        val doubtful = text(UnsentWhy.NO_ANSWER)
        assertEquals(SendAgainPlan.SEND, sendAgainPlan(doubtful, relayKeepsIds = true))
        // A relay from before the id would take the same id for a new message.
        assertEquals(SendAgainPlan.RELAY_CANNOT_TELL, sendAgainPlan(doubtful, relayKeepsIds = false))
        // The relay could not be asked just now: nothing is sent, the row stays as it is.
        assertEquals(SendAgainPlan.COULD_NOT_ASK, sendAgainPlan(doubtful, relayKeepsIds = null))
        // And never without an id, whatever the relay can do.
        assertEquals(SendAgainPlan.RELAY_CANNOT_TELL, sendAgainPlan(text(UnsentWhy.NO_ANSWER, sendId = ""), relayKeepsIds = true))
    }

    @Test fun whether_the_relay_keeps_ids_is_what_its_health_answer_says() {
        fun connected(caps: List<String>?) = ProbeOutcome.Connected(1, emptyList(), emptyMap(), caps)
        assertEquals(true, relayKeepsSendIds(connected(listOf("edit", "send_id"))))
        assertEquals(false, relayKeepsSendIds(connected(listOf("edit", "unsend"))))
        assertEquals(false, relayKeepsSendIds(connected(null)))                 // a relay that names no capabilities
        assertNull(relayKeepsSendIds(null))                                     // no answer as a relay
    }

    // ---- the first text of a new conversation: the id lives with the compose screen ----

    @Test fun a_second_tap_on_the_same_message_to_the_same_people_is_the_same_send() {
        val first = newChatSendFor(null, listOf("+15550100200", "+15550100300"), "hello") { "id-one-0001" }
        val again = newChatSendFor(first, listOf("+15550100300", "+15550100200"), "hello") { "id-two-0002" }
        assertEquals("id-one-0001", again.id)                                   // the order of the people does not matter
    }

    @Test fun other_people_or_other_words_are_another_send() {
        val first = newChatSendFor(null, listOf("+15550100200"), "hello") { "id-one-0001" }
        assertNotEquals(first.id, newChatSendFor(first, listOf("+15550100300"), "hello") { "id-two-0002" }.id)
        assertNotEquals(first.id, newChatSendFor(first, listOf("+15550100200"), "hello there") { "id-two-0002" }.id)
    }

    @Test fun what_a_failed_start_leaves_behind() {
        val send = NewChatSend("k", "id-one-0001")
        assertFalse(newChatAfter(send, ConnectException("refused")).doubtful)   // certainly not sent: tap again
        assertTrue(newChatAfter(send, SocketTimeoutException("timeout")).doubtful)
        assertTrue(newChatAfter(send, SendFailedException(SendFailure.OTHER, "create_chat HTTP 502", 502)).doubtful)
    }

    @Test fun a_doubtful_start_is_repeated_under_its_id_only_to_a_relay_that_keeps_ids() {
        val fresh = NewChatSend("k", "id-one-0001")
        val doubtful = fresh.copy(doubtful = true)
        assertEquals(NewChatPlan.SEND, newChatPlan(fresh, relayKeepsIds = null))
        assertEquals(NewChatPlan.SEND, newChatPlan(doubtful, relayKeepsIds = true))
        assertEquals(NewChatPlan.ASK_FIRST, newChatPlan(doubtful, relayKeepsIds = false))
        assertEquals(NewChatPlan.ASK_FIRST, newChatPlan(doubtful, relayKeepsIds = null))
    }

    @Test fun the_relay_not_knowing_what_became_of_the_start_means_asking_first() {
        val unsure = SendFailedException(SendFailure.OTHER, "create_chat HTTP 409", 409, relayCode = "send_outcome_unknown")
        assertTrue(newChatMustAsk(unsure))
        assertFalse(newChatMustAsk(SocketTimeoutException("timeout")))
        assertFalse(newChatMustAsk(ConnectException("refused")))
        assertTrue(NEW_CHAT_ANYWAY_WARNING.contains("twice"))
    }
}
