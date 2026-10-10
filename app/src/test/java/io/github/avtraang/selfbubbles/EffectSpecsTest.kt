package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble effects as the bubble plays them (2026-10-10, round 1). Slam,
 * Loud and Gentle are keyframe tracks (EffectSpecs, EffectPlayback.kt) that a
 * frame loop reads into the bubble's graphicsLayer; Invisible Ink is a timing
 * and a particle field (InvisibleInkSpec, InkField) drawn over the words.
 *
 * These pin what each effect looks like at its ends and in between: Slam from
 * two and a half times its size to its place with a brief jolt of the row,
 * Loud swelling past its size and settling, Gentle from half size and faint,
 * every one ending at rest (so a finished effect leaves the bubble exactly
 * where it was) and lasting between 0.3 and 2.5 s; the reveal window of
 * Invisible Ink around 5 s; and that the particles stay inside their bubble
 * however long they drift.
 */
class EffectSpecsTest {
    private val slam = EffectSpecs.slam
    private val loud = EffectSpecs.loud
    private val gentle = EffectSpecs.gentle
    private val all = listOf(slam, loud, gentle)

    private fun BubbleEffectSpec.tracks() = listOf(scale, rotation, alpha, joltDp)

    // ---- the catalogue ----

    @Test fun the_three_moving_bubble_effects_are_found_by_apples_id_and_invisible_ink_is_not_one() {
        assertEquals(slam, EffectSpecs.bubbleSpec("com.apple.MobileSMS.expressivesend.impact"))
        assertEquals(loud, EffectSpecs.bubbleSpec("com.apple.MobileSMS.expressivesend.loud"))
        assertEquals(gentle, EffectSpecs.bubbleSpec("com.apple.MobileSMS.expressivesend.gentle"))
        // Invisible Ink is drawn, not moved; a screen effect is not a bubble's to play.
        assertNull(EffectSpecs.bubbleSpec(MessageEffects.INVISIBLE_INK))
        assertNull(EffectSpecs.bubbleSpec("com.apple.messages.effect.CKConfettiEffect"))
        // Every spec is one of the catalogue's bubble effects.
        for (spec in all) assertEquals(spec.id, EffectKind.BUBBLE, MessageEffects.byId(spec.id)?.kind)
    }

    // ---- Slam ----

    @Test fun slam_starts_large_and_tilted_and_lands_at_its_place() {
        assertEquals(2.5f, slam.scale.start, 0.1f)
        assertEquals(1f, slam.scale.end, 0f)
        assertNotEquals(0f, slam.rotation.start)
        assertEquals(0f, slam.rotation.end, 0f)
        // The spring overshoots: on the way from 2.5 to 1 the bubble squashes below its size before settling.
        val smallest = (0..slam.scale.durationMillis).minOf { slam.scale.valueAt(it.toLong()) }
        assertTrue("squashes to $smallest", smallest < 1f && smallest > 0.7f)
        assertTrue(slam.fromTail)
    }

    @Test fun slams_row_jolts_briefly_around_the_landing_and_comes_back() {
        val jolt = slam.joltDp
        assertEquals(0f, jolt.start, 0f)
        assertEquals(0f, jolt.end, 0f)
        val moving = jolt.keys.filter { it.value != 0f }
        assertTrue("a jolt moves", moving.isNotEmpty())
        assertTrue("both ways", moving.any { it.value > 0f } && moving.any { it.value < 0f })
        assertTrue("small", moving.all { kotlin.math.abs(it.value) <= 4f })
        // From the last still frame before the first shove to the frame it comes to rest: about 200 ms.
        val from = jolt.keys.last { it.atMillis < moving.first().atMillis }.atMillis
        val span = jolt.durationMillis - from
        assertTrue("jolt lasts $span ms", span in 150..300)
    }

    // ---- Loud ----

    @Test fun loud_swells_past_its_size_shakes_both_ways_and_settles_in_about_a_second_and_a_bit() {
        assertEquals(1f, loud.scale.start, 0f)
        val peak = loud.scale.keys.maxOf { it.value }
        assertTrue("peak $peak", peak > 1.4f && peak <= 1.8f)
        assertEquals(1f, loud.scale.end, 0f)
        val swings = loud.rotation.keys.map { it.value }
        assertTrue(swings.any { it >= 3f } && swings.any { it <= -3f })
        assertTrue("a few shakes", swings.count { it > 0f } >= 3)
        assertTrue("never more than a few degrees", swings.all { kotlin.math.abs(it) <= 4f })
        assertEquals(0f, loud.rotation.end, 0f)
        assertTrue("lasts ${loud.durationMillis} ms", loud.durationMillis in 1000..1400)
    }

    // ---- Gentle ----

    @Test fun gentle_starts_small_and_faint_and_eases_to_its_place_over_about_a_second_and_a_half() {
        assertEquals(0.5f, gentle.scale.start, 0.1f)
        assertEquals(1f, gentle.scale.end, 0f)
        assertTrue("faint at first", gentle.alpha.start < 0.6f)
        assertEquals(1f, gentle.alpha.end, 0f)
        assertEquals(0f, gentle.rotation.start, 0f)
        assertTrue("lasts ${gentle.durationMillis} ms", gentle.durationMillis in 1300..1700)
        // Eases: monotone, and most of the way there by the middle.
        val mid = gentle.scale.valueAt((gentle.durationMillis / 2).toLong())
        assertTrue("mid $mid", mid > 0.85f && mid < 1f)
        for (t in 0 until gentle.durationMillis step 50) {
            assertTrue(gentle.scale.valueAt(t.toLong()) <= gentle.scale.valueAt(t.toLong() + 50) + 1e-4f)
        }
    }

    // ---- every one of them ----

    @Test fun every_bubble_effect_lasts_between_a_third_of_a_second_and_two_and_a_half() {
        for (spec in all) assertTrue("${spec.id}: ${spec.durationMillis} ms", spec.durationMillis in 300..2500)
    }

    @Test fun every_bubble_effect_ends_at_rest_so_the_bubble_is_left_exactly_where_it_was() {
        for (spec in all) {
            assertEquals(spec.id, 1f, spec.scale.end, 0f)
            assertEquals(spec.id, 0f, spec.rotation.end, 0f)
            assertEquals(spec.id, 1f, spec.alpha.end, 0f)
            assertEquals(spec.id, 0f, spec.joltDp.end, 0f)
            // And the values read after the effect is over are the rest values, not the last key's easing.
            val after = (spec.durationMillis + 1000).toLong()
            assertEquals(1f, spec.scale.valueAt(after), 0f)
            assertEquals(0f, spec.rotation.valueAt(after), 0f)
            assertEquals(1f, spec.alpha.valueAt(after), 0f)
            assertEquals(0f, spec.joltDp.valueAt(after), 0f)
        }
    }

    @Test fun every_track_starts_at_zero_and_is_in_time_order() {
        for (spec in all) for (track in spec.tracks()) {
            assertEquals(0, track.keys.first().atMillis)
            assertEquals(track.keys.map { it.atMillis }.sorted(), track.keys.map { it.atMillis })
            assertEquals(track.keys.map { it.atMillis }.distinct().size, track.keys.size)
            // Before the start and at the start the first value; at a key, that key's own value.
            assertEquals(track.start, track.valueAt(-5), 0f)
            assertEquals(track.start, track.valueAt(0), 0f)
            for (k in track.keys) assertEquals(k.value, track.valueAt(k.atMillis.toLong()), 1e-5f)
        }
    }

    @Test fun a_track_is_read_between_its_keys_along_the_segments_easing() {
        val track = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(100, 10f), EffectKey(300, 0f, EffectEase.OUT)))
        assertEquals(5f, track.valueAt(50), 1e-5f)       // linear by default
        assertEquals(10f, track.valueAt(100), 0f)
        assertEquals(10f - 10f * EffectEase.OUT.at(0.5f), track.valueAt(200), 1e-5f)
        assertEquals(0f, track.valueAt(300), 0f)
        assertEquals(0f, track.valueAt(999), 0f)
    }

    @Test fun every_easing_runs_from_zero_to_one_and_only_overshoot_goes_past() {
        for (ease in EffectEase.entries) {
            assertEquals(ease.name, 0f, ease.at(0f), 1e-6f)
            assertEquals(ease.name, 1f, ease.at(1f), 1e-6f)
            val past = (1..99).any { ease.at(it / 100f) > 1f }
            assertEquals(ease.name, ease == EffectEase.OVERSHOOT, past)
        }
        // easeOutBack goes about a tenth past, never more.
        assertTrue((1..99).maxOf { EffectEase.OVERSHOOT.at(it / 100f) } in 1.05f..1.15f)
    }

    @Test fun a_still_track_holds_its_value_and_a_malformed_one_is_refused() {
        val still = EffectTrack.still(1f)
        assertEquals(0, still.durationMillis)
        assertEquals(1f, still.valueAt(0), 0f)
        assertEquals(1f, still.valueAt(5000), 0f)
        assertTrue(runCatching { EffectTrack(emptyList()) }.isFailure)
        assertTrue(runCatching { EffectTrack(listOf(EffectKey(10, 1f))) }.isFailure)
        assertTrue(runCatching { EffectTrack(listOf(EffectKey(0, 1f), EffectKey(0, 2f))) }.isFailure)
        assertTrue(runCatching { EffectTrack(listOf(EffectKey(0, 1f), EffectKey(50, 2f), EffectKey(20, 3f))) }.isFailure)
    }

    // ---- Invisible Ink ----

    @Test fun invisible_ink_shows_its_words_for_about_five_seconds_and_fades_well_within_that() {
        assertTrue(InvisibleInkSpec.REVEAL_MILLIS in 4000L..6000L)
        assertTrue(InvisibleInkSpec.FADE_MILLIS in 100..600)
        assertTrue(InvisibleInkSpec.FADE_MILLIS * 2 < InvisibleInkSpec.REVEAL_MILLIS)
    }

    @Test fun a_bigger_bubble_gets_more_particles_within_bounds() {
        val small = InvisibleInkSpec.particleCount(60f, 36f)     // a one-word pill
        val wide = InvisibleInkSpec.particleCount(300f, 36f)     // a one-line bubble at the cap
        val tall = InvisibleInkSpec.particleCount(300f, 400f)    // a paragraph
        assertTrue(small <= wide && wide <= tall)
        assertEquals(InvisibleInkSpec.MIN_PARTICLES, InvisibleInkSpec.particleCount(1f, 1f))
        assertEquals(InvisibleInkSpec.MAX_PARTICLES, tall)
        assertTrue(InvisibleInkSpec.MIN_PARTICLES in 1 until InvisibleInkSpec.MAX_PARTICLES)
    }

    @Test fun the_particles_stay_inside_their_bubble_however_long_they_drift() {
        val field = InkField(InvisibleInkSpec.MAX_PARTICLES, seed = 42L)
        assertEquals(InvisibleInkSpec.MAX_PARTICLES, field.count)
        val w = 280f * 2.75f
        val h = 36f * 2.75f
        for (seconds in listOf(0f, 0.5f, 3f, 61f, 3600f)) for (i in 0 until field.count) {
            val x = field.x(i, seconds, w, 2.75f)
            val y = field.y(i, seconds, h, 2.75f)
            assertTrue("x $x at $seconds s", x >= 0f && x < w)
            assertTrue("y $y at $seconds s", y >= 0f && y < h)
            val a = field.alpha(i, seconds)
            assertTrue("alpha $a", a >= InvisibleInkSpec.ALPHA_MIN - 1e-4f && a <= InvisibleInkSpec.ALPHA_MAX + 1e-4f)
            assertTrue(field.radius(i) in 0f..1f)
        }
        // A degenerate size (nothing laid out yet) is answered with 0, not NaN.
        assertEquals(0f, field.x(0, 1f, 0f, 2.75f), 0f)
    }

    @Test fun the_particles_drift_slowly_and_the_same_message_shimmers_the_same_way_each_time() {
        val a = InkField(50, seed = 7L)
        val b = InkField(50, seed = 7L)
        val other = InkField(50, seed = 8L)
        for (i in 0 until 50) {
            assertEquals(a.x(i, 2f, 1000f, 1f), b.x(i, 2f, 1000f, 1f), 0f)
            assertEquals(a.alpha(i, 2f), b.alpha(i, 2f), 0f)
        }
        assertTrue((0 until 50).any { a.x(it, 0f, 1000f, 1f) != other.x(it, 0f, 1000f, 1f) })
        // In one second no particle moves further than the spec's top speed, in dp (a wide field, so nothing wraps).
        for (i in 0 until 50) {
            val moved = kotlin.math.abs(a.x(i, 1f, 100_000f, 1f) - a.x(i, 0f, 100_000f, 1f))
            assertTrue("moved $moved dp", moved <= InvisibleInkSpec.SPEED_DP_PER_S + 1e-3f)
        }
    }
}
