package io.github.avtraang.selfbubbles

// The nine screen effects as plain Kotlin (ScreenEffectSpecsTest): what each one is before it is drawn.
// A ScreenEffectSpec says how long the overlay holds the effect and how many particles it allocates;
// each effect's own object holds its numbers (speeds, sizes, colours) and the maths of its motion, and
// a *Field class lays its particles out once per play from a seed into flat arrays, so that a frame is
// arithmetic on those arrays and allocates nothing. The rule that clears a finished play, and that a
// Replay restarts the painters, is ScreenPlayRules. Nothing here touches Android or Compose: the
// painters that read these are ScreenEffectPainters.kt, the engine that runs them EffectPlayer.kt.

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the overlay knows of a screen effect before painting it: Apple's [id],
 * how long it holds the overlay ([durationMillis]; the painter calls
 * screenDone when that is up), how many particles its field allocates
 * ([particles]: made once per play, never per frame) and whether it [dims]
 * the chat under it, as iOS darkens the screen for Lasers, Fireworks and
 * Spotlight and for no other.
 */
data class ScreenEffectSpec(val id: String, val durationMillis: Int, val particles: Int, val dims: Boolean)

/**
 * The colours the particles are drawn in, as 0xRRGGBB (what Konfetti takes;
 * the painters make an opaque Color of one). A fixed set, bright on the dark
 * and the light theme alike; the words of Echo and the hearts of Love take the
 * message's own bubble colours instead.
 */
object EffectPalette {
    /** Paper and streamers: a party's mix. */
    val party = intArrayOf(0xFF5E7C, 0xFFB347, 0xFFE34D, 0x4CD964, 0x34AADC, 0x5E5CE6, 0xFF7AB6)
    /** Celebration's sparkles and the shooting star's tail: golds, warm to pale. */
    val gold = intArrayOf(0xFFD700, 0xFFE680, 0xFFC125, 0xFFF4B3, 0xFFFFFF)
    /** Balloons: pastels. */
    val pastel = intArrayOf(0xFFB3C6, 0xFFD9A8, 0xFFF3A8, 0xB8F2C2, 0xB3E0FF, 0xD6C4FF, 0xFFC4F0)
    /** Lasers and fireworks: saturated, so they read against a dimmed screen. */
    val neon = intArrayOf(0xFF3B5C, 0xFF9F1C, 0xFFE234, 0x3DF27A, 0x33C9FF, 0x7C5CFF, 0xFF5CF2)
}

/** How far along a span that starts at [startMillis] and lasts [spanMillis] the moment [millis] is: 0 before, 1 after. */
internal fun progress(millis: Long, startMillis: Int, spanMillis: Int): Float =
    ((millis - startMillis).toFloat() / spanMillis).coerceIn(0f, 1f)

private const val TWO_PI = (2.0 * PI).toFloat()
private const val DEG = (PI / 180.0).toFloat()

/** A value between [a] and [b], [t] of the way (0 to 1). */
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun Random.between(a: Float, b: Float): Float = a + (b - a) * nextFloat()

// ---------------------------------------------------------------------------
// Confetti
// ---------------------------------------------------------------------------

/**
 * Confetti: coloured paper falls from the whole top edge. Drawn by Konfetti
 * (KonfettiView), so this is the configuration of its Party in the library's
 * units (speed in its own scale, sizes in dp) and the duration the overlay
 * holds for it, which must outlast the shower: paper keeps coming for
 * [EMIT_MILLIS], each piece lives [TIME_TO_LIVE_MILLIS] from the moment it is
 * emitted, and only then fades, taking up to [FADE_MILLIS] (a piece that falls
 * off the bottom first dies there). The hold is the sum of the three, so the
 * last piece emitted is gone before the overlay is, and the whole view is
 * faded over the last [EXIT_MILLIS] so nothing it still shows vanishes in one
 * frame. That makes it the longest of the nine, within the five-second bound.
 */
object ConfettiSpec {
    const val ID = "com.apple.messages.effect.CKConfettiEffect"
    const val EMIT_MILLIS = 1500
    const val PER_SECOND = 120
    const val TIME_TO_LIVE_MILLIS = 2200L
    /**
     * Konfetti's fade is per frame, not per second: alpha 255 falls 5 a frame,
     * 51 frames, which is 850 ms at 60 Hz and less on a faster screen. A full
     * second covers a screen as slow as 51 Hz.
     */
    const val FADE_MILLIS = 1000
    /** The overlay's own fade of whatever is left at the end. */
    const val EXIT_MILLIS = 400
    /** Straight down with a wide spread, so the paper drifts rather than drops in lines. */
    const val ANGLE_DEGREES = 90
    const val SPREAD_DEGREES = 60
    const val SPEED_MIN = 0f
    const val SPEED_MAX = 15f
    const val DAMPING = 0.9f
    /** The oblong pieces: height as a share of width. */
    const val STRIP_RATIO = 0.4f

    val spec = ScreenEffectSpec(
        ID,
        durationMillis = EMIT_MILLIS + TIME_TO_LIVE_MILLIS.toInt() + FADE_MILLIS,
        particles = PER_SECOND * EMIT_MILLIS / 1000,
        dims = false,
    )
    /** The view's alpha: whole until the shower should be over, then out with the overlay. */
    val exit = EffectTrack(listOf(EffectKey(0, 1f), EffectKey(spec.durationMillis - EXIT_MILLIS, 1f), EffectKey(spec.durationMillis, 0f)))
}

// ---------------------------------------------------------------------------
// Celebration
// ---------------------------------------------------------------------------

/**
 * Celebration (iOS's Sparkles): golden stars burst from the two top corners,
 * [BURSTS_PER_CORNER] times each, fanning down and inward, slowed by a little
 * gravity and twinkling as they fade. The right corner fires half a beat after
 * the left so the bursts alternate.
 */
object CelebrationSpec {
    const val ID = "com.apple.messages.effect.CKSparklesEffect"
    const val BURSTS_PER_CORNER = 3
    const val SPARKS_PER_BURST = 40
    const val BURST_GAP_MILLIS = 600
    const val CORNER_OFFSET_MILLIS = 300
    /** A spark's life: the last burst (1500 ms in) is out by 2800 ms, inside the play. */
    const val SPARK_LIFE_MILLIS = 1300
    const val SPEED_MIN_DP_PER_S = 180f
    const val SPEED_MAX_DP_PER_S = 420f
    const val GRAVITY_DP_PER_S2 = 260f
    /** A spark's arm, centre to tip, and the stroke it is drawn with, in dp. */
    const val ARM_DP = 3.5f
    const val STROKE_DP = 1.4f
    /** The fan: centred on the diagonal into the screen (45° below the horizontal), this wide in all. */
    const val FAN_DEGREES = 80f
    const val TWINKLE_HZ = 6f

    val spec = ScreenEffectSpec(ID, durationMillis = 3000, particles = 2 * BURSTS_PER_CORNER * SPARKS_PER_BURST, dims = false)
}

/**
 * The sparks of one Celebration, laid out by [seed]: spark [i] belongs to a
 * corner and a burst by its index, and has its own angle, speed and twinkle.
 */
class CelebrationField(seed: Long) {
    val count = CelebrationSpec.spec.particles
    private val born = IntArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val color = IntArray(count)
    private val phase = FloatArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            val burst = i / CelebrationSpec.SPARKS_PER_BURST
            val right = burst % 2 == 1
            val round = burst / 2
            born[i] = round * CelebrationSpec.BURST_GAP_MILLIS + if (right) CelebrationSpec.CORNER_OFFSET_MILLIS else 0
            // Into the screen: from the left corner down and right (45°), from the right corner down and left (135°).
            val centre = if (right) 135f else 45f
            val angle = (centre + rnd.between(-0.5f, 0.5f) * CelebrationSpec.FAN_DEGREES) * DEG
            val speed = rnd.between(CelebrationSpec.SPEED_MIN_DP_PER_S, CelebrationSpec.SPEED_MAX_DP_PER_S)
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed
            color[i] = EffectPalette.gold[rnd.nextInt(EffectPalette.gold.size)]
            phase[i] = rnd.nextFloat()
        }
    }

    /** Whether spark [i] starts at the right corner (else the left). */
    fun fromRight(i: Int): Boolean = (i / CelebrationSpec.SPARKS_PER_BURST) % 2 == 1

    fun bornMillis(i: Int): Int = born[i]

    /** Whether spark [i] is in the air [millis] in: born, and not yet out. */
    fun alive(i: Int, millis: Long): Boolean = millis >= born[i] && millis < born[i] + CelebrationSpec.SPARK_LIFE_MILLIS

    /** Spark [i]'s x in pixels across a screen [width] wide ([pxPerDp] scales its speed). */
    fun x(i: Int, millis: Long, width: Float, pxPerDp: Float): Float {
        val t = (millis - born[i]) / 1000f
        return (if (fromRight(i)) width else 0f) + vx[i] * t * pxPerDp
    }

    fun y(i: Int, millis: Long, pxPerDp: Float): Float {
        val t = (millis - born[i]) / 1000f
        return (vy[i] * t + 0.5f * CelebrationSpec.GRAVITY_DP_PER_S2 * t * t) * pxPerDp
    }

    /** Fading out over its life, twinkling the while. */
    fun alpha(i: Int, millis: Long): Float {
        val life = progress(millis, born[i], CelebrationSpec.SPARK_LIFE_MILLIS)
        val twinkle = 0.65f + 0.35f * sin(TWO_PI * (phase[i] + millis / 1000f * CelebrationSpec.TWINKLE_HZ))
        return (1f - life) * twinkle
    }

    fun color(i: Int): Int = color[i]
}

// ---------------------------------------------------------------------------
// Balloons
// ---------------------------------------------------------------------------

/**
 * Balloons (iOS's Happy Birthday): [COUNT] pastel balloons, each an oval with
 * a thin string, rise from below the bottom edge and out over the top in about
 * four seconds, each on its own start, speed and sway. The travel is a share of
 * the screen's height, so they clear a Fold's tall inner screen as surely as a
 * phone's.
 */
object BalloonsSpec {
    const val ID = "com.apple.messages.effect.CKHappyBirthdayEffect"
    const val COUNT = 25
    /** Balloons set off over this long... */
    const val START_SPREAD_MILLIS = 1200
    /** ...and each takes between these to cross the screen, so the last is gone by the end. */
    const val TRAVEL_MIN_MILLIS = 2200
    const val TRAVEL_MAX_MILLIS = 2700
    const val RADIUS_MIN_DP = 14f
    const val RADIUS_MAX_DP = 22f
    /** An oval, taller than wide. */
    const val ASPECT = 1.2f
    const val STRING_DP = 36f
    const val STRING_WIDTH_DP = 1f
    const val SWAY_DP = 14f
    const val SWAY_MIN_HZ = 0.35f
    const val SWAY_MAX_HZ = 0.7f
    /** Centres stay this far in from the sides, as a share of the width. */
    const val SIDE_MARGIN = 0.06f

    val spec = ScreenEffectSpec(ID, durationMillis = 4000, particles = COUNT, dims = false)

    /** A balloon's height in dp, oval and string, the margin it starts below the screen and ends above it. */
    fun tailDp(radiusDp: Float): Float = 2f * radiusDp * ASPECT + STRING_DP
}

/** The balloons of one play, laid out by [seed]. */
class BalloonField(seed: Long) {
    val count = BalloonsSpec.COUNT
    private val x0 = FloatArray(count)
    private val start = IntArray(count)
    private val travel = IntArray(count)
    private val radius = FloatArray(count)
    private val swayHz = FloatArray(count)
    private val phase = FloatArray(count)
    private val color = IntArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            x0[i] = rnd.between(BalloonsSpec.SIDE_MARGIN, 1f - BalloonsSpec.SIDE_MARGIN)
            start[i] = (rnd.nextFloat() * BalloonsSpec.START_SPREAD_MILLIS).toInt()
            travel[i] = (BalloonsSpec.TRAVEL_MIN_MILLIS + rnd.nextFloat() * (BalloonsSpec.TRAVEL_MAX_MILLIS - BalloonsSpec.TRAVEL_MIN_MILLIS)).toInt()
            radius[i] = rnd.between(BalloonsSpec.RADIUS_MIN_DP, BalloonsSpec.RADIUS_MAX_DP)
            swayHz[i] = rnd.between(BalloonsSpec.SWAY_MIN_HZ, BalloonsSpec.SWAY_MAX_HZ)
            phase[i] = rnd.nextFloat()
            color[i] = EffectPalette.pastel[rnd.nextInt(EffectPalette.pastel.size)]
        }
    }

    fun startMillis(i: Int): Int = start[i]
    fun travelMillis(i: Int): Int = travel[i]
    fun radiusDp(i: Int): Float = radius[i]
    fun color(i: Int): Int = color[i]

    /** Balloon [i]'s centre x in pixels: its lane across [width], swaying. */
    fun x(i: Int, millis: Long, width: Float, pxPerDp: Float): Float =
        x0[i] * width + sin(TWO_PI * (phase[i] + millis / 1000f * swayHz[i])) * BalloonsSpec.SWAY_DP * pxPerDp

    /** Balloon [i]'s centre y in pixels: from wholly below a screen [height] tall to wholly above it. */
    fun y(i: Int, millis: Long, height: Float, pxPerDp: Float): Float {
        val tail = BalloonsSpec.tailDp(radius[i]) * pxPerDp
        return height + tail - progress(millis, start[i], travel[i]) * (height + 2f * tail)
    }
}

// ---------------------------------------------------------------------------
// Love
// ---------------------------------------------------------------------------

/**
 * Love (iOS's Heart): one big heart grows out of the message's bubble with a
 * spring's overshoot, floats up and fades, while a few small hearts drift up
 * around it. Drawn in the bubble's own colours.
 */
object LoveSpec {
    const val ID = "com.apple.messages.effect.CKHeartEffect"
    const val BIG_SIZE_DP = 120f
    const val BIG_RISE_DP = 220f
    const val SMALL_COUNT = 8
    const val SMALL_SIZE_MIN_DP = 14f
    const val SMALL_SIZE_MAX_DP = 26f
    const val SMALL_RISE_MIN_DP = 160f
    const val SMALL_RISE_MAX_DP = 300f
    /** The small hearts start this far either side of the bubble's centre, in dp. */
    const val SMALL_SPREAD_DP = 90f
    const val SMALL_START_MIN_MILLIS = 150
    const val SMALL_START_MAX_MILLIS = 900
    const val SMALL_SWAY_DP = 10f

    val spec = ScreenEffectSpec(ID, durationMillis = 3000, particles = SMALL_COUNT, dims = false)

    /** The big heart's size as a share of [BIG_SIZE_DP]: from nothing, past full, settling in 700 ms. */
    val bigScale = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(700, 1f, EffectEase.OVERSHOOT)))
    /** How far up it has floated, as a share of [BIG_RISE_DP]. */
    val bigRise = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(spec.durationMillis, 1f, EffectEase.OUT)))
    /** Full until the middle of the play, gone at its end. */
    val bigAlpha = EffectTrack(listOf(EffectKey(0, 1f), EffectKey(1500, 1f), EffectKey(spec.durationMillis, 0f)))
}

/** The small hearts of one Love, laid out by [seed], around a centre the painter supplies (the bubble's). */
class LoveField(seed: Long) {
    val count = LoveSpec.SMALL_COUNT
    private val dx = FloatArray(count)
    private val start = IntArray(count)
    private val size = FloatArray(count)
    private val rise = FloatArray(count)
    private val phase = FloatArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            // Alternate sides, so the hearts are not all on one.
            val side = if (i % 2 == 0) -1f else 1f
            dx[i] = side * rnd.between(0.35f, 1f) * LoveSpec.SMALL_SPREAD_DP
            start[i] = LoveSpec.SMALL_START_MIN_MILLIS + (rnd.nextFloat() * (LoveSpec.SMALL_START_MAX_MILLIS - LoveSpec.SMALL_START_MIN_MILLIS)).toInt()
            size[i] = rnd.between(LoveSpec.SMALL_SIZE_MIN_DP, LoveSpec.SMALL_SIZE_MAX_DP)
            rise[i] = rnd.between(LoveSpec.SMALL_RISE_MIN_DP, LoveSpec.SMALL_RISE_MAX_DP)
            phase[i] = rnd.nextFloat()
        }
    }

    fun startMillis(i: Int): Int = start[i]
    fun sizeDp(i: Int): Float = size[i]

    /** How far heart [i] is along its drift: 0 before it starts, 1 at the end of the play. */
    fun drift(i: Int, millis: Long): Float = progress(millis, start[i], LoveSpec.spec.durationMillis - start[i])

    /** Heart [i]'s centre x in pixels, around [centreX]. */
    fun x(i: Int, millis: Long, centreX: Float, pxPerDp: Float): Float =
        centreX + (dx[i] + sin(TWO_PI * (phase[i] + drift(i, millis))) * LoveSpec.SMALL_SWAY_DP) * pxPerDp

    /** Heart [i]'s centre y in pixels, rising from [centreY]. */
    fun y(i: Int, millis: Long, centreY: Float, pxPerDp: Float): Float =
        centreY - EffectEase.OUT.at(drift(i, millis)) * rise[i] * pxPerDp

    /** Nothing before its start, in quickly, out over the last third. */
    fun alpha(i: Int, millis: Long): Float {
        if (millis < start[i]) return 0f
        val p = drift(i, millis)
        return minOf(p * 8f, 1f) * (1f - ((p - 0.66f) / 0.34f).coerceIn(0f, 1f))
    }
}

// ---------------------------------------------------------------------------
// Lasers
// ---------------------------------------------------------------------------

/**
 * Lasers: the chat dims and [BEAMS] coloured beams fan upward from a point just
 * above the message's bubble, each swinging [SWEEP_DEGREES] either side of its
 * place in the fan, across the screen and out of it. Beam [i]'s resting angle
 * is its share of [FAN_DEGREES] around straight up; the sweep is a sine on its
 * own phase, so the beams cross rather than move as one.
 */
object LasersSpec {
    const val ID = "com.apple.messages.effect.CKLasersEffect"
    const val BEAMS = 8
    const val FAN_DEGREES = 100f
    const val SWEEP_DEGREES = 30f
    const val SWEEP_HZ = 0.45f
    const val WIDTH_DP = 3f
    const val GLOW_DP = 14f
    /** The point the beams come from sits this far above the bubble's top. */
    const val ORIGIN_ABOVE_BUBBLE_DP = 24f
    const val DIM_ALPHA = 0.6f

    val spec = ScreenEffectSpec(ID, durationMillis = 3000, particles = BEAMS, dims = true)

    /** The beams and the dimming come up in 300 ms and go in the last half second. */
    val fade = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(300, 1f), EffectKey(2500, 1f), EffectKey(spec.durationMillis, 0f)))

    /** Beam [i]'s resting angle in degrees, screen sense (0 right, 90 down): straight up is -90, the fan spread around it. */
    fun restDegrees(i: Int): Float = -90f + (i.toFloat() / (BEAMS - 1) - 0.5f) * FAN_DEGREES
}

/** The phases of one Lasers play, by [seed]: where in its swing each beam begins. */
class LaserFan(seed: Long) {
    val count = LasersSpec.BEAMS
    private val phase = FloatArray(count)
    private val color = IntArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            phase[i] = rnd.nextFloat()
            color[i] = EffectPalette.neon[(i + rnd.nextInt(2)) % EffectPalette.neon.size]
        }
    }

    /** Beam [i]'s angle [millis] in, in degrees (screen sense). */
    fun degrees(i: Int, millis: Long): Float =
        LasersSpec.restDegrees(i) + sin(TWO_PI * (phase[i] + millis / 1000f * LasersSpec.SWEEP_HZ)) * LasersSpec.SWEEP_DEGREES

    /** The beam's far end is this far from its origin: a screen's diagonal, so it always leaves the screen. */
    fun length(width: Float, height: Float): Float = hypot(width, height)

    fun endX(i: Int, millis: Long, originX: Float, width: Float, height: Float): Float =
        originX + cos(degrees(i, millis) * DEG) * length(width, height)

    fun endY(i: Int, millis: Long, originY: Float, width: Float, height: Float): Float =
        originY + sin(degrees(i, millis) * DEG) * length(width, height)

    fun color(i: Int): Int = color[i]
}

// ---------------------------------------------------------------------------
// Fireworks
// ---------------------------------------------------------------------------

/**
 * Fireworks: the chat dims and [BURSTS] shells burst in turn at places across
 * the upper screen, each throwing [SPARKS_PER_BURST] sparks out radially that
 * slow under drag, fall under gravity and fade, with a short trail behind each.
 */
object FireworksSpec {
    const val ID = "com.apple.messages.effect.CKFireworksEffect"
    const val BURSTS = 6
    const val SPARKS_PER_BURST = 48
    /** The bursts are spread over this long, the last well before the end so its sparks have time to fade. */
    const val LAST_BURST_MILLIS = 2400
    const val BURST_JITTER_MILLIS = 160
    const val SPARK_LIFE_MILLIS = 1400
    const val SPEED_MIN_DP_PER_S = 110f
    const val SPEED_MAX_DP_PER_S = 280f
    const val GRAVITY_DP_PER_S2 = 160f
    /** Air drag: a spark's speed decays by this per second. */
    const val DRAG_PER_S = 1.8f
    const val RADIUS_DP = 2.2f
    const val TRAIL_MILLIS = 70
    /** The flash at the shell's burst: this big, gone in this long. */
    const val FLASH_RADIUS_DP = 28f
    const val FLASH_MILLIS = 180
    const val DIM_ALPHA = 0.55f
    /** Bursts land within these shares of the screen. */
    const val X_MIN = 0.15f
    const val X_MAX = 0.85f
    const val Y_MIN = 0.12f
    const val Y_MAX = 0.5f

    val spec = ScreenEffectSpec(ID, durationMillis = 4000, particles = BURSTS * SPARKS_PER_BURST, dims = true)

    /** The dimming as a share of [DIM_ALPHA]: in over 300 ms, lifted over the last half second. */
    val dim = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(300, 1f), EffectKey(3500, 1f), EffectKey(spec.durationMillis, 0f)))

    /** How far a spark with speed [v] has flown [t] seconds in, slowing under drag: v(1 - e^(-kt))/k. */
    fun flown(v: Float, t: Float): Float = v * (1f - exp(-DRAG_PER_S * t)) / DRAG_PER_S
}

/** The shells and sparks of one Fireworks, laid out by [seed]. */
class FireworksField(seed: Long) {
    val bursts = FireworksSpec.BURSTS
    val count = FireworksSpec.spec.particles
    private val burstX = FloatArray(bursts)
    private val burstY = FloatArray(bursts)
    private val burstAt = IntArray(bursts)
    private val burstColor = IntArray(bursts)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val color = IntArray(count)

    init {
        val rnd = Random(seed)
        val gap = FireworksSpec.LAST_BURST_MILLIS / (bursts - 1)
        for (b in 0 until bursts) {
            burstX[b] = rnd.between(FireworksSpec.X_MIN, FireworksSpec.X_MAX)
            burstY[b] = rnd.between(FireworksSpec.Y_MIN, FireworksSpec.Y_MAX)
            burstAt[b] = (b * gap + rnd.nextFloat() * FireworksSpec.BURST_JITTER_MILLIS).toInt()
            burstColor[b] = EffectPalette.neon[rnd.nextInt(EffectPalette.neon.size)]
        }
        for (i in 0 until count) {
            val angle = rnd.nextFloat() * TWO_PI
            val speed = rnd.between(FireworksSpec.SPEED_MIN_DP_PER_S, FireworksSpec.SPEED_MAX_DP_PER_S)
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed
            // Mostly the shell's colour, a few white-hot.
            color[i] = if (rnd.nextInt(5) == 0) 0xFFFFFF else burstColor[burstOf(i)]
        }
    }

    fun burstOf(i: Int): Int = i / FireworksSpec.SPARKS_PER_BURST
    fun burstAtMillis(b: Int): Int = burstAt[b]
    fun burstX(b: Int, width: Float): Float = burstX[b] * width
    fun burstY(b: Int, height: Float): Float = burstY[b] * height
    fun burstColor(b: Int): Int = burstColor[b]

    /** The flash at shell [b]'s burst: nothing before it, 1 at the instant, 0 after [FireworksSpec.FLASH_MILLIS]. */
    fun flash(b: Int, millis: Long): Float =
        if (millis < burstAt[b]) 0f else 1f - progress(millis, burstAt[b], FireworksSpec.FLASH_MILLIS)

    fun alive(i: Int, millis: Long): Boolean {
        val at = burstAt[burstOf(i)]
        return millis >= at && millis < at + FireworksSpec.SPARK_LIFE_MILLIS
    }

    fun x(i: Int, millis: Long, width: Float, pxPerDp: Float): Float {
        val t = (millis - burstAt[burstOf(i)]) / 1000f
        return burstX(burstOf(i), width) + FireworksSpec.flown(vx[i], t) * pxPerDp
    }

    fun y(i: Int, millis: Long, height: Float, pxPerDp: Float): Float {
        val t = (millis - burstAt[burstOf(i)]) / 1000f
        return burstY(burstOf(i), height) + (FireworksSpec.flown(vy[i], t) + 0.5f * FireworksSpec.GRAVITY_DP_PER_S2 * t * t) * pxPerDp
    }

    /** Full for the first half of its life, then out. */
    fun alpha(i: Int, millis: Long): Float {
        val life = progress(millis, burstAt[burstOf(i)], FireworksSpec.SPARK_LIFE_MILLIS)
        return 1f - ((life - 0.5f) * 2f).coerceIn(0f, 1f)
    }

    fun color(i: Int): Int = color[i]
}

// ---------------------------------------------------------------------------
// Shooting Star
// ---------------------------------------------------------------------------

/**
 * Shooting Star: a star streaks from one top corner (which one, the seed says)
 * down across the screen and off the far side in [FLIGHT_MILLIS], trailing
 * [TAIL] glittering sparks that follow its path a little behind and scatter
 * off it, and twinkle out after it has gone.
 */
object ShootingStarSpec {
    const val ID = "com.apple.messages.effect.CKShootingStarEffect"
    const val FLIGHT_MILLIS = 1500
    const val TAIL = 48
    const val HEAD_RADIUS_DP = 6f
    /** The head starts and ends this far outside the screen's width, as a share of it. */
    const val OVERSHOOT = 0.12f
    /** Where it crosses: these shares of the height at the start and the end. */
    const val START_Y = 0.08f
    const val END_Y = 0.42f
    /** A tail spark trails the head by between these, in seconds along its path. */
    const val LAG_MIN_S = 0.03f
    const val LAG_MAX_S = 0.4f
    /** ...and scatters this far off the path at most, in dp, the further back the more. */
    const val SCATTER_DP = 22f
    const val SPARK_RADIUS_MIN_DP = 1f
    const val SPARK_RADIUS_MAX_DP = 2.6f
    const val TWINKLE_HZ = 5f

    val spec = ScreenEffectSpec(ID, durationMillis = 2500, particles = TAIL, dims = false)

    /** How far along its flight the head is [millis] in. */
    fun flight(millis: Long): Float = progress(millis, 0, FLIGHT_MILLIS)

    /** The head's x in pixels across [width], left to right when [fromLeft], else mirrored. */
    fun headX(millis: Long, width: Float, fromLeft: Boolean): Float {
        val along = -OVERSHOOT + flight(millis) * (1f + 2f * OVERSHOOT)
        return (if (fromLeft) along else 1f - along) * width
    }

    fun headY(millis: Long, height: Float): Float = lerp(START_Y, END_Y, flight(millis)) * height

    /** The tail twinkles out over the second after the head has gone. */
    fun tailFade(millis: Long): Float = 1f - progress(millis, FLIGHT_MILLIS, spec.durationMillis - FLIGHT_MILLIS)
}

/** One shooting star's side and tail, by [seed]. */
class StarTail(seed: Long) {
    val count = ShootingStarSpec.TAIL
    private val rnd = Random(seed)
    /** Whether the star comes from the top-left corner (else the top-right). */
    val fromLeft: Boolean = rnd.nextBoolean()
    private val lag = FloatArray(count)
    private val scatter = FloatArray(count)
    private val radius = FloatArray(count)
    private val phase = FloatArray(count)
    private val color = IntArray(count)

    init {
        for (i in 0 until count) {
            lag[i] = rnd.between(ShootingStarSpec.LAG_MIN_S, ShootingStarSpec.LAG_MAX_S)
            scatter[i] = rnd.between(-1f, 1f)
            radius[i] = rnd.between(ShootingStarSpec.SPARK_RADIUS_MIN_DP, ShootingStarSpec.SPARK_RADIUS_MAX_DP)
            phase[i] = rnd.nextFloat()
            color[i] = EffectPalette.gold[rnd.nextInt(EffectPalette.gold.size)]
        }
    }

    fun lagSeconds(i: Int): Float = lag[i]
    fun radiusDp(i: Int): Float = radius[i]
    fun color(i: Int): Int = color[i]

    /** Spark [i]'s x: where the head was [lag] ago, scattered across the path (the path's normal is mostly vertical). */
    fun x(i: Int, millis: Long, width: Float, pxPerDp: Float): Float {
        val behind = millis - (lag[i] * 1000f).toLong()
        return ShootingStarSpec.headX(behind, width, fromLeft) + scatter[i] * lagShare(i) * ShootingStarSpec.SCATTER_DP * 0.3f * pxPerDp
    }

    fun y(i: Int, millis: Long, height: Float, pxPerDp: Float): Float {
        val behind = millis - (lag[i] * 1000f).toLong()
        return ShootingStarSpec.headY(behind, height) + scatter[i] * lagShare(i) * ShootingStarSpec.SCATTER_DP * pxPerDp
    }

    /** Dimmer the further back, twinkling, and out with the tail once the head has gone. */
    fun alpha(i: Int, millis: Long): Float {
        if (millis < (lag[i] * 1000f).toLong()) return 0f
        val twinkle = 0.55f + 0.45f * sin(TWO_PI * (phase[i] + millis / 1000f * ShootingStarSpec.TWINKLE_HZ))
        return (1f - 0.7f * lagShare(i)) * twinkle * ShootingStarSpec.tailFade(millis)
    }

    private fun lagShare(i: Int): Float = (lag[i] - ShootingStarSpec.LAG_MIN_S) / (ShootingStarSpec.LAG_MAX_S - ShootingStarSpec.LAG_MIN_S)
}

// ---------------------------------------------------------------------------
// Spotlight
// ---------------------------------------------------------------------------

/**
 * Spotlight: the chat dims except for a soft-edged circle that starts as wide
 * as the screen, closes onto the message's bubble in [FIND_MILLIS], holds
 * there, and lifts with the dimming at the end. The circle's final radius is
 * the bubble's half-diagonal plus [PAD_DP], so the whole bubble stays lit.
 */
object SpotlightSpec {
    const val ID = "com.apple.messages.effect.CKSpotlightEffect"
    const val FIND_MILLIS = 600
    const val HOLD_UNTIL_MILLIS = 1900
    const val PAD_DP = 20f
    /** The lit circle's radius in dp while the bubble has not said where it is (its first frame, or a bubble scrolled away). */
    const val FALLBACK_RADIUS_DP = 90f
    const val DIM_ALPHA = 0.78f
    /** The lit circle is fully clear to this share of its radius, then fades to the dimming at the edge. */
    const val SOFT_EDGE = 0.72f

    val spec = ScreenEffectSpec(ID, durationMillis = 2500, particles = 0, dims = true)

    /** 0: the circle covers the screen from its centre; 1: it sits on the bubble. */
    val find = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(FIND_MILLIS, 1f, EffectEase.OUT)))
    /** The dimming as a share of [DIM_ALPHA]: in quickly, held, lifted over the last 600 ms. */
    val dim = EffectTrack(listOf(EffectKey(0, 0f), EffectKey(250, 1f), EffectKey(HOLD_UNTIL_MILLIS, 1f), EffectKey(spec.durationMillis, 0f, EffectEase.IN_OUT)))

    /** The circle's radius [millis] in, closing from [screenRadius] (half the diagonal) onto [bubbleRadius]. */
    fun radius(millis: Long, screenRadius: Float, bubbleRadius: Float): Float = lerp(screenRadius, bubbleRadius, find.valueAt(millis))

    /** The circle's centre along one axis, moving from the screen's centre to the bubble's. */
    fun centre(millis: Long, screenCentre: Float, bubbleCentre: Float): Float = lerp(screenCentre, bubbleCentre, find.valueAt(millis))

    /** The radius that lights a bubble [widthPx] by [heightPx]: its half-diagonal and the padding. */
    fun bubbleRadius(widthPx: Float, heightPx: Float, pxPerDp: Float): Float = hypot(widthPx, heightPx) / 2f + PAD_DP * pxPerDp
}

// ---------------------------------------------------------------------------
// Echo
// ---------------------------------------------------------------------------

/**
 * Echo: the message's words, as [COPIES] small bubbles in its own colours,
 * rain from above the top edge to below the bottom over about three seconds,
 * each on its own lane, start, speed, size and tilt, and all fade together at
 * the end. A message without words rains plain bubble shapes.
 */
object EchoSpec {
    const val ID = "com.apple.messages.effect.CKEchoEffect"
    const val COPIES = 36
    /** Copies set off over this long, and each falls for between these: the last is through by 2900 ms. */
    const val START_SPREAD_MILLIS = 1000
    const val FALL_MIN_MILLIS = 1400
    const val FALL_MAX_MILLIS = 1900
    const val SCALE_MIN = 0.45f
    const val SCALE_MAX = 0.85f
    const val TILT_DEGREES = 12f
    const val SWAY_DP = 16f
    /** Every copy fades out from here to the end. */
    const val FADE_FROM_MILLIS = 2300
    /** A copy shows at most this much of the words, on one line. */
    const val MAX_CHARS = 40
    /** The plain bubble for a message with no words, in dp. */
    const val EMPTY_WIDTH_DP = 64f
    const val EMPTY_HEIGHT_DP = 36f

    val spec = ScreenEffectSpec(ID, durationMillis = 3000, particles = COPIES, dims = false)

    /** The alpha every copy shares [millis] in: full, then out from [FADE_FROM_MILLIS] to the end. */
    fun fade(millis: Long): Float = 1f - progress(millis, FADE_FROM_MILLIS, spec.durationMillis - FADE_FROM_MILLIS)

    /** The text a copy shows: the first line of [words], at most [MAX_CHARS] long with an ellipsis; null for no words. */
    fun copyText(words: String?): String? {
        val line = words?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS - 1).trimEnd() + "…"
    }
}

/** The copies of one Echo, laid out by [seed]. */
class EchoField(seed: Long) {
    val count = EchoSpec.COPIES
    private val lane = FloatArray(count)
    private val start = IntArray(count)
    private val fall = IntArray(count)
    private val scale = FloatArray(count)
    private val tilt = FloatArray(count)
    private val phase = FloatArray(count)

    init {
        val rnd = Random(seed)
        for (i in 0 until count) {
            lane[i] = rnd.between(0.08f, 0.92f)
            start[i] = (rnd.nextFloat() * EchoSpec.START_SPREAD_MILLIS).toInt()
            fall[i] = (EchoSpec.FALL_MIN_MILLIS + rnd.nextFloat() * (EchoSpec.FALL_MAX_MILLIS - EchoSpec.FALL_MIN_MILLIS)).toInt()
            scale[i] = rnd.between(EchoSpec.SCALE_MIN, EchoSpec.SCALE_MAX)
            tilt[i] = rnd.between(-EchoSpec.TILT_DEGREES, EchoSpec.TILT_DEGREES)
            phase[i] = rnd.nextFloat()
        }
    }

    fun startMillis(i: Int): Int = start[i]
    fun fallMillis(i: Int): Int = fall[i]
    fun scale(i: Int): Float = scale[i]
    fun tiltDegrees(i: Int): Float = tilt[i]

    /** Whether copy [i] has started falling. */
    fun started(i: Int, millis: Long): Boolean = millis >= start[i]

    /** Copy [i]'s centre x in pixels: its lane across [width], swaying once over its fall. */
    fun x(i: Int, millis: Long, width: Float, pxPerDp: Float): Float =
        lane[i] * width + sin(TWO_PI * (phase[i] + progress(millis, start[i], fall[i]))) * EchoSpec.SWAY_DP * pxPerDp

    /** Copy [i]'s centre y in pixels: from wholly above a screen [height] tall to wholly below it, [copyHeight] being its own. */
    fun y(i: Int, millis: Long, height: Float, copyHeight: Float): Float =
        -copyHeight + progress(millis, start[i], fall[i]) * (height + 2f * copyHeight)
}

// ---------------------------------------------------------------------------
// The catalogue, and the overlay's state rule
// ---------------------------------------------------------------------------

object ScreenEffectSpecs {
    /** The nine, in the picker's order. ScreenEffectSpecsTest holds this against MessageEffects.screen, so a new id cannot go unpainted. */
    val all: List<ScreenEffectSpec> = listOf(
        EchoSpec.spec, SpotlightSpec.spec, BalloonsSpec.spec, ConfettiSpec.spec, LoveSpec.spec,
        LasersSpec.spec, FireworksSpec.spec, ShootingStarSpec.spec, CelebrationSpec.spec,
    )

    private val byId: Map<String, ScreenEffectSpec> = all.associateBy { it.id }

    /** The spec of a screen effect, or null for a bubble effect or an id the app does not know. */
    fun spec(effectId: String): ScreenEffectSpec? = byId[effectId]
}

/**
 * How the overlay's one screen play comes and goes. The controller holds the
 * play asked for last; its painter runs for the spec's duration and reports
 * screenDone; a Replay (or another message's effect) while one runs replaces
 * the play with a new serial, which restarts the painters, and the older
 * painter's screenDone must then leave the new play alone.
 */
object ScreenPlayRules {
    /** The play the controller holds after [finished] reports done while [current] is held: cleared when they are the same play, [current] otherwise. */
    fun afterDone(current: EffectPlay?, finished: EffectPlay): EffectPlay? = if (current == finished) null else current

    /** Whether the painters start over when the held play goes from [before] to [after]: a play with a serial not seen before. */
    fun restarts(before: EffectPlay?, after: EffectPlay?): Boolean = after != null && before?.serial != after.serial
}
