package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Effects play (2026-10-10, round 1). A message sent with Slam, Loud or Gentle
 * read "Sent with Slam" under a bubble that did nothing, and Invisible Ink
 * showed its words in the clear. Now a bubble effect plays on its bubble, once,
 * by itself, the first time the message is on screen, and the caption line
 * offers Replay; Invisible Ink hides its words until a tap.
 *
 * These pin the rules of that engine (PlaybackRules, EffectPlayback.kt): which
 * message starts by itself (dated after the feature first ran on the phone,
 * not yet played, the owner's own as well), that the owner's "Remove
 * animations" (an animator duration scale of 0) stops everything including
 * Replay, that the memory of what has played stays bounded and keeps the
 * newest, and when the caption line carries Replay.
 */
class PlaybackRulesTest {
    private val slam = "com.apple.MobileSMS.expressivesend.impact"
    private val loud = "com.apple.MobileSMS.expressivesend.loud"
    private val confetti = "com.apple.messages.effect.CKConfettiEffect"
    private val ink = MessageEffects.INVISIBLE_INK

    /** The feature first ran at noon; messages are dated around it, in unix seconds like Msg.date. */
    private val since = 1_760_000_000.0

    private fun msg(effect: String?, date: Double? = since + 60, mine: Boolean = false, guid: String = "g-1") =
        Msg(rowid = 1, guid = guid, text = "hello", date = date, is_from_me = mine, chat_guid = "iMessage;-;+15550100200", effect = effect)

    // ---- reduce motion ----

    @Test fun animations_are_off_exactly_when_the_animator_scale_is_zero() {
        assertFalse(PlaybackRules.animationsOn(0f))
        assertTrue(PlaybackRules.animationsOn(1f))
        assertTrue(PlaybackRules.animationsOn(0.5f))
        assertTrue(PlaybackRules.animationsOn(1.5f))
        // Never seen from Settings.Global, but a scale below zero is not "animations on" either.
        assertFalse(PlaybackRules.animationsOn(-1f))
    }

    // ---- what plays by itself ----

    @Test fun a_message_that_arrived_after_the_feature_first_ran_plays_once_by_itself() {
        assertTrue(PlaybackRules.autoPlays(msg(slam), since, emptySet(), 1f))
        assertTrue(PlaybackRules.autoPlays(msg(loud), since, emptySet(), 1f))
        assertTrue(PlaybackRules.autoPlays(msg(confetti), since, emptySet(), 1f))
        // Dated exactly at the stamp counts as after it.
        assertTrue(PlaybackRules.autoPlays(msg(slam, date = since), since, emptySet(), 1f))
    }

    @Test fun a_message_from_before_the_feature_first_ran_never_plays_by_itself() {
        // A fresh install scrolling through years of history must not fire every old effect.
        assertFalse(PlaybackRules.autoPlays(msg(slam, date = since - 1), since, emptySet(), 1f))
        assertFalse(PlaybackRules.autoPlays(msg(slam, date = since - 86_400 * 365), since, emptySet(), 1f))
        // A message with no date cannot be told from history, and is left alone.
        assertFalse(PlaybackRules.autoPlays(msg(slam, date = null), since, emptySet(), 1f))
    }

    @Test fun a_message_that_has_played_does_not_play_again_by_itself() {
        assertFalse(PlaybackRules.autoPlays(msg(slam, guid = "g-7"), since, setOf("g-7"), 1f))
        assertTrue(PlaybackRules.autoPlays(msg(slam, guid = "g-7"), since, setOf("g-8"), 1f))
    }

    @Test fun the_owners_own_message_plays_as_a_received_one_does() {
        // iOS plays a sent effect for the sender too.
        assertTrue(PlaybackRules.autoPlays(msg(slam, mine = true), since, emptySet(), 1f))
        assertFalse(PlaybackRules.autoPlays(msg(slam, mine = true, date = since - 1), since, emptySet(), 1f))
    }

    @Test fun nothing_plays_by_itself_with_animations_off() {
        assertFalse(PlaybackRules.autoPlays(msg(slam), since, emptySet(), 0f))
        assertFalse(PlaybackRules.autoPlays(msg(confetti), since, emptySet(), 0f))
    }

    @Test fun a_message_without_an_effect_the_app_can_play_starts_nothing() {
        assertFalse(PlaybackRules.autoPlays(msg(null), since, emptySet(), 1f))
        assertFalse(PlaybackRules.autoPlays(msg("com.apple.messages.effect.CKNewInIOS30Effect"), since, emptySet(), 1f))
        // Invisible Ink's play is the concealment on its bubble: there is nothing to start.
        assertFalse(PlaybackRules.autoPlays(msg(ink), since, emptySet(), 1f))
    }

    // ---- the memory of what has played ----

    @Test fun a_played_guid_is_added_newest_last() {
        assertEquals(listOf("a"), PlaybackRules.rememberPlayed(emptyList(), "a"))
        assertEquals(listOf("a", "b"), PlaybackRules.rememberPlayed(listOf("a"), "b"))
    }

    @Test fun a_guid_played_again_moves_to_the_newest_place_without_a_double() {
        assertEquals(listOf("b", "c", "a"), PlaybackRules.rememberPlayed(listOf("a", "b", "c"), "a"))
    }

    @Test fun the_memory_stays_bounded_and_keeps_the_newest() {
        assertEquals(2000, PlaybackRules.PLAYED_CAP)
        val full = (1..2000).map { "g-$it" }
        val next = PlaybackRules.rememberPlayed(full, "g-2001")
        assertEquals(2000, next.size)
        assertEquals("g-2", next.first())
        assertEquals("g-2001", next.last())
        assertFalse("g-1" in next)
        // A smaller cap, to see several dropped at once from an oversized list.
        assertEquals(listOf("d", "e", "f"), PlaybackRules.rememberPlayed(listOf("a", "b", "c", "d", "e"), "f", cap = 3))
    }

    @Test fun the_memory_is_stored_one_guid_per_line_in_order() {
        val played = listOf("g-1", "p:0/ABC-DEF", "g-3")
        assertEquals("g-1\np:0/ABC-DEF\ng-3", playedEncode(played))
        assertEquals(played, playedDecode(playedEncode(played)))
        assertEquals(emptyList<String>(), playedDecode(null))
        assertEquals(emptyList<String>(), playedDecode(""))
        assertEquals("", playedEncode(emptyList()))
    }

    // ---- the caption line: "Sent with Slam · Replay" ----

    @Test fun replay_is_offered_for_a_known_effect_other_than_invisible_ink_with_animations_on() {
        assertTrue(PlaybackRules.showsReplay(slam, animationsOn = true))
        assertTrue(PlaybackRules.showsReplay(loud, animationsOn = true))
        // A screen effect gets Replay too: round 2 paints it through the same call.
        assertTrue(PlaybackRules.showsReplay(confetti, animationsOn = true))
    }

    @Test fun no_replay_for_invisible_ink_or_an_unknown_effect_or_no_effect() {
        // A tap on the bubble is Invisible Ink's replay.
        assertFalse(PlaybackRules.showsReplay(ink, animationsOn = true))
        assertFalse(PlaybackRules.showsReplay("com.apple.messages.effect.CKNewInIOS30Effect", animationsOn = true))
        assertFalse(PlaybackRules.showsReplay(null, animationsOn = true))
        assertFalse(PlaybackRules.showsReplay("", animationsOn = true))
    }

    @Test fun no_replay_with_animations_off_or_while_the_effect_is_playing() {
        assertFalse(PlaybackRules.showsReplay(slam, animationsOn = PlaybackRules.animationsOn(0f)))
        assertFalse(PlaybackRules.showsReplay(slam, animationsOn = true, playing = true))
        assertTrue(PlaybackRules.showsReplay(slam, animationsOn = PlaybackRules.animationsOn(1f), playing = false))
    }

    @Test fun talkback_hears_what_an_invisible_ink_bubble_is_not_its_words() {
        assertEquals("Invisible Ink message, tap to reveal", INVISIBLE_INK_DESCRIPTION)
    }
}
