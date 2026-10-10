package io.github.avtraang.selfbubbles

// How and when an iMessage effect plays, as plain Kotlin (PlaybackRulesTest, EffectSpecsTest):
// the rules that decide whether a message's effect starts by itself and whether its caption
// line offers Replay, the bounded memory of what has played, and the specs of the four bubble
// effects (Slam, Loud, Gentle as keyframe tracks the bubble's graphicsLayer follows; Invisible
// Ink as the timing and the particle field of its shimmer). Nothing here touches Android or
// Compose: the engine that drives the bubbles with these is EffectPlayer.kt.

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What TalkBack reads for an Invisible Ink bubble while its words are hidden. */
const val INVISIBLE_INK_DESCRIPTION = "Invisible Ink message, tap to reveal"

/**
 * When an effect plays by itself, and when Replay is offered. The phone's
 * animator duration scale (Settings.Global.ANIMATOR_DURATION_SCALE) is 0 under
 * the owner's "Remove animations" accessibility setting: then nothing animates
 * and no Replay is shown, as iOS does under Reduce Motion.
 */
object PlaybackRules {
    /** How many played guids are kept: the newest, so a message seen again does not play again. */
    const val PLAYED_CAP = 2000

    /** Whether effects animate at all: a duration scale of 0 means the owner has turned animations off. */
    fun animationsOn(animatorScale: Float): Boolean = animatorScale > 0f

    /**
     * Whether the effect of [m] starts by itself, the first time its bubble is
     * on screen: a known effect other than Invisible Ink (whose play is the
     * concealment on the bubble, nothing to start), animations on, a message
     * dated at or after [effectsSince] (unix seconds; the moment this feature
     * first ran on the phone, so a fresh install does not fire every historical
     * effect while scrolling, and a message with no date is left alone) and not
     * yet in [played]. The owner's own messages play too: iOS plays a sent effect
     * for the sender.
     */
    fun autoPlays(m: Msg, effectsSince: Double, played: Set<String>, animatorScale: Float): Boolean {
        val effect = m.effect?.let { MessageEffects.byId(it) } ?: return false
        if (effect.id == MessageEffects.INVISIBLE_INK) return false
        if (!animationsOn(animatorScale)) return false
        val date = m.date ?: return false
        if (date < effectsSince) return false
        return m.guid !in played
    }

    /**
     * Whether the caption line ("Sent with Slam") carries Replay beside it: a
     * known effect, not Invisible Ink (a tap on the bubble is its replay),
     * animations on, and not while the bubble's own play is running.
     */
    fun showsReplay(effect: String?, animationsOn: Boolean, playing: Boolean = false): Boolean =
        effect != null && MessageEffects.byId(effect) != null &&
            effect != MessageEffects.INVISIBLE_INK && animationsOn && !playing

    /**
     * [played] with [guid] as its newest entry (moved there if it was already
     * in), the oldest dropped past [cap]. Oldest first, so the stored order is
     * the order of play.
     */
    fun rememberPlayed(played: Collection<String>, guid: String, cap: Int = PLAYED_CAP): List<String> {
        val next = ArrayList<String>(played.size + 1)
        for (g in played) if (g != guid) next.add(g)
        next.add(guid)
        return if (next.size > cap) next.subList(next.size - cap, next.size).toList() else next
    }
}

/** One guid per line: guids carry no newline, and a string keeps the order a preferences string set would lose. */
fun playedEncode(played: List<String>): String = played.joinToString("\n")

fun playedDecode(stored: String?): List<String> =
    stored.orEmpty().split('\n').filter { it.isNotEmpty() }

// ---------------------------------------------------------------------------
// The bubble effects: Slam, Loud, Gentle
// ---------------------------------------------------------------------------

/** How a track moves from one keyframe to the next: closed forms (easings.net), so the spec evaluates without Compose. */
enum class EffectEase {
    LINEAR, OUT, IN_OUT,
    /** Past the target and back (easeOutBack, about a tenth over): the settling of a spring, in one segment. */
    OVERSHOOT;

    fun at(t: Float): Float = when (this) {
        LINEAR -> t
        OUT -> 1f - (1f - t) * (1f - t) * (1f - t)
        IN_OUT -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
        OVERSHOOT -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            1f + c3 * (t - 1f) * (t - 1f) * (t - 1f) + c1 * (t - 1f) * (t - 1f)
        }
    }
}

/** A keyframe: the track's [value] [atMillis] into the effect, reached from the previous frame along [ease]. */
data class EffectKey(val atMillis: Int, val value: Float, val ease: EffectEase = EffectEase.LINEAR)

/**
 * One animated property over the effect: keyframes in time order, the first
 * at 0 ms, the last the value the property rests at when the effect is over.
 * [valueAt] is what each frame reads; it allocates nothing.
 */
class EffectTrack(val keys: List<EffectKey>) {
    init {
        require(keys.isNotEmpty() && keys.first().atMillis == 0) { "a track starts at 0 ms" }
        require(keys.zipWithNext().all { (a, b) -> b.atMillis > a.atMillis }) { "keyframes in time order" }
    }

    val durationMillis: Int get() = keys.last().atMillis
    val start: Float get() = keys.first().value
    val end: Float get() = keys.last().value

    fun valueAt(millis: Long): Float {
        if (millis <= 0) return start
        if (millis >= durationMillis) return end
        var i = 1
        while (keys[i].atMillis < millis) i++
        val a = keys[i - 1]
        val b = keys[i]
        val t = (millis - a.atMillis).toFloat() / (b.atMillis - a.atMillis)
        return a.value + (b.value - a.value) * b.ease.at(t)
    }

    companion object {
        /** A property this effect does not move: one key, the rest value. */
        fun still(value: Float) = EffectTrack(listOf(EffectKey(0, value)))
    }
}

/**
 * A bubble effect as the bubble's graphicsLayer plays it: [scale] and
 * [rotation] (degrees) about the bubble's own corner on the sender's side when
 * [fromTail], else its centre; [alpha]; and [joltDp], the whole row's vertical
 * shove in dp (Slam's landing; still for the others). Every track ends at rest
 * (1, 0, 1, 0), which EffectSpecsTest pins: a finished effect leaves the bubble
 * exactly where it was.
 */
data class BubbleEffectSpec(
    val id: String,
    val fromTail: Boolean,
    val scale: EffectTrack,
    val rotation: EffectTrack,
    val alpha: EffectTrack,
    val joltDp: EffectTrack,
) {
    val durationMillis: Int = maxOf(scale.durationMillis, rotation.durationMillis, alpha.durationMillis, joltDp.durationMillis)
}

object EffectSpecs {
    private fun key(at: Int, value: Float, ease: EffectEase = EffectEase.LINEAR) = EffectKey(at, value, ease)

    /**
     * Slam: the bubble appears at two and a half times its size, tilted and a
     * little faint, and slams to its place with a spring's overshoot (a squash
     * to about 0.85 on the way), then the row jolts for 200 ms as it lands.
     */
    val slam = BubbleEffectSpec(
        id = "com.apple.MobileSMS.expressivesend.impact",
        fromTail = true,
        scale = EffectTrack(listOf(key(0, 2.5f), key(280, 1f, EffectEase.OVERSHOOT))),
        rotation = EffectTrack(listOf(key(0, -4f), key(280, 0f, EffectEase.OUT))),
        alpha = EffectTrack(listOf(key(0, 0.7f), key(120, 1f, EffectEase.OUT))),
        joltDp = EffectTrack(listOf(
            key(0, 0f), key(240, 0f),
            key(270, 3f), key(300, -3f), key(330, 2f), key(360, -1f), key(440, 0f),
        )),
    )

    /** Loud: the bubble swells to 1.6x, shakes a few degrees either way several times, and settles over 1.2 s. */
    val loud = BubbleEffectSpec(
        id = "com.apple.MobileSMS.expressivesend.loud",
        fromTail = false,
        scale = EffectTrack(listOf(key(0, 1f), key(160, 1.6f, EffectEase.OUT), key(960, 1.5f), key(1200, 1f, EffectEase.IN_OUT))),
        rotation = EffectTrack(listOf(
            key(0, 0f), key(160, 0f),
            key(240, -3.5f), key(320, 3.5f), key(400, -3.5f), key(480, 3.5f),
            key(560, -3f), key(640, 3f), key(720, -2f), key(800, 2f), key(880, -1f),
            key(960, 0f), key(1200, 0f),
        )),
        alpha = EffectTrack.still(1f),
        joltDp = EffectTrack.still(0f),
    )

    /** Gentle: the bubble starts at half size and faint, and eases to its place over 1.5 s. */
    val gentle = BubbleEffectSpec(
        id = "com.apple.MobileSMS.expressivesend.gentle",
        fromTail = true,
        scale = EffectTrack(listOf(key(0, 0.5f), key(1500, 1f, EffectEase.OUT))),
        rotation = EffectTrack.still(0f),
        alpha = EffectTrack(listOf(key(0, 0.35f), key(1500, 1f, EffectEase.OUT))),
        joltDp = EffectTrack.still(0f),
    )

    /** The three effects the graphicsLayer plays, by Apple's id. Invisible Ink is drawn, not moved: [InvisibleInkSpec]. */
    val bubble: Map<String, BubbleEffectSpec> = listOf(slam, loud, gentle).associateBy { it.id }

    fun bubbleSpec(effectId: String): BubbleEffectSpec? = bubble[effectId]
}

// ---------------------------------------------------------------------------
// Invisible Ink
// ---------------------------------------------------------------------------

/**
 * Invisible Ink: the words sit under a drifting shimmer of particles in the
 * bubble's own text colour; a tap fades the particles out and the words in,
 * and after [REVEAL_MILLIS] they hide again.
 */
object InvisibleInkSpec {
    /** How long a tap shows the words, from the tap. */
    const val REVEAL_MILLIS = 5_000L
    /** The cross-fade between the shimmer and the words, each way. */
    const val FADE_MILLIS = 350

    /** One particle per this many dp² of bubble: dense enough to read as a cloud over the words, not a wall. */
    const val DP2_PER_PARTICLE = 40f
    const val MIN_PARTICLES = 24
    const val MAX_PARTICLES = 320

    /** Particles drift at most this fast, in dp per second: slowly, as iOS's do. */
    const val SPEED_DP_PER_S = 6f
    const val RADIUS_MIN_DP = 0.75f
    const val RADIUS_MAX_DP = 1.75f
    /** Each particle twinkles between these, at [TWINKLE_HZ] cycles a second. */
    const val ALPHA_MIN = 0.25f
    const val ALPHA_MAX = 0.9f
    const val TWINKLE_HZ = 0.8f

    /** How many of a field's particles are drawn over a bubble of this size. */
    fun particleCount(widthDp: Float, heightDp: Float): Int =
        (widthDp * heightDp / DP2_PER_PARTICLE).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)

    /**
     * Whether the ink has anything to hide on the bubble of [m]: words in its
     * text bubble ([bubbleBody]) or a quoted reply. A message of attachments
     * only shows them plainly, as iOS does, so no ink state and no shimmer
     * loop are made for it.
     */
    fun hidesSomething(m: Msg): Boolean = bubbleBody(m) != null || m.reply_to != null
}

/**
 * The particles of one Invisible Ink message: [count] of them, placed and sent
 * drifting by [seed] (the message's guid, so the same message shimmers the
 * same way each time it is drawn). Each frame asks where particle [i] is
 * [seconds] in, across a bubble [width] (or height) pixels wide, and how
 * bright it is; the answers are arithmetic on the arrays, no allocation.
 */
class InkField(val count: Int, seed: Long) {
    private val x0 = FloatArray(count)
    private val y0 = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val r = FloatArray(count)
    private val phase = FloatArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            x0[i] = rnd.nextFloat()
            y0[i] = rnd.nextFloat()
            val angle = rnd.nextFloat() * TWO_PI
            val speed = (0.3f + 0.7f * rnd.nextFloat()) * InvisibleInkSpec.SPEED_DP_PER_S
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed
            r[i] = rnd.nextFloat()
            phase[i] = rnd.nextFloat()
        }
    }

    /** Particle [i]'s x in pixels, [seconds] in, wrapped around a bubble [width] wide ([pxPerDp] converts its speed). */
    fun x(i: Int, seconds: Float, width: Float, pxPerDp: Float): Float = wrap(x0[i] * width + vx[i] * pxPerDp * seconds, width)

    fun y(i: Int, seconds: Float, height: Float, pxPerDp: Float): Float = wrap(y0[i] * height + vy[i] * pxPerDp * seconds, height)

    /** Particle [i]'s size as a share of the way from [InvisibleInkSpec.RADIUS_MIN_DP] to [InvisibleInkSpec.RADIUS_MAX_DP]. */
    fun radius(i: Int): Float = r[i]

    /** Particle [i]'s alpha [seconds] in: a slow twinkle between [InvisibleInkSpec.ALPHA_MIN] and [InvisibleInkSpec.ALPHA_MAX]. */
    fun alpha(i: Int, seconds: Float): Float {
        val wave = 0.5f + 0.5f * sin(TWO_PI * (phase[i] + seconds * InvisibleInkSpec.TWINKLE_HZ))
        return InvisibleInkSpec.ALPHA_MIN + (InvisibleInkSpec.ALPHA_MAX - InvisibleInkSpec.ALPHA_MIN) * wave
    }

    private fun wrap(v: Float, extent: Float): Float {
        if (extent <= 0f) return 0f
        val m = v % extent
        return if (m < 0f) m + extent else m
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
    }
}
