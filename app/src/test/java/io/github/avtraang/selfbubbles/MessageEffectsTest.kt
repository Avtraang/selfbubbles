package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iMessage effects reach the app (2026-10-10, parts 1a and 1b). A message sent
 * with Confetti or Invisible Ink looked like any other, and the composer could
 * ask for none. The relay now serves "effect" with every message (chat.db's
 * expressive_send_style_id, null for none) and takes one on /send, answering
 * "effect_sent" when one was asked for: true when BlueBubbles sent it, false
 * when a fallback engine delivered the words alone.
 *
 * These pin the app's side of that contract: the thirteen ids and their iOS
 * names, the caption under a bubble, which chats offer the picker, how an
 * effect travels with a text through the outbox, what a message parses to with
 * and without the key, and how the relay's answer is read. The JSON of the send
 * itself is pinned with the other send fields (SendIdTest).
 */
class MessageEffectsTest {
    private val slam = "com.apple.MobileSMS.expressivesend.impact"
    private val confetti = "com.apple.messages.effect.CKConfettiEffect"

    /** The contract's table, verbatim: Apple's id, iOS's name, where it plays. */
    private val contract = listOf(
        Triple("com.apple.MobileSMS.expressivesend.impact", "Slam", EffectKind.BUBBLE),
        Triple("com.apple.MobileSMS.expressivesend.loud", "Loud", EffectKind.BUBBLE),
        Triple("com.apple.MobileSMS.expressivesend.gentle", "Gentle", EffectKind.BUBBLE),
        Triple("com.apple.MobileSMS.expressivesend.invisibleink", "Invisible Ink", EffectKind.BUBBLE),
        Triple("com.apple.messages.effect.CKEchoEffect", "Echo", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKSpotlightEffect", "Spotlight", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKHappyBirthdayEffect", "Balloons", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKConfettiEffect", "Confetti", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKHeartEffect", "Love", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKLasersEffect", "Lasers", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKFireworksEffect", "Fireworks", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKShootingStarEffect", "Shooting Star", EffectKind.SCREEN),
        Triple("com.apple.messages.effect.CKSparklesEffect", "Celebration", EffectKind.SCREEN),
    )

    private fun msg(effect: String?, mine: Boolean = false, text: String? = "hello") =
        Msg(rowid = 1, guid = "g-1", text = text, is_from_me = mine, chat_guid = "iMessage;-;+15550100200", effect = effect)

    // ---- the catalogue ----

    @Test fun every_one_of_the_thirteen_ids_has_its_ios_name_and_kind() {
        assertEquals(13, contract.size)
        for ((id, name, kind) in contract) {
            val effect = MessageEffects.byId(id)
            assertEquals(id, name, effect?.name)
            assertEquals(id, kind, effect?.kind)
            assertEquals(id, name, MessageEffects.displayName(id))
        }
    }

    @Test fun the_picker_offers_the_bubble_effects_then_the_screen_effects_and_nothing_else() {
        assertEquals(contract.map { it.first }, MessageEffects.all.map { it.id })
        assertEquals(contract.filter { it.third == EffectKind.BUBBLE }.map { it.first }, MessageEffects.bubble.map { it.id })
        assertEquals(contract.filter { it.third == EffectKind.SCREEN }.map { it.first }, MessageEffects.screen.map { it.id })
        assertEquals(MessageEffects.all, MessageEffects.bubble + MessageEffects.screen)
        assertEquals("com.apple.MobileSMS.expressivesend.invisibleink", MessageEffects.INVISIBLE_INK)
    }

    @Test fun an_id_the_app_does_not_know_is_shown_as_an_effect() {
        assertNull(MessageEffects.byId("com.apple.messages.effect.CKNewInIOS30Effect"))
        assertEquals("Effect", MessageEffects.displayName("com.apple.messages.effect.CKNewInIOS30Effect"))
        assertEquals("Effect", MessageEffects.displayName(""))
        // Not case-insensitive: the id is Apple's spelling, and the relay passes it on as it is.
        assertEquals("Effect", MessageEffects.displayName(slam.uppercase()))
    }

    // ---- the caption under a bubble ----

    @Test fun the_caption_reads_as_ios_does() {
        assertEquals("Sent with Confetti", effectCaption(confetti))
        assertEquals("Sent with Slam", effectCaption(slam))
        assertEquals("Sent with Invisible Ink", effectCaption(MessageEffects.INVISIBLE_INK))
        assertEquals("Sent with Effect", effectCaption("com.apple.messages.effect.CKNewInIOS30Effect"))
    }

    @Test fun a_message_sent_without_an_effect_has_no_caption() {
        assertNull(effectCaption(null))
        assertNull(effectCaption(""))
        assertNull(effectCaption("   "))
    }

    @Test fun a_received_message_carries_the_caption_as_a_sent_one_does() {
        assertEquals("Sent with Confetti", effectCaptionFor(msg(confetti, mine = false)))
        assertEquals("Sent with Confetti", effectCaptionFor(msg(confetti, mine = true)))
        assertNull(effectCaptionFor(msg(null, mine = true)))
    }

    @Test fun no_caption_is_left_where_the_owners_own_bubble_was_unsent() {
        // After an Undo Send the Mac leaves the effect on the emptied row, as it leaves the edit date.
        assertNull(effectCaptionFor(msg(slam, mine = true, text = "")))
        assertNull(effectCaptionFor(msg(slam, mine = true, text = null)))
        // Someone else's emptied message keeps its caption: the one trace that something was there.
        assertEquals("Sent with Slam", effectCaptionFor(msg(slam, mine = false, text = "")))
    }

    // ---- what the relay serves ----

    @Test fun a_message_parses_with_and_without_the_key() {
        val with = json.decodeFromString<Msg>(
            """{"rowid":1,"guid":"g-1","chat_guid":"iMessage;-;+15550100200","service":"iMessage","effect":"$confetti"}""",
        )
        assertEquals(confetti, with.effect)
        // The relay's null, for a message sent without one.
        val nulled = json.decodeFromString<Msg>("""{"rowid":1,"guid":"g-1","chat_guid":"iMessage;-;+15550100200","effect":null}""")
        assertNull(nulled.effect)
        // An older relay's silence.
        val without = json.decodeFromString<Msg>("""{"rowid":1,"guid":"g-1","chat_guid":"iMessage;-;+15550100200"}""")
        assertNull(without.effect)
    }

    // ---- which chats offer the picker ----

    @Test fun the_picker_is_offered_in_imessage_chats_only() {
        fun thread(service: String? = null, network: String? = null, chatGuid: String = "any;-;+15550100200") =
            Thread(chat_guid = chatGuid, last_rowid = 1, service = service, network = network)
        assertTrue(offersEffects(thread()))
        assertTrue(offersEffects(thread(service = "iMessage")))
        // SMS and RCS on the Mac, and Google Messages, have no effects (the relay refuses them for the latter).
        assertFalse(offersEffects(thread(service = "SMS")))
        assertFalse(offersEffects(thread(service = "RCS")))
        assertFalse(offersEffects(thread(network = "gmessages")))
        // A Google Messages chat opened from a notification, a search hit or compose is a bare stub with no
        // labels yet (ChatVM.open gets Thread(chat_guid, chat_name, last_rowid = 0)); its "bp:" guid tells.
        assertFalse(offersEffects(thread(chatGuid = "bp:123")))
        assertFalse(offersEffects(Thread(chat_guid = "bp:123", chat_name = "Alice Anders", last_rowid = 0)))
        assertFalse(offersEffects(null))
    }

    // ---- the effect travels with the text ----

    @Test fun the_effect_is_stored_with_the_text_and_kept_by_every_send_of_it() {
        val entry = UnsentText(7, "iMessage;-;+15550100200", "Alice Anders", "synthetic text",
                               why = UnsentWhy.NO_ANSWER, createdAtMillis = 1_000, sendId = "a-send-id-0001", effect = slam)
        val stored = outboxEncode(listOf(entry))
        assertTrue(stored.contains("\"effect\":\"$slam\""))
        assertEquals(listOf(entry), outboxDecode(stored))
        // "Send again": the same message, the same effect. "Send anyway": a new id, still the same effect.
        assertEquals(slam, outboxRetry(listOf(entry), 7).single().effect)
        assertEquals(slam, outboxSendAnyway(listOf(entry.copy(why = UnsentWhy.RELAY_UNSURE)), 7, "a-new-id-0001").single().effect)
        // The process ended mid-send: the text comes back unconfirmed, with its effect.
        assertEquals(slam, outboxAfterRestart(listOf(entry.copy(why = null))).single().effect)
    }

    @Test fun a_text_without_an_effect_is_stored_as_it_always_was() {
        val entry = UnsentText(7, "iMessage;-;+15550100200", "Alice Anders", "synthetic text", why = UnsentWhy.NO_ANSWER, createdAtMillis = 1_000)
        assertFalse(outboxEncode(listOf(entry)).contains("effect"))
        val old = """[{"id":7,"chatGuid":"iMessage;-;+15550100200","chatTitle":"Alice Anders","text":"synthetic text","why":"NO_ANSWER","createdAtMillis":1000}]"""
        assertNull(outboxDecode(old).single().effect)
    }

    // ---- what the relay answers ----

    @Test fun the_relays_word_on_the_effect_is_read_from_its_answer() {
        assertEquals(SendReply("bb", true), sendReplyOf("""{"ok":true,"via":"bb","effect_sent":true}"""))
        assertEquals(SendReply("applescript", false), sendReplyOf("""{"ok":true,"via":"applescript","effect_sent":false}"""))
    }

    @Test fun an_answer_that_says_nothing_of_the_effect_means_nothing_to_say() {
        // No effect was asked for, or an older relay: the key is absent.
        assertEquals(SendReply("bb", null), sendReplyOf("""{"ok":true,"via":"bb"}"""))
        assertEquals(SendReply("gmessages", null), sendReplyOf("""{"ok":true,"via":"gmessages"}"""))
        assertEquals(SendReply("bb", null), sendReplyOf("""{"ok":true,"via":"bb","effect_sent":null}"""))
        // A BlueBubbles success without "via", as before; and a key of the wrong shape costs that key, not the path.
        assertEquals(SendReply("bb", null), sendReplyOf("""{"ok":true}"""))
        assertEquals(SendReply("applescript", null), sendReplyOf("""{"ok":true,"via":"applescript","effect_sent":"maybe"}"""))
    }

    @Test fun only_an_effect_the_relay_delivered_without_is_mentioned() {
        assertEquals("Sent without the effect", effectSendNotice(false))
        assertNull(effectSendNotice(true))
        assertNull(effectSendNotice(null))
        assertEquals(SENT_WITHOUT_EFFECT, effectSendNotice(false))
    }

    @Test fun a_send_that_ended_without_a_word_on_the_effect_carries_none() {
        // TextSendResult is built by Outbox from the relay's reply; a failed send has no reply and says nothing.
        val failed = TextSendResult("iMessage;-;+15550100200", "synthetic text", via = null, why = UnsentWhy.NO_ANSWER, sentAtMillis = 1_000)
        assertNull(failed.effectSent)
        assertNull(effectSendNotice(failed.effectSent))
    }
}
