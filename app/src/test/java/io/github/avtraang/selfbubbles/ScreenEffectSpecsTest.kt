package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen effects play (2026-10-10, round 2). Round 1 recorded a Confetti
 * or Lasers play on the controller and drew nothing; now each of the nine has a
 * painter over the chat (ScreenEffectPainters.kt) that follows a spec and a
 * particle field laid out once per play (ScreenEffectSpecs.kt).
 *
 * These pin the plain-Kotlin side of that: that every screen id in the
 * catalogue has a spec and no bubble id does (so a new id cannot go
 * unpainted), that each play lasts between two and five seconds and allocates
 * a sane number of particles, which three dim the chat; the rule that clears a
 * finished play and leaves a restarted one alone; that Confetti's hold outlasts
 * the shower Konfetti draws; and the maths of the motion,
 * deterministic for a seed: lasers that fan upward from their origin and never
 * swing past their share of the fan, balloons that start below the screen and
 * end above it, fireworks that burst in the upper screen and fade in time, a
 * star that crosses from one side to the other, a spotlight that closes onto
 * the bubble and lifts, and Echo's copies that rain from above the top to
 * below the bottom. And the two round-1 leftovers: which messages Invisible
 * Ink has anything to hide on, and what counts as a bubble's words.
 */
class ScreenEffectSpecsTest {
    private val screenIds = MessageEffects.screen.map { it.id }

    private fun msg(text: String? = "hello", mine: Boolean = false, guid: String = "g-1", link: LinkPreview? = null, reply: ReplyRef? = null, attachments: List<Att> = emptyList()) =
        Msg(rowid = 1, guid = guid, text = text, is_from_me = mine, chat_guid = "iMessage;-;+15550100200", link = link, reply_to = reply, attachments = attachments)

    // ---- the catalogue ----

    @Test fun every_screen_effect_in_the_catalogue_has_a_spec_and_no_bubble_effect_does() {
        // Exhaustive both ways: a screen id added to MessageEffects without a painter, or a spec for an id the
        // catalogue lacks, fails here.
        assertEquals(screenIds.toSet(), ScreenEffectSpecs.all.map { it.id }.toSet())
        assertEquals(9, ScreenEffectSpecs.all.size)
        assertEquals(ScreenEffectSpecs.all.size, ScreenEffectSpecs.all.map { it.id }.distinct().size)
        for (id in screenIds) assertEquals(id, id, ScreenEffectSpecs.spec(id)?.id)
        for (e in MessageEffects.bubble) assertNull(e.id, ScreenEffectSpecs.spec(e.id))
        assertNull(ScreenEffectSpecs.spec("com.apple.messages.effect.CKNoSuchEffect"))
    }

    @Test fun each_effects_own_id_is_the_catalogues() {
        assertEquals("Confetti", MessageEffects.displayName(ConfettiSpec.ID))
        assertEquals("Celebration", MessageEffects.displayName(CelebrationSpec.ID))
        assertEquals("Balloons", MessageEffects.displayName(BalloonsSpec.ID))
        assertEquals("Love", MessageEffects.displayName(LoveSpec.ID))
        assertEquals("Lasers", MessageEffects.displayName(LasersSpec.ID))
        assertEquals("Fireworks", MessageEffects.displayName(FireworksSpec.ID))
        assertEquals("Shooting Star", MessageEffects.displayName(ShootingStarSpec.ID))
        assertEquals("Spotlight", MessageEffects.displayName(SpotlightSpec.ID))
        assertEquals("Echo", MessageEffects.displayName(EchoSpec.ID))
    }

    @Test fun every_screen_effect_lasts_between_two_and_five_seconds() {
        for (spec in ScreenEffectSpecs.all) assertTrue("${spec.id}: ${spec.durationMillis} ms", spec.durationMillis in 2000..5000)
        // The ones the task names: about 3 s, Balloons and Fireworks about 4, the star and the spotlight about 2.5.
        assertTrue(BalloonsSpec.spec.durationMillis in 3500..4500)
        assertTrue(FireworksSpec.spec.durationMillis in 3500..4500)
        assertTrue(ShootingStarSpec.spec.durationMillis in 2000..3000)
        assertTrue(SpotlightSpec.spec.durationMillis in 2000..3000)
    }

    @Test fun particle_counts_are_sane_and_the_fields_allocate_exactly_them() {
        for (spec in ScreenEffectSpecs.all) assertTrue("${spec.id}: ${spec.particles}", spec.particles in 0..400)
        assertEquals(CelebrationSpec.spec.particles, CelebrationField(1L).count)
        assertEquals(BalloonsSpec.spec.particles, BalloonField(1L).count)
        assertEquals(LoveSpec.spec.particles, LoveField(1L).count)
        assertEquals(LasersSpec.spec.particles, LaserFan(1L).count)
        assertEquals(FireworksSpec.spec.particles, FireworksField(1L).count)
        assertEquals(ShootingStarSpec.spec.particles, StarTail(1L).count)
        assertEquals(EchoSpec.spec.particles, EchoField(1L).count)
        // About 25 balloons, 6 to 10 beams, 5 to 7 bursts, as asked.
        assertTrue(BalloonsSpec.COUNT in 20..30)
        assertTrue(LasersSpec.BEAMS in 6..10)
        assertTrue(FireworksSpec.BURSTS in 5..7)
        // Spotlight has no particles; its state is tracks.
        assertEquals(0, SpotlightSpec.spec.particles)
    }

    @Test fun lasers_fireworks_and_spotlight_dim_the_chat_and_the_others_do_not() {
        assertEquals(setOf(LasersSpec.ID, FireworksSpec.ID, SpotlightSpec.ID), ScreenEffectSpecs.all.filter { it.dims }.map { it.id }.toSet())
        for (spec in ScreenEffectSpecs.all.filter { it.dims }) {
            val alpha = when (spec.id) {
                LasersSpec.ID -> LasersSpec.DIM_ALPHA
                FireworksSpec.ID -> FireworksSpec.DIM_ALPHA
                else -> SpotlightSpec.DIM_ALPHA
            }
            assertTrue("${spec.id} dims to $alpha", alpha in 0.4f..0.85f)
        }
        // The dimming is lifted by the end of each: no dark screen left behind.
        assertEquals(0f, LasersSpec.fade.valueAt(LasersSpec.spec.durationMillis.toLong()), 0f)
        assertEquals(0f, FireworksSpec.dim.valueAt(FireworksSpec.spec.durationMillis.toLong()), 0f)
        assertEquals(0f, SpotlightSpec.dim.valueAt(SpotlightSpec.spec.durationMillis.toLong()), 0f)
    }

    @Test fun the_palettes_are_opaque_rgb_and_not_empty() {
        for (palette in listOf(EffectPalette.party, EffectPalette.gold, EffectPalette.pastel, EffectPalette.neon)) {
            assertTrue(palette.size >= 4)
            for (c in palette) assertTrue("%06x".format(c), c in 0..0xFFFFFF)
        }
    }

    // ---- play -> done -> cleared, and a restart ----

    @Test fun a_finished_play_clears_the_overlay_and_an_older_one_finishing_leaves_a_restart_alone() {
        val first = EffectPlay("g-1", ConfettiSpec.ID, serial = 1)
        // Playing, then done: cleared.
        assertNull(ScreenPlayRules.afterDone(first, first))
        // Nothing held (already cleared, or never started with animations off): stays nothing.
        assertNull(ScreenPlayRules.afterDone(null, first))
        // A Replay while the first runs: a new serial on the same message. The first painter's done must not clear it.
        val replay = first.copy(serial = 2)
        assertNotEquals(first, replay)
        assertSame(replay, ScreenPlayRules.afterDone(replay, first))
        // ...and the replay's own done clears it.
        assertNull(ScreenPlayRules.afterDone(replay, replay))
        // Another message's effect while the first runs: the same.
        val other = EffectPlay("g-2", LasersSpec.ID, serial = 3)
        assertSame(other, ScreenPlayRules.afterDone(other, first))
    }

    @Test fun the_painters_restart_exactly_when_the_serial_changes() {
        val first = EffectPlay("g-1", ConfettiSpec.ID, serial = 1)
        assertTrue(ScreenPlayRules.restarts(null, first))
        assertTrue(ScreenPlayRules.restarts(first, first.copy(serial = 2)))
        assertTrue(ScreenPlayRules.restarts(first, EffectPlay("g-2", LasersSpec.ID, serial = 2)))
        // The same play recomposed is not a restart; a play ending is not one either.
        assertFalse(ScreenPlayRules.restarts(first, first))
        assertFalse(ScreenPlayRules.restarts(first, null))
        assertFalse(ScreenPlayRules.restarts(null, null))
    }

    @Test fun a_play_carries_the_words_and_whose_the_message_is_for_echo_and_love() {
        val play = EffectPlay("g-1", EchoSpec.ID, serial = 1, words = "see you at 8", mine = true)
        assertEquals("see you at 8", play.words)
        assertTrue(play.mine)
        // Defaults for a play that needs neither (a bubble effect): no words, not the owner's.
        val slam = EffectPlay("g-1", EffectSpecs.slam.id, serial = 2)
        assertNull(slam.words)
        assertFalse(slam.mine)
    }

    // ---- Confetti ----

    @Test fun confetti_is_held_until_its_last_paper_has_faded_and_the_view_goes_out_with_it() {
        // Konfetti starts a piece's fade only when its time to live is up, so the last piece emitted is on
        // screen until EMIT + TIME_TO_LIVE + its fade. A shorter hold cut the shower off mid-air, opaque.
        val shower = ConfettiSpec.EMIT_MILLIS + ConfettiSpec.TIME_TO_LIVE_MILLIS.toInt() + ConfettiSpec.FADE_MILLIS
        assertTrue(ConfettiSpec.spec.durationMillis >= shower)
        // The library fades alpha 255 by 5 a frame: 51 frames, 850 ms at 60 Hz. Allow at least that.
        assertTrue(ConfettiSpec.FADE_MILLIS >= 51 * 1000 / 60)
        // Paper keeps coming for about a second and a half, and the hold stays inside the five-second bound.
        assertTrue(ConfettiSpec.EMIT_MILLIS in 1000..2000)
        assertTrue(ConfettiSpec.spec.durationMillis <= 5000)
        // The view is whole through the shower and gone when the overlay is, so nothing vanishes in one frame.
        assertEquals(1f, ConfettiSpec.exit.valueAt(0L), 0f)
        assertEquals(1f, ConfettiSpec.exit.valueAt((ConfettiSpec.spec.durationMillis - ConfettiSpec.EXIT_MILLIS).toLong()), 0f)
        assertTrue(ConfettiSpec.exit.valueAt((ConfettiSpec.spec.durationMillis - ConfettiSpec.EXIT_MILLIS / 2).toLong()) in 0.3f..0.7f)
        assertEquals(0f, ConfettiSpec.exit.valueAt(ConfettiSpec.spec.durationMillis.toLong()), 0f)
        assertEquals(ConfettiSpec.spec.durationMillis, ConfettiSpec.exit.durationMillis)
    }

    // ---- Lasers ----

    @Test fun lasers_fan_upward_from_their_origin_and_swing_within_their_share_of_the_fan() {
        val fan = LaserFan(seed = 42L)
        for (i in 0 until fan.count) {
            val rest = LasersSpec.restDegrees(i)
            for (millis in 0L..LasersSpec.spec.durationMillis step 50) {
                val deg = fan.degrees(i, millis)
                assertTrue("beam $i at $millis: $deg", kotlin.math.abs(deg - rest) <= LasersSpec.SWEEP_DEGREES + 1e-3f)
                // Upward: the far end is above the origin (screen y grows downward).
                assertTrue("beam $i points up at $millis", fan.endY(i, millis, 1000f, 1000f, 1600f) < 1000f)
            }
        }
        // The fan is spread evenly around straight up and its ends are FAN apart.
        assertEquals(-90f - LasersSpec.FAN_DEGREES / 2, LasersSpec.restDegrees(0), 1e-4f)
        assertEquals(-90f + LasersSpec.FAN_DEGREES / 2, LasersSpec.restDegrees(LasersSpec.BEAMS - 1), 1e-4f)
        // Long enough to leave any screen: the diagonal.
        assertEquals(2000f, fan.length(1200f, 1600f), 1e-3f)
    }

    @Test fun the_same_seed_lays_out_the_same_lasers_and_another_seed_does_not() {
        val a = LaserFan(7L)
        val b = LaserFan(7L)
        val other = LaserFan(8L)
        for (i in 0 until a.count) {
            assertEquals(a.degrees(i, 1234L), b.degrees(i, 1234L), 0f)
            assertEquals(a.color(i), b.color(i))
        }
        assertTrue((0 until a.count).any { a.degrees(it, 1234L) != other.degrees(it, 1234L) })
        // The beams do not all swing together: at least two are at different points of their sine.
        assertTrue((0 until a.count).map { a.degrees(it, 0L) - LasersSpec.restDegrees(it) }.distinct().size >= 2)
    }

    // ---- Balloons ----

    @Test fun every_balloon_starts_wholly_below_the_screen_and_ends_wholly_above_it_in_time() {
        val field = BalloonField(seed = 3L)
        val w = 1080f
        val h = 2400f
        val px = 2.625f
        for (i in 0 until field.count) {
            val tail = BalloonsSpec.tailDp(field.radiusDp(i)) * px
            // Before its start: below the bottom edge, oval and string.
            assertTrue(field.y(i, 0L, h, px) - tail >= h - 1e-3f)
            assertTrue(field.y(i, field.startMillis(i).toLong(), h, px) - tail >= h - 1e-3f)
            // At the end of the play: above the top edge, oval and string.
            assertTrue("balloon $i done in time", field.startMillis(i) + field.travelMillis(i) <= BalloonsSpec.spec.durationMillis)
            assertTrue(field.y(i, BalloonsSpec.spec.durationMillis.toLong(), h, px) + tail <= 1e-3f)
            // Rising all the way: y only decreases.
            var last = Float.MAX_VALUE
            for (millis in 0L..BalloonsSpec.spec.durationMillis step 40) {
                val y = field.y(i, millis, h, px)
                assertTrue(y <= last + 1e-3f)
                last = y
            }
            // Swaying within the width, string and oval included.
            for (millis in 0L..BalloonsSpec.spec.durationMillis step 40) {
                val x = field.x(i, millis, w, px)
                assertTrue("x $x", x >= 0f && x <= w)
            }
            assertTrue(field.radiusDp(i) in BalloonsSpec.RADIUS_MIN_DP..BalloonsSpec.RADIUS_MAX_DP)
            assertTrue(field.color(i) in EffectPalette.pastel)
        }
        // Each with its own sway and speed: not all the same start, travel or lane.
        assertTrue((0 until field.count).map { field.startMillis(it) }.distinct().size > 1)
        assertTrue((0 until field.count).map { field.travelMillis(it) }.distinct().size > 1)
        assertTrue((0 until field.count).map { field.x(it, 0L, w, px) }.distinct().size > 1)
    }

    @Test fun the_same_seed_lays_out_the_same_balloons() {
        val a = BalloonField(11L)
        val b = BalloonField(11L)
        val other = BalloonField(12L)
        for (i in 0 until a.count) {
            assertEquals(a.x(i, 900L, 1000f, 1f), b.x(i, 900L, 1000f, 1f), 0f)
            assertEquals(a.y(i, 900L, 2000f, 1f), b.y(i, 900L, 2000f, 1f), 0f)
            assertEquals(a.startMillis(i), b.startMillis(i))
        }
        assertTrue((0 until a.count).any { a.startMillis(it) != other.startMillis(it) })
    }

    // ---- Fireworks ----

    @Test fun fireworks_burst_in_the_upper_screen_in_turn_and_every_spark_is_out_before_the_end() {
        val field = FireworksField(seed = 5L)
        val w = 1080f
        val h = 2400f
        var lastAt = -1
        for (b in 0 until field.bursts) {
            assertTrue(field.burstX(b, w) in w * FireworksSpec.X_MIN..w * FireworksSpec.X_MAX)
            assertTrue(field.burstY(b, h) in h * FireworksSpec.Y_MIN..h * FireworksSpec.Y_MAX)
            assertTrue("in turn", field.burstAtMillis(b) > lastAt)
            lastAt = field.burstAtMillis(b)
            assertTrue("sparks of burst $b fade in time", field.burstAtMillis(b) + FireworksSpec.SPARK_LIFE_MILLIS <= FireworksSpec.spec.durationMillis)
            // The flash: none before the burst, full at it, gone soon after.
            assertEquals(0f, field.flash(b, field.burstAtMillis(b) - 1L), 0f)
            assertEquals(1f, field.flash(b, field.burstAtMillis(b).toLong()), 0f)
            assertEquals(0f, field.flash(b, (field.burstAtMillis(b) + FireworksSpec.FLASH_MILLIS).toLong()), 0f)
        }
        for (i in 0 until field.count) {
            val at = field.burstAtMillis(field.burstOf(i))
            assertFalse(field.alive(i, at - 1L))
            assertTrue(field.alive(i, at.toLong()))
            assertFalse(field.alive(i, (at + FireworksSpec.SPARK_LIFE_MILLIS).toLong()))
            // At the burst the spark is at the shell; later it has moved, and fallen under gravity more than drag alone would have it.
            assertEquals(field.burstX(field.burstOf(i), w), field.x(i, at.toLong(), w, 1f), 1e-3f)
            assertEquals(field.burstY(field.burstOf(i), h), field.y(i, at.toLong(), h, 1f), 1e-3f)
            assertEquals(1f, field.alpha(i, at.toLong()), 0f)
            assertEquals(0f, field.alpha(i, (at + FireworksSpec.SPARK_LIFE_MILLIS).toLong()), 1e-5f)
        }
        // Drag: a spark never flies further than v/k, and flies most of that in the first second.
        val cap = FireworksSpec.SPEED_MAX_DP_PER_S / FireworksSpec.DRAG_PER_S
        assertTrue(FireworksSpec.flown(FireworksSpec.SPEED_MAX_DP_PER_S, 100f) <= cap + 1e-3f)
        assertTrue(FireworksSpec.flown(FireworksSpec.SPEED_MAX_DP_PER_S, 1f) > cap * 0.7f)
        assertEquals(0f, FireworksSpec.flown(200f, 0f), 0f)
    }

    // ---- Celebration ----

    @Test fun celebration_bursts_from_both_top_corners_into_the_screen_and_fades() {
        val field = CelebrationField(seed = 9L)
        val w = 1080f
        var left = 0
        var right = 0
        for (i in 0 until field.count) {
            val born = field.bornMillis(i)
            assertTrue(born + CelebrationSpec.SPARK_LIFE_MILLIS <= CelebrationSpec.spec.durationMillis)
            assertFalse(field.alive(i, born - 1L))
            assertTrue(field.alive(i, born.toLong()))
            // Born at its corner, at the top edge...
            val x0 = field.x(i, born.toLong(), w, 1f)
            assertEquals(if (field.fromRight(i)) w else 0f, x0, 1e-3f)
            assertEquals(0f, field.y(i, born.toLong(), 1f), 1e-3f)
            // ...then into the screen: the left corner's sparks move right, the right corner's left, all downward.
            val x1 = field.x(i, born + 300L, w, 1f)
            if (field.fromRight(i)) { right++; assertTrue(x1 < x0) } else { left++; assertTrue(x1 > x0) }
            assertTrue(field.y(i, born + 300L, 1f) > 0f)
            // Bright when born (the twinkle alone takes it as low as 0.3), gone at the end of its life.
            assertTrue(field.alpha(i, born.toLong()) > 0.25f)
            assertEquals(0f, field.alpha(i, (born + CelebrationSpec.SPARK_LIFE_MILLIS).toLong()), 1e-5f)
            assertTrue(field.color(i) in EffectPalette.gold)
        }
        assertEquals(left, right)
        assertTrue(left > 0)
        // The corners alternate: the right corner fires after the left each round.
        assertEquals(CelebrationSpec.CORNER_OFFSET_MILLIS, field.bornMillis(CelebrationSpec.SPARKS_PER_BURST) - field.bornMillis(0))
    }

    // ---- Shooting Star ----

    @Test fun the_star_crosses_from_outside_one_top_corner_to_outside_the_far_side_lower_down_and_its_tail_follows() {
        val w = 1080f
        val h = 2400f
        for (fromLeft in listOf(true, false)) {
            val x0 = ShootingStarSpec.headX(0L, w, fromLeft)
            val x1 = ShootingStarSpec.headX(ShootingStarSpec.FLIGHT_MILLIS.toLong(), w, fromLeft)
            if (fromLeft) { assertTrue(x0 < 0f); assertTrue(x1 > w) } else { assertTrue(x0 > w); assertTrue(x1 < 0f) }
            assertEquals(x0, ShootingStarSpec.headX(-100L, w, fromLeft), 0f)   // held at the start before 0
            assertEquals(x1, ShootingStarSpec.headX(9000L, w, fromLeft), 0f)   // and at the end after
        }
        assertTrue(ShootingStarSpec.headY(0L, h) < ShootingStarSpec.headY(ShootingStarSpec.FLIGHT_MILLIS.toLong(), h))
        assertTrue(ShootingStarSpec.headY(ShootingStarSpec.FLIGHT_MILLIS.toLong(), h) < h / 2f)
        // The tail: behind the head along its path (where the head was), and gone by the end.
        val tail = StarTail(seed = 2L)
        val millis = 800L
        val headX = ShootingStarSpec.headX(millis, w, tail.fromLeft)
        for (i in 0 until tail.count) {
            val x = tail.x(i, millis, w, 1f)
            if (tail.fromLeft) assertTrue("spark $i behind", x < headX) else assertTrue("spark $i behind", x > headX)
            assertTrue(tail.alpha(i, millis) > 0f)
            assertEquals(0f, tail.alpha(i, ShootingStarSpec.spec.durationMillis.toLong()), 1e-5f)
            assertEquals(0f, tail.alpha(i, 0L), 0f)   // nothing trails a head that has not moved yet
        }
        // Which corner is the seed's: both happen.
        assertTrue((0L..40L).map { StarTail(it).fromLeft }.distinct().size == 2)
        assertEquals(StarTail(4L).fromLeft, StarTail(4L).fromLeft)
    }

    // ---- Spotlight ----

    @Test fun the_spotlight_closes_from_the_whole_screen_onto_the_bubble_holds_and_lifts() {
        val screenR = 1300f
        val bubbleR = SpotlightSpec.bubbleRadius(600f, 120f, 2.625f)
        assertTrue(bubbleR > kotlin.math.hypot(600f, 120f) / 2f)
        assertEquals(screenR, SpotlightSpec.radius(0L, screenR, bubbleR), 0f)
        assertEquals(bubbleR, SpotlightSpec.radius(SpotlightSpec.FIND_MILLIS.toLong(), screenR, bubbleR), 1e-3f)
        assertEquals(bubbleR, SpotlightSpec.radius(SpotlightSpec.HOLD_UNTIL_MILLIS.toLong(), screenR, bubbleR), 1e-3f)
        // Closing all the way: the radius only shrinks.
        var last = Float.MAX_VALUE
        for (millis in 0L..SpotlightSpec.FIND_MILLIS step 20) {
            val r = SpotlightSpec.radius(millis, screenR, bubbleR)
            assertTrue(r <= last + 1e-3f)
            last = r
        }
        // The centre travels from the screen's to the bubble's.
        assertEquals(500f, SpotlightSpec.centre(0L, 500f, 820f), 0f)
        assertEquals(820f, SpotlightSpec.centre(SpotlightSpec.FIND_MILLIS.toLong(), 500f, 820f), 1e-3f)
        // Dimming: up quickly, full through the hold, lifted by the end.
        assertEquals(0f, SpotlightSpec.dim.valueAt(0L), 0f)
        assertEquals(1f, SpotlightSpec.dim.valueAt(SpotlightSpec.FIND_MILLIS.toLong()), 0f)
        assertEquals(1f, SpotlightSpec.dim.valueAt(SpotlightSpec.HOLD_UNTIL_MILLIS.toLong()), 0f)
        assertEquals(0f, SpotlightSpec.dim.valueAt(SpotlightSpec.spec.durationMillis.toLong()), 0f)
        assertTrue(SpotlightSpec.SOFT_EDGE in 0.5f..0.95f)
    }

    // ---- Love ----

    @Test fun the_big_heart_grows_from_nothing_past_full_size_floats_up_and_fades_while_small_ones_drift_around_it() {
        assertEquals(0f, LoveSpec.bigScale.start, 0f)
        assertEquals(1f, LoveSpec.bigScale.end, 0f)
        assertTrue("overshoots", (0..LoveSpec.bigScale.durationMillis).maxOf { LoveSpec.bigScale.valueAt(it.toLong()) } > 1.05f)
        assertEquals(0f, LoveSpec.bigRise.start, 0f)
        assertEquals(1f, LoveSpec.bigRise.valueAt(LoveSpec.spec.durationMillis.toLong()), 0f)
        assertEquals(1f, LoveSpec.bigAlpha.valueAt(1000L), 0f)
        assertEquals(0f, LoveSpec.bigAlpha.valueAt(LoveSpec.spec.durationMillis.toLong()), 0f)
        val field = LoveField(seed = 6L)
        val cx = 700f
        val cy = 1800f
        var leftOf = 0
        for (i in 0 until field.count) {
            val start = field.startMillis(i)
            assertTrue(start in LoveSpec.SMALL_START_MIN_MILLIS..LoveSpec.SMALL_START_MAX_MILLIS)
            assertEquals(0f, field.alpha(i, start - 1L), 0f)
            assertTrue(field.alpha(i, start + 200L) > 0.4f)
            assertEquals(0f, field.alpha(i, LoveSpec.spec.durationMillis.toLong()), 1e-5f)
            // Starts at the bubble's height and rises from there.
            assertEquals(cy, field.y(i, start.toLong(), cy, 1f), 1e-3f)
            assertTrue(field.y(i, LoveSpec.spec.durationMillis.toLong(), cy, 1f) < cy - LoveSpec.SMALL_RISE_MIN_DP + 1e-3f)
            if (field.x(i, start.toLong(), cx, 1f) < cx) leftOf++
            assertTrue(field.sizeDp(i) in LoveSpec.SMALL_SIZE_MIN_DP..LoveSpec.SMALL_SIZE_MAX_DP)
        }
        // On both sides of the bubble.
        assertTrue(leftOf in 1 until field.count)
    }

    // ---- Echo ----

    @Test fun echos_copies_rain_from_above_the_top_to_below_the_bottom_on_their_own_lanes_and_fade_together() {
        val field = EchoField(seed = 8L)
        val w = 1080f
        val h = 2400f
        val copyH = 90f
        for (i in 0 until field.count) {
            val start = field.startMillis(i)
            assertTrue(start in 0..EchoSpec.START_SPREAD_MILLIS)
            assertTrue(start + field.fallMillis(i) <= EchoSpec.spec.durationMillis)
            assertFalse(field.started(i, start - 1L))
            assertTrue(field.started(i, start.toLong()))
            // Wholly above the top at its start, wholly below the bottom when its fall is done.
            assertTrue(field.y(i, start.toLong(), h, copyH) + copyH / 2f <= 1e-3f)
            assertTrue(field.y(i, (start + field.fallMillis(i)).toLong(), h, copyH) - copyH / 2f >= h - 1e-3f)
            assertTrue(field.scale(i) in EchoSpec.SCALE_MIN..EchoSpec.SCALE_MAX)
            assertTrue(kotlin.math.abs(field.tiltDegrees(i)) <= EchoSpec.TILT_DEGREES)
            for (millis in 0L..EchoSpec.spec.durationMillis step 100) {
                val x = field.x(i, millis, w, 1f)
                assertTrue("x $x", x > 0f && x < w)
            }
        }
        assertTrue((0 until field.count).map { field.x(it, 0L, w, 1f) }.distinct().size > field.count / 2)
        // All fade together over the last stretch.
        assertEquals(1f, EchoSpec.fade(0L), 0f)
        assertEquals(1f, EchoSpec.fade(EchoSpec.FADE_FROM_MILLIS.toLong()), 0f)
        assertEquals(0f, EchoSpec.fade(EchoSpec.spec.durationMillis.toLong()), 0f)
        assertTrue(EchoSpec.fade((EchoSpec.FADE_FROM_MILLIS + EchoSpec.spec.durationMillis).toLong() / 2) in 0.3f..0.7f)
    }

    @Test fun an_echo_copy_shows_the_first_line_of_the_words_cut_to_one_small_bubble_and_nothing_for_no_words() {
        assertEquals("see you at 8", EchoSpec.copyText("see you at 8"))
        assertEquals("first line", EchoSpec.copyText("  first line\nsecond line"))
        assertEquals("second", EchoSpec.copyText("\n\n  second  \nthird"))
        val long = "a".repeat(EchoSpec.MAX_CHARS + 20)
        val cut = EchoSpec.copyText(long)!!
        assertEquals(EchoSpec.MAX_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("a".repeat(EchoSpec.MAX_CHARS), EchoSpec.copyText("a".repeat(EchoSpec.MAX_CHARS)))
        assertNull(EchoSpec.copyText(null))
        assertNull(EchoSpec.copyText(""))
        assertNull(EchoSpec.copyText("  \n  "))
    }

    // ---- the round-1 leftovers: a bubble's words, and what Invisible Ink has to hide ----

    @Test fun a_bubbles_words_are_its_text_without_attachment_marks_and_not_a_link_cards_own_url() {
        assertEquals("hello", bubbleBody(msg("hello")))
        assertEquals("hello", bubbleBody(msg("\uFFFC hello \uFFFC")))
        assertNull(bubbleBody(msg(null)))
        assertNull(bubbleBody(msg("")))
        assertNull(bubbleBody(msg("\uFFFC")))
        val card = LinkPreview(url = "https://example.com/page?variant=139407", title = "Page")
        // Only the URL, with or without the query string and a trailing slash: the card shows it.
        assertNull(bubbleBody(msg("https://example.com/page", link = card)))
        assertNull(bubbleBody(msg("HTTPS://example.com/page/", link = card)))
        // Words beside the link stay.
        assertEquals("look https://example.com/page", bubbleBody(msg("look https://example.com/page", link = card)))
    }

    @Test fun invisible_ink_has_something_to_hide_on_words_or_a_quote_and_nothing_on_attachments_alone() {
        val photo = Att(guid = "a-1", mime_type = "image/jpeg", name = "IMG.jpg", url = "/att/a-1")
        assertTrue(InvisibleInkSpec.hidesSomething(msg("hello")))
        assertTrue(InvisibleInkSpec.hidesSomething(msg("hello", attachments = listOf(photo))))
        assertTrue(InvisibleInkSpec.hidesSomething(msg(null, reply = ReplyRef("quoted", "Ann"))))
        // Attachments only (Apple's mark in place of text, or nothing): shown plainly, no shimmer loop.
        assertFalse(InvisibleInkSpec.hidesSomething(msg("\uFFFC", attachments = listOf(photo))))
        assertFalse(InvisibleInkSpec.hidesSomething(msg(null, attachments = listOf(photo))))
        assertFalse(InvisibleInkSpec.hidesSomething(msg(null)))
        // A link-only message: the card is shown, there are no words to hide.
        assertFalse(InvisibleInkSpec.hidesSomething(msg("https://example.com/p", link = LinkPreview(url = "https://example.com/p", title = "P"))))
    }
}
