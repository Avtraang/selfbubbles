package io.github.avtraang.selfbubbles

// The engine that plays iMessage effects in the open chat: the controller a bubble reaches through
// LocalEffectController (play on first sight, Replay, what is playing now, where the playing bubble
// is), the phone's memory of what has played (EffectHistory, SharedPreferences), the overlay that
// paints the screen effects over the list (ScreenEffectOverlay, drawing with ScreenEffectPainters.kt),
// and the Compose side of the bubble effects: the frame loop that drives a bubble's graphicsLayer
// along a BubbleEffectSpec, and the Invisible Ink state and shimmer. The rules and the specs
// themselves are plain Kotlin in EffectPlayback.kt and ScreenEffectSpecs.kt.

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

/**
 * One play of an effect: on the bubble of [guid], or over the conversation for
 * a screen effect. [serial] tells a Replay from the play before it on the same
 * message, so the bubble (or the overlay's painters) restarts and a finished
 * play removes only itself. [words] and [mine] are what some screen painters
 * show: the message's words as its bubble shows them (null for attachments
 * only), which Echo rains, and whether it is the owner's, which picks the
 * bubble colours Echo and Love draw in.
 */
data class EffectPlay(
    val guid: String,
    val effectId: String,
    val serial: Int,
    val words: String? = null,
    val mine: Boolean = false,
)

/**
 * What the bubbles of a chat and the screen overlay observe and ask. Provided
 * by the Conversation screen; everywhere else a Bubble is drawn (the compose
 * screen's history) [NoEffects] stands in and nothing moves.
 */
@Stable
interface EffectController {
    /** False under the owner's "Remove animations": nothing plays and no Replay is offered (PlaybackRules). */
    val animationsOn: Boolean

    /** The screen effect playing over the chat, for the overlay, until its painter reports [screenDone]; null for none. */
    val screenPlay: EffectPlay?

    /** Where the bubble of [screenPlay] last said its parts are, in root coordinates ([reportBounds]); null until it has. */
    val screenBounds: Rect?

    /** The play running on the bubble of [guid], or null. */
    fun bubblePlay(guid: String): EffectPlay?

    /** Plays [effectId] on the message [m] now, and remembers that it has played. */
    fun play(m: Msg, effectId: String)

    /** The bubble has finished (or dropped) [play]: it is no longer running. */
    fun bubbleDone(play: EffectPlay)

    /** The overlay has finished (or dropped) [play]: it is no longer running, and the overlay goes. */
    fun screenDone(play: EffectPlay)

    /** The bubble of [guid] says where its parts are (root coordinates), while its screen effect plays; ignored for any other. */
    fun reportBounds(guid: String, bounds: Rect)

    /** Plays the effect of [m] when it is new on this phone (PlaybackRules.autoPlays); true when it did. */
    fun playIfUnseen(m: Msg): Boolean
}

/** The default: a Bubble composed outside the Conversation screen renders as it always has. */
object NoEffects : EffectController {
    override val animationsOn: Boolean get() = false
    override val screenPlay: EffectPlay? get() = null
    override val screenBounds: Rect? get() = null
    override fun bubblePlay(guid: String): EffectPlay? = null
    override fun play(m: Msg, effectId: String) {}
    override fun bubbleDone(play: EffectPlay) {}
    override fun screenDone(play: EffectPlay) {}
    override fun reportBounds(guid: String, bounds: Rect) {}
    override fun playIfUnseen(m: Msg): Boolean = false
}

val LocalEffectController = staticCompositionLocalOf<EffectController> { NoEffects }

/**
 * The Conversation screen's controller: one per open chat, re-made if the
 * owner flips "Remove animations" and comes back ([rememberEffectController]).
 */
private class ConversationEffects(
    private val history: EffectHistory,
    private val animatorScale: Float,
) : EffectController {
    override val animationsOn: Boolean = PlaybackRules.animationsOn(animatorScale)
    private val bubbles = mutableStateMapOf<String, EffectPlay>()
    private var screen by mutableStateOf<EffectPlay?>(null)
    private var bounds by mutableStateOf<Rect?>(null)
    private var serial = 0

    override val screenPlay: EffectPlay? get() = screen
    override val screenBounds: Rect? get() = bounds
    override fun bubblePlay(guid: String): EffectPlay? = bubbles[guid]

    override fun play(m: Msg, effectId: String) {
        val effect = MessageEffects.byId(effectId) ?: return
        history.markPlayed(m.guid)
        // Remembered but never drawn with animations off (so no screen effect is recorded as playing either): no
        // Replay is offered then, so this only guards a stale caller. Invisible Ink's play is the concealment on
        // its bubble; nothing to start.
        if (!animationsOn || effectId == MessageEffects.INVISIBLE_INK) return
        val play = EffectPlay(m.guid, effectId, ++serial, words = bubbleBody(m), mine = m.is_from_me)
        when (effect.kind) {
            EffectKind.BUBBLE -> bubbles[m.guid] = play
            EffectKind.SCREEN -> {
                // A new play while one runs replaces it (the overlay restarts on the serial). The bounds of another
                // message's bubble are not this one's: cleared until the new bubble reports.
                if (screen?.guid != m.guid) bounds = null
                screen = play
            }
        }
    }

    override fun bubbleDone(play: EffectPlay) {
        if (bubbles[play.guid] == play) bubbles.remove(play.guid)
    }

    override fun screenDone(play: EffectPlay) {
        val next = ScreenPlayRules.afterDone(screen, play)
        if (next === screen) return
        screen = next
        if (next == null) bounds = null
    }

    override fun reportBounds(guid: String, bounds: Rect) {
        if (screen?.guid == guid) this.bounds = bounds
    }

    override fun playIfUnseen(m: Msg): Boolean {
        val effect = m.effect ?: return false
        if (!PlaybackRules.autoPlays(m, history.since, history.played, animatorScale)) return false
        play(m, effect)
        return true
    }
}

/** The phone's animator duration scale: 0 under "Remove animations"; 1 when it cannot be read. */
private fun animatorScaleOf(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/** The controller for the chat on screen. Reads the animator scale again on each resume: the owner may have changed it in Settings. */
@Composable
fun rememberEffectController(): EffectController {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(animatorScaleOf(ctx)) }
    LifecycleResumeEffect(Unit) {
        scale = animatorScaleOf(ctx)
        onPauseOrDispose {}
    }
    return remember(scale) {
        EffectHistory.init(ctx)
        ConversationEffects(EffectHistory, scale)
    }
}

/**
 * Paints the screen effect [controller] is playing (Confetti, Lasers, ...:
 * ScreenEffectPainters.kt) over the message list and under the search overlay,
 * and composes nothing at all while none plays, so the full-size Box is not in
 * the tree then. The painters are keyed on the play's serial, so a Replay (or
 * another message's effect while one runs) starts them over; each calls
 * [EffectController.screenDone] when its spec's duration is up, or when it
 * leaves composition (the chat closed), and the overlay goes with the play.
 * No pointer input anywhere in it: taps and scrolls reach the list through it.
 */
@Composable
fun ScreenEffectOverlay(controller: EffectController, modifier: Modifier = Modifier) {
    val play = controller.screenPlay ?: return
    // Where the overlay sits in the root, so the bubble's bounds (reported in root coordinates) read in its own.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        key(play.serial) { ScreenEffectPainter(play, controller, origin) }
    }
}

// ---------------------------------------------------------------------------
// What has played
// ---------------------------------------------------------------------------

/**
 * Which effects have played on this phone, in SharedPreferences "effects":
 * the moment the feature first ran ([since], unix seconds; every message dated
 * before it is history and never plays by itself) and the newest
 * [PlaybackRules.PLAYED_CAP] guids that have played, in order. Call [init]
 * before reading; a no-op after the first call.
 */
object EffectHistory {
    private const val FILE = "effects"
    private const val KEY_SINCE_MILLIS = "effects_since"
    private const val KEY_PLAYED = "played"

    private var prefs: SharedPreferences? = null
    private var list: List<String> = emptyList()

    /** Until [init], nothing is dated after "forever": no effect plays by itself. */
    var since: Double = Double.MAX_VALUE
        private set
    var played: Set<String> = emptySet()
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        // First run of the feature: the line between history, which stays quiet, and what arrives from now on.
        if (!p.contains(KEY_SINCE_MILLIS)) p.edit().putLong(KEY_SINCE_MILLIS, System.currentTimeMillis()).apply()
        since = p.getLong(KEY_SINCE_MILLIS, Long.MAX_VALUE) / 1000.0
        list = playedDecode(runCatching { p.getString(KEY_PLAYED, null) }.getOrNull())
        played = list.toHashSet()
    }

    fun markPlayed(guid: String) {
        list = PlaybackRules.rememberPlayed(list, guid)
        played = list.toHashSet()
        prefs?.edit()?.putString(KEY_PLAYED, playedEncode(list))?.apply()
    }
}

// ---------------------------------------------------------------------------
// Slam, Loud, Gentle on a bubble
// ---------------------------------------------------------------------------

/**
 * The values a bubble's graphicsLayer reads while a bubble effect plays, each
 * its own float state so a frame invalidates the draw and nothing recomposes.
 * They start where the spec starts (the first drawn frame is already the big
 * Slam bubble, not a flash of the resting one) and [rest] puts them back.
 */
@Stable
class BubbleEffectValues internal constructor(private val spec: BubbleEffectSpec, mine: Boolean) {
    val scale = mutableFloatStateOf(spec.scale.start)
    val rotation = mutableFloatStateOf(spec.rotation.start)
    val alpha = mutableFloatStateOf(spec.alpha.start)
    val joltPx = mutableFloatStateOf(0f)

    /** The bubble's corner on the sender's side (its tail) for Slam and Gentle, its centre for Loud. */
    val origin: TransformOrigin =
        if (spec.fromTail) TransformOrigin(if (mine) 1f else 0f, 1f) else TransformOrigin(0.5f, 0.5f)

    internal fun at(millis: Long, pxPerDp: Float) {
        scale.floatValue = spec.scale.valueAt(millis)
        rotation.floatValue = spec.rotation.valueAt(millis)
        alpha.floatValue = spec.alpha.valueAt(millis)
        joltPx.floatValue = spec.joltDp.valueAt(millis) * pxPerDp
    }

    /** Where the bubble lives when nothing plays: the specs end here too, but a cancelled play must land here as well. */
    internal fun rest() {
        scale.floatValue = 1f
        rotation.floatValue = 0f
        alpha.floatValue = 1f
        joltPx.floatValue = 0f
    }
}

/**
 * Drives the bubble effect of [play] (Slam, Loud or Gentle; null for no play,
 * for Invisible Ink and for an id without a spec) along its spec, one frame at
 * a time, and tells [controller] when it is over. Leaving composition (the row
 * scrolled away) cancels the loop; either way the values end at rest and the
 * play is dropped, so the bubble never stays transformed and does not start
 * over when it scrolls back in.
 */
@Composable
internal fun rememberBubbleEffect(play: EffectPlay?, controller: EffectController, mine: Boolean): BubbleEffectValues? {
    val spec = play?.let { EffectSpecs.bubbleSpec(it.effectId) } ?: return null
    val values = remember(play) { BubbleEffectValues(spec, mine) }
    val pxPerDp = LocalDensity.current.density
    LaunchedEffect(play) {
        try {
            val t0 = withFrameNanos { it }
            while (true) {
                val millis = (withFrameNanos { it } - t0) / 1_000_000
                values.at(millis, pxPerDp)
                if (millis >= spec.durationMillis) break
            }
        } finally {
            values.rest()
            controller.bubbleDone(play)
        }
    }
    return values
}

// ---------------------------------------------------------------------------
// Invisible Ink
// ---------------------------------------------------------------------------

/**
 * One Invisible Ink bubble's state: whether its words are hidden ([concealed]:
 * what the semantics and the chips under the bubble follow), how much of the
 * shimmer covers them right now ([cover], 1 hidden to 0 shown, cross-fading
 * between), the particles and the clock their drift reads.
 */
@Stable
class InvisibleInkState internal constructor(internal val field: InkField) {
    var concealed by mutableStateOf(true)
        internal set
    internal val cover = Animatable(1f)
    internal val seconds = mutableFloatStateOf(0f)

    /** A tap on the bubble: the words come out, and hide again after [InvisibleInkSpec.REVEAL_MILLIS]. */
    fun reveal() { concealed = false }
}

/**
 * The Invisible Ink state of the message [guid], concealed to begin with. With
 * [animationsOn] the shimmer drifts and the reveal cross-fades; without, the
 * particles stand still and the words switch. The drift's frame loop runs only
 * while this is composed (the row on screen) and something of the shimmer is
 * showing.
 */
@Composable
internal fun rememberInvisibleInk(guid: String, animationsOn: Boolean): InvisibleInkState {
    val state = remember(guid) { InvisibleInkState(InkField(InvisibleInkSpec.MAX_PARTICLES, guid.hashCode().toLong())) }
    LaunchedEffect(state, state.concealed, animationsOn) {
        val target = if (state.concealed) 1f else 0f
        state.cover.animateTo(target, if (animationsOn) tween(InvisibleInkSpec.FADE_MILLIS) else snap())
        if (!state.concealed) {
            delay(InvisibleInkSpec.REVEAL_MILLIS - InvisibleInkSpec.FADE_MILLIS)
            state.concealed = true
        }
    }
    // A boolean derived from the animated cover, so the loop is started and stopped without recomposing each frame.
    val shimmering by remember(state) { derivedStateOf { state.cover.value > 0f } }
    LaunchedEffect(state, shimmering, animationsOn) {
        if (!shimmering || !animationsOn) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        while (true) {
            state.seconds.floatValue = (withFrameNanos { it } - t0) / 1_000_000_000f
        }
    }
    return state
}

/**
 * Hides this content under [state]'s shimmer in [color] (the bubble's own text
 * colour): the content is drawn at 1 - cover, the particles over it at cover.
 * The draw lambdas read state only; a frame allocates nothing.
 */
internal fun Modifier.invisibleInk(state: InvisibleInkState, color: Color): Modifier = this
    .drawWithContent {
        drawContent()
        val cover = state.cover.value
        if (cover > 0f) drawInk(state.field, state.seconds.floatValue, color, cover)
    }
    .graphicsLayer { alpha = 1f - state.cover.value }

private fun DrawScope.drawInk(field: InkField, seconds: Float, color: Color, cover: Float) {
    val w = size.width
    val h = size.height
    val pxPerDp = density
    val n = InvisibleInkSpec.particleCount(w / pxPerDp, h / pxPerDp).coerceAtMost(field.count)
    val rMin = InvisibleInkSpec.RADIUS_MIN_DP * pxPerDp
    val rSpan = (InvisibleInkSpec.RADIUS_MAX_DP - InvisibleInkSpec.RADIUS_MIN_DP) * pxPerDp
    for (i in 0 until n) {
        drawCircle(
            color = color,
            radius = rMin + field.radius(i) * rSpan,
            center = Offset(field.x(i, seconds, w, pxPerDp), field.y(i, seconds, h, pxPerDp)),
            alpha = field.alpha(i, seconds) * cover,
        )
    }
}
