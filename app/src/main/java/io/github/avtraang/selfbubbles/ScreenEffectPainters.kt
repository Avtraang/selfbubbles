package io.github.avtraang.selfbubbles

// The nine screen effects painted over the chat. ScreenEffectOverlay (EffectPlayer.kt) composes
// ScreenEffectPainter for the play the chat's controller holds, keyed on the play's serial; this file
// is what it draws. Each painter is a Canvas over the whole overlay that reads a clock (milliseconds
// into the play, one state the frame loop writes) and a field its spec laid out once for the play
// (ScreenEffectSpecs.kt, plain Kotlin), so a frame is arithmetic on the field's arrays and draw calls,
// nothing allocated. Confetti alone is Konfetti's KonfettiView. Every loop ends at its spec's duration
// and tells the controller (screenDone), as does leaving composition; no painter takes pointer input,
// so a tap or a scroll goes through the overlay to the list.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Paints [play] over the chat: the painter of its effect, run by one frame
 * clock that ends the play at its spec's duration. [origin] is where the
 * overlay sits in the root, so the bubble's bounds (reported in root
 * coordinates through [controller]) can be read in the overlay's own.
 */
@Composable
internal fun ScreenEffectPainter(play: EffectPlay, controller: EffectController, origin: Offset) {
    val spec = ScreenEffectSpecs.spec(play.effectId)
    if (spec == null) {
        // A screen effect the catalogue knows but no painter draws (none today: ScreenEffectSpecsTest holds the
        // two lists together). Over at once, so the overlay does not stay up on it.
        LaunchedEffect(play) { controller.screenDone(play) }
        return
    }
    val clock = rememberScreenClock(play, spec, controller)
    // The layout of the particles follows the message and the play, so a Replay is not the same shower twice.
    val seed = play.guid.hashCode().toLong() * 31 + play.serial
    when (spec.id) {
        ConfettiSpec.ID -> ConfettiPainter(clock)
        CelebrationSpec.ID -> CelebrationPainter(clock, seed)
        BalloonsSpec.ID -> BalloonsPainter(clock, seed)
        LoveSpec.ID -> LovePainter(clock, seed, play.mine, controller, origin)
        LasersSpec.ID -> LasersPainter(clock, seed, controller, origin)
        FireworksSpec.ID -> FireworksPainter(clock, seed)
        ShootingStarSpec.ID -> ShootingStarPainter(clock, seed)
        SpotlightSpec.ID -> SpotlightPainter(clock, controller, origin)
        EchoSpec.ID -> EchoPainter(clock, seed, play.words, play.mine)
    }
}

/**
 * Milliseconds into [play], written each frame; the draw lambdas read it, so a
 * frame invalidates the Canvas and nothing recomposes. The loop ends at the
 * spec's duration, and either way (the chat closed, a Replay keying this
 * painter out) [controller] hears that the play is over.
 */
@Composable
private fun rememberScreenClock(play: EffectPlay, spec: ScreenEffectSpec, controller: EffectController): LongState {
    val clock = remember(play) { mutableLongStateOf(0L) }
    LaunchedEffect(play) {
        try {
            val t0 = withFrameNanos { it }
            while (true) {
                val millis = (withFrameNanos { it } - t0) / 1_000_000
                clock.longValue = millis
                if (millis >= spec.durationMillis) break
            }
        } finally {
            controller.screenDone(play)
        }
    }
    return clock
}

/** An opaque Color of a palette entry (0xRRGGBB). */
private fun rgb(c: Int): Color = Color(0xFF000000L or c.toLong())

/** Where the playing bubble is taken to be while it has not reported: the lower part of the list, where the newest message sits. */
private const val ANCHOR_FALLBACK_Y = 0.8f

/** The playing bubble's centre x in this overlay's pixels, or the middle while the bubble has not said. */
private fun DrawScope.anchorX(bounds: Rect?, origin: Offset): Float =
    if (bounds != null) bounds.center.x - origin.x else size.width / 2f

private fun DrawScope.anchorY(bounds: Rect?, origin: Offset): Float =
    if (bounds != null) bounds.center.y - origin.y else size.height * ANCHOR_FALLBACK_Y

// ---------------------------------------------------------------------------
// Confetti
// ---------------------------------------------------------------------------

/**
 * Konfetti rains the paper from the whole top edge; its own frame loop runs
 * while this is composed. The overlay's hold outlasts the shower
 * (ConfettiSpec), and the view fades with [clock] over the last moments, so a
 * piece the library still shows goes out with the overlay, not in one frame.
 */
@Composable
private fun ConfettiPainter(clock: LongState) {
    val parties = remember {
        listOf(
            Party(
                angle = ConfettiSpec.ANGLE_DEGREES,
                spread = ConfettiSpec.SPREAD_DEGREES,
                speed = ConfettiSpec.SPEED_MIN,
                maxSpeed = ConfettiSpec.SPEED_MAX,
                damping = ConfettiSpec.DAMPING,
                colors = EffectPalette.party.toList(),
                shapes = listOf(Shape.Square, Shape.Circle, Shape.Rectangle(ConfettiSpec.STRIP_RATIO)),
                timeToLive = ConfettiSpec.TIME_TO_LIVE_MILLIS,
                position = Position.Relative(0.0, 0.0).between(Position.Relative(1.0, 0.0)),
                emitter = Emitter(ConfettiSpec.EMIT_MILLIS.toLong(), TimeUnit.MILLISECONDS).perSecond(ConfettiSpec.PER_SECOND),
            ),
        )
    }
    // The alpha is read in the layer block, so a frame changes the layer and recomposes nothing.
    KonfettiView(Modifier.fillMaxSize().graphicsLayer { alpha = ConfettiSpec.exit.valueAt(clock.longValue) }, parties = parties)
}

// ---------------------------------------------------------------------------
// Celebration
// ---------------------------------------------------------------------------

@Composable
private fun CelebrationPainter(clock: LongState, seed: Long) {
    val field = remember(seed) { CelebrationField(seed) }
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val arm = CelebrationSpec.ARM_DP * px
        val stroke = CelebrationSpec.STROKE_DP * px
        for (i in 0 until field.count) {
            if (!field.alive(i, millis)) continue
            drawSpark(rgb(field.color(i)), field.x(i, millis, size.width, px), field.y(i, millis, px), arm, stroke, field.alpha(i, millis))
        }
    }
}

/** A four-point star: a cross at full length, a fainter one across it at half. */
private fun DrawScope.drawSpark(color: Color, x: Float, y: Float, arm: Float, stroke: Float, alpha: Float) {
    drawLine(color, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = stroke, cap = StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = stroke, cap = StrokeCap.Round, alpha = alpha)
    val d = arm * 0.5f
    drawLine(color, Offset(x - d, y - d), Offset(x + d, y + d), strokeWidth = stroke * 0.7f, cap = StrokeCap.Round, alpha = alpha * 0.8f)
    drawLine(color, Offset(x - d, y + d), Offset(x + d, y - d), strokeWidth = stroke * 0.7f, cap = StrokeCap.Round, alpha = alpha * 0.8f)
}

// ---------------------------------------------------------------------------
// Balloons
// ---------------------------------------------------------------------------

@Composable
private fun BalloonsPainter(clock: LongState, seed: Long) {
    val field = remember(seed) { BalloonField(seed) }
    val string = MaterialTheme.colorScheme.onSurfaceVariant
    // The shine on each balloon: white in both themes, as the glyphs over a scrim are.
    val shine = MessagesTheme.colors.onScrim
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val stringPx = BalloonsSpec.STRING_DP * px
        val stringWidth = BalloonsSpec.STRING_WIDTH_DP * px
        for (i in 0 until field.count) {
            if (millis < field.startMillis(i)) continue
            val r = field.radiusDp(i) * px
            val rh = r * BalloonsSpec.ASPECT
            val cx = field.x(i, millis, size.width, px)
            val cy = field.y(i, millis, size.height, px)
            val knotY = cy + rh
            // The string trails the sway: it bows toward where the balloon was a moment ago, in three segments.
            val bow = (cx - field.x(i, millis - 150, size.width, px)) * 0.8f
            var x0 = cx
            var y0 = knotY
            for (k in 1..3) {
                val t = k / 3f
                val x1 = cx - bow * t * t
                val y1 = knotY + stringPx * t
                drawLine(string, Offset(x0, y0), Offset(x1, y1), strokeWidth = stringWidth, alpha = 0.7f)
                x0 = x1
                y0 = y1
            }
            val color = rgb(field.color(i))
            drawOval(color, topLeft = Offset(cx - r, cy - rh), size = Size(2f * r, 2f * rh))
            drawCircle(color, radius = r * 0.16f, center = Offset(cx, knotY))
            drawOval(shine, topLeft = Offset(cx - r * 0.6f, cy - rh * 0.7f), size = Size(r * 0.42f, rh * 0.5f), alpha = 0.35f)
        }
    }
}

// ---------------------------------------------------------------------------
// Love
// ---------------------------------------------------------------------------

@Composable
private fun LovePainter(clock: LongState, seed: Long, mine: Boolean, controller: EffectController, origin: Offset) {
    val field = remember(seed) { LoveField(seed) }
    val heart = remember { heartPath() }
    val colors = MessagesTheme.colors
    val fill = if (mine) colors.bubbleIMessage else colors.bubbleReceived
    val edge = if (mine) colors.onBubbleIMessage else colors.onBubbleReceived
    // The edge's width in the unit heart's own scale: 3% of the heart, whatever its size.
    val edgeStroke = remember { Stroke(width = 0.03f) }
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val bounds = controller.screenBounds
        val cx = anchorX(bounds, origin)
        val cy = anchorY(bounds, origin)
        for (i in 0 until field.count) {
            val a = field.alpha(i, millis)
            if (a <= 0f) continue
            drawHeart(heart, fill, edge, edgeStroke, field.x(i, millis, cx, px), field.y(i, millis, cy, px), field.sizeDp(i) * px, a)
        }
        val size = LoveSpec.bigScale.valueAt(millis) * LoveSpec.BIG_SIZE_DP * px
        val a = LoveSpec.bigAlpha.valueAt(millis)
        if (a > 0f) drawHeart(heart, fill, edge, edgeStroke, cx, cy - LoveSpec.bigRise.valueAt(millis) * LoveSpec.BIG_RISE_DP * px, size, a)
    }
}

/** A heart in the unit square, point down: two cubic lobes. Built once per play and scaled into place. */
private fun heartPath(): Path = Path().apply {
    moveTo(0.5f, 0.32f)
    cubicTo(0.5f, 0.24f, 0.4f, 0.1f, 0.26f, 0.1f)
    cubicTo(0.1f, 0.1f, 0f, 0.23f, 0f, 0.38f)
    cubicTo(0f, 0.62f, 0.28f, 0.8f, 0.5f, 0.96f)
    cubicTo(0.72f, 0.8f, 1f, 0.62f, 1f, 0.38f)
    cubicTo(1f, 0.23f, 0.9f, 0.1f, 0.74f, 0.1f)
    cubicTo(0.6f, 0.1f, 0.5f, 0.24f, 0.5f, 0.32f)
    close()
}

/** [heart] filled in the bubble's colour, [sizePx] across, centred on ([cx], [cy]). The transforms are inline: no lambda per frame. */
private fun DrawScope.drawHeart(heart: Path, fill: Color, edge: Color, edgeStroke: Stroke, cx: Float, cy: Float, sizePx: Float, alpha: Float) {
    if (sizePx <= 0f) return
    translate(cx - sizePx / 2f, cy - sizePx / 2f) {
        scale(sizePx, sizePx, pivot = Offset.Zero) {
            drawPath(heart, fill, alpha = alpha)
            // A faint edge in the bubble's text colour, so a received (grey) heart still has one on a white screen,
            // as a white photo gets a hairline frame.
            drawPath(heart, edge, alpha = alpha * 0.35f, style = edgeStroke)
        }
    }
}

// ---------------------------------------------------------------------------
// Lasers
// ---------------------------------------------------------------------------

@Composable
private fun LasersPainter(clock: LongState, seed: Long, controller: EffectController, origin: Offset) {
    val fan = remember(seed) { LaserFan(seed) }
    val scrim = MaterialTheme.colorScheme.scrim
    val core = MessagesTheme.colors.onScrim
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val fade = LasersSpec.fade.valueAt(millis)
        if (fade <= 0f) return@Canvas
        drawRect(scrim, alpha = fade * LasersSpec.DIM_ALPHA)
        val bounds = controller.screenBounds
        val ox = anchorX(bounds, origin)
        val oy = (if (bounds != null) bounds.top - origin.y else size.height * ANCHOR_FALLBACK_Y) - LasersSpec.ORIGIN_ABOVE_BUBBLE_DP * px
        val w = size.width
        val h = size.height
        val glow = LasersSpec.GLOW_DP * px
        val width = LasersSpec.WIDTH_DP * px
        for (i in 0 until fan.count) {
            val end = Offset(fan.endX(i, millis, ox, w, h), fan.endY(i, millis, oy, w, h))
            val color = rgb(fan.color(i))
            drawLine(color, Offset(ox, oy), end, strokeWidth = glow, cap = StrokeCap.Round, alpha = fade * 0.25f)
            drawLine(color, Offset(ox, oy), end, strokeWidth = width, cap = StrokeCap.Round, alpha = fade)
            drawLine(core, Offset(ox, oy), end, strokeWidth = width * 0.35f, cap = StrokeCap.Round, alpha = fade * 0.7f)
        }
    }
}

// ---------------------------------------------------------------------------
// Fireworks
// ---------------------------------------------------------------------------

@Composable
private fun FireworksPainter(clock: LongState, seed: Long) {
    val field = remember(seed) { FireworksField(seed) }
    val scrim = MaterialTheme.colorScheme.scrim
    val flash = MessagesTheme.colors.onScrim
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        drawRect(scrim, alpha = FireworksSpec.dim.valueAt(millis) * FireworksSpec.DIM_ALPHA)
        val w = size.width
        val h = size.height
        for (b in 0 until field.bursts) {
            val f = field.flash(b, millis)
            if (f > 0f) drawCircle(flash, radius = (1f - f) * FireworksSpec.FLASH_RADIUS_DP * px, center = Offset(field.burstX(b, w), field.burstY(b, h)), alpha = f * 0.8f)
        }
        val r = FireworksSpec.RADIUS_DP * px
        for (i in 0 until field.count) {
            if (!field.alive(i, millis)) continue
            val x = field.x(i, millis, w, px)
            val y = field.y(i, millis, h, px)
            val a = field.alpha(i, millis)
            val color = rgb(field.color(i))
            // The trail: from where the spark was a few frames ago.
            val back = millis - FireworksSpec.TRAIL_MILLIS
            if (field.alive(i, back)) {
                drawLine(color, Offset(field.x(i, back, w, px), field.y(i, back, h, px)), Offset(x, y), strokeWidth = r, cap = StrokeCap.Round, alpha = a * 0.5f)
            }
            drawCircle(color, radius = r, center = Offset(x, y), alpha = a)
        }
    }
}

// ---------------------------------------------------------------------------
// Shooting Star
// ---------------------------------------------------------------------------

@Composable
private fun ShootingStarPainter(clock: LongState, seed: Long) {
    val tail = remember(seed) { StarTail(seed) }
    // The head: a gold glow (visible on the light theme's white too) around a white core.
    val glow = rgb(EffectPalette.gold[0])
    val core = MessagesTheme.colors.onScrim
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val w = size.width
        val h = size.height
        for (i in 0 until tail.count) {
            val a = tail.alpha(i, millis)
            if (a <= 0f) continue
            drawCircle(rgb(tail.color(i)), radius = tail.radiusDp(i) * px, center = Offset(tail.x(i, millis, w, px), tail.y(i, millis, h, px)), alpha = a)
        }
        if (millis <= ShootingStarSpec.FLIGHT_MILLIS) {
            val head = Offset(ShootingStarSpec.headX(millis, w, tail.fromLeft), ShootingStarSpec.headY(millis, h))
            val r = ShootingStarSpec.HEAD_RADIUS_DP * px
            drawCircle(glow, radius = r * 2.4f, center = head, alpha = 0.45f)
            drawCircle(glow, radius = r * 1.3f, center = head, alpha = 0.8f)
            drawCircle(core, radius = r, center = head)
        }
    }
}

// ---------------------------------------------------------------------------
// Spotlight
// ---------------------------------------------------------------------------

@Composable
private fun SpotlightPainter(clock: LongState, controller: EffectController, origin: Offset) {
    val scrim = MaterialTheme.colorScheme.scrim
    // The lit circle is cut out of the dimming with DstOut (the destination's alpha goes down by the source's),
    // so the colour here is only an alpha mask: opaque to the soft edge, then fading to nothing. A unit gradient,
    // scaled to the circle's radius each frame, so no brush is made per frame.
    val hole = remember {
        Brush.radialGradient(0f to Color.Black, SpotlightSpec.SOFT_EDGE to Color.Black, 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
    }
    // Composited offscreen: the DstOut must take alpha out of this layer's dimming, not out of the chat under it.
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val millis = clock.longValue
        val px = density
        val dim = SpotlightSpec.dim.valueAt(millis) * SpotlightSpec.DIM_ALPHA
        if (dim <= 0f) return@Canvas
        drawRect(scrim, alpha = dim)
        val bounds = controller.screenBounds
        val screenR = hypot(size.width, size.height) / 2f
        val bubbleR = if (bounds != null) SpotlightSpec.bubbleRadius(bounds.width, bounds.height, px) else SpotlightSpec.FALLBACK_RADIUS_DP * px
        val cx = SpotlightSpec.centre(millis, size.width / 2f, anchorX(bounds, origin))
        val cy = SpotlightSpec.centre(millis, size.height / 2f, anchorY(bounds, origin))
        val r = SpotlightSpec.radius(millis, screenR, bubbleR)
        translate(cx, cy) {
            scale(r, r, pivot = Offset.Zero) {
                drawCircle(hole, radius = 1f, center = Offset.Zero, blendMode = BlendMode.DstOut)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Echo
// ---------------------------------------------------------------------------

@Composable
private fun EchoPainter(clock: LongState, seed: Long, words: String?, mine: Boolean) {
    val field = remember(seed) { EchoField(seed) }
    val colors = MessagesTheme.colors
    val fill = if (mine) colors.bubbleIMessage else colors.bubbleReceived
    val ink = if (mine) colors.onBubbleIMessage else colors.onBubbleReceived
    val measurer = rememberTextMeasurer()
    // Spec C6: bubble text is bodyLarge; a copy is that text at a smaller scale, laid out once for the play.
    val style = MaterialTheme.typography.bodyLarge
    val localDensity = LocalDensity.current
    val copy = remember(words) { EchoSpec.copyText(words) }
    val layout = remember(copy, style, localDensity) {
        copy?.let {
            measurer.measure(
                AnnotatedString(it), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = with(localDensity) { (Dimens.bubbleMaxWidth - Dimens.bubblePadH * 2).roundToPx() }),
            )
        }
    }
    val padH = with(localDensity) { Dimens.bubblePadH.toPx() }
    val padV = with(localDensity) { Dimens.bubblePadV.toPx() }
    val corner = with(localDensity) { Dimens.bubbleRadius.toPx() }
    val copyW = if (layout != null) layout.size.width + 2f * padH else EchoSpec.EMPTY_WIDTH_DP * localDensity.density
    val copyH = if (layout != null) layout.size.height + 2f * padV else EchoSpec.EMPTY_HEIGHT_DP * localDensity.density
    Canvas(Modifier.fillMaxSize()) {
        val millis = clock.longValue
        val px = density
        val fade = EchoSpec.fade(millis)
        if (fade <= 0f) return@Canvas
        for (i in 0 until field.count) {
            if (!field.started(i, millis)) continue
            val s = field.scale(i)
            val cx = field.x(i, millis, size.width, px)
            val cy = field.y(i, millis, size.height, copyH * s)
            translate(cx - copyW / 2f, cy - copyH / 2f) {
                scale(s, s, pivot = Offset(copyW / 2f, copyH / 2f)) {
                    rotate(field.tiltDegrees(i), pivot = Offset(copyW / 2f, copyH / 2f)) {
                        drawRoundRect(fill, size = Size(copyW, copyH), cornerRadius = CornerRadius(corner), alpha = fade)
                        if (layout != null) drawText(layout, ink, topLeft = Offset(padH, padV), alpha = fade)
                    }
                }
            }
        }
    }
}
