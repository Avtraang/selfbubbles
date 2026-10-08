package io.github.avtraang.selfbubbles

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.avtraang.selfbubbles.ui.theme.BubbleShape
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Pause
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/** Why a voice message could not be played; kept per message so an expired one stays marked. */
enum class AudioError { Failed, Expired }

/**
 * One player for the whole chat screen. It owns a single ExoPlayer, built like
 * the video player's (streams through the shared `http` client, so the relay
 * credentials are added by the same interceptors), and remembers which message
 * is in it by attachment guid, so a bubble that scrolls out and back in, or is
 * recomposed, shows the same play state. Nothing is fetched until the first tap
 * on a play button, and only one message plays at a time.
 */
@OptIn(UnstableApi::class)
class AudioController(private val ctx: Context) {
    /** The attachment in the player, if any. */
    var currentGuid by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Preparing or stalled: the bubble shows a spinner instead of the glyph. */
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    /** Length of the current attachment; negative until the stream has been read. */
    var durationMs by mutableStateOf(-1L)
        private set
    /** Messages that failed, by guid, until the next tap on them. */
    val errors = mutableStateMapOf<String, AudioError>()

    // Lengths seen so far, so a message still shows "0:00 / 0:42" after another one was played.
    private val durations = mutableStateMapOf<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poll: Job? = null
    private var released = false
    private var built: ExoPlayer? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> sync()
                Player.STATE_ENDED -> {
                    sync()
                    if (durationMs > 0L) positionMs = durationMs
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) startPolling() else {
                stopPolling()
                sync()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            buffering = false
            currentGuid?.let { errors[it] = classify(error) }
        }
    }

    /** The player, built on first use: nothing is created for a chat that never plays a voice message. */
    private val player: ExoPlayer
        get() = built ?: build().also { built = it }

    private fun build(): ExoPlayer {
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
            .setAudioAttributes(audio, true)   // takes audio focus: other audio pauses
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { addListener(listener) }
    }

    /** The length of [guid] if the player has read it, else -1. */
    fun knownDuration(guid: String): Long = durations[guid] ?: -1L

    /**
     * The play button: starts [guid] (stopping whatever else was playing),
     * pauses it when it is playing, resumes it when it is paused, restarts it
     * from the beginning when it has ended, and tries again after an error.
     */
    fun toggle(guid: String, url: String) {
        if (released) return
        val p = player
        if (currentGuid != guid) {
            errors.remove(guid)
            stopPolling()
            // Stop and empty the player BEFORE the clock points at the new message: stop()
            // delivers onIsPlayingChanged(false) synchronously while the old item (and its
            // position and length) is still in the player, and sync() must file that
            // reading under the old guid, not the new one.
            p.stop()
            p.clearMediaItems()
            currentGuid = guid
            positionMs = 0L
            durationMs = knownDuration(guid)
            p.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(guid).build())
            p.prepare()
            p.play()
            return
        }
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_IDLE -> {   // after an error: prepare again from where it was
                errors.remove(guid)
                p.prepare()
                p.play()
            }
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0L)
                p.play()
            }
            else -> p.play()
        }
    }

    /** Moves the current message to [fraction] of its length; ignored for any other message or before the length is known. */
    fun seek(guid: String, fraction: Float) {
        if (released || currentGuid != guid || durationMs <= 0L) return
        val target = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
        player.seekTo(target)
        positionMs = target
    }

    /** Pauses whatever is playing (the app went to the background). */
    fun pause() {
        built?.pause()
    }

    /** The chat screen is gone: stop the clock and free the player. */
    fun release() {
        released = true
        stopPolling()
        scope.cancel()
        built?.let {
            it.removeListener(listener)
            it.release()
        }
        built = null
        isPlaying = false
        buffering = false
    }

    private fun sync() {
        val p = built ?: return
        // Only a reading of the message the bubble shows: during a switch, after stop()
        // or after an error the player still answers for the previous item (or nothing).
        if (!shouldSyncClock(currentGuid, p.currentMediaItem?.mediaId, p.playbackState == Player.STATE_IDLE)) return
        positionMs = p.currentPosition.coerceAtLeast(0L)
        val d = p.duration
        if (d > 0L) {
            durationMs = d
            currentGuid?.let { durations[it] = d }
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                sync()
                delay(AUDIO_POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        poll?.cancel()
        poll = null
    }

    private fun classify(error: PlaybackException): AudioError {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && isExpiredStatus(cause.responseCode)) {
                return AudioError.Expired
            }
            cause = cause.cause
        }
        return AudioError.Failed
    }
}

/**
 * The chat screen's shared voice-message player. Released when the screen
 * leaves composition; paused when the app goes to the background.
 */
@Composable
fun rememberAudioController(): AudioController {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { AudioController(ctx.applicationContext) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return controller
}

/**
 * A voice message inside its bubble: play/pause, a slider, the clock and the
 * label, in the bubble's own colours ([bg] and [fg] are the text bubble's).
 * A long press anywhere on it opens the message actions like any other bubble.
 */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioBubble(
    guid: String,
    url: String,
    name: String?,
    controller: AudioController,
    bg: Color,
    fg: Color,
    onLongPress: () -> Unit,
) {
    val current = controller.currentGuid == guid
    val playing = current && controller.isPlaying
    val buffering = current && controller.buffering
    val error = controller.errors[guid]
    val duration = if (current) controller.durationMs else controller.knownDuration(guid)
    val position = if (current) controller.positionMs else 0L
    val label = remember(name) { audioLabel(name) }
    // Where the thumb is while a finger holds it; the player is only told on release.
    var dragging by remember(guid) { mutableStateOf<Float?>(null) }
    // A long press on the live slider opened the message actions: the release that
    // follows must not count as a tap on the track (no jump, no seek).
    var heldForActions by remember(guid) { mutableStateOf(false) }
    val fraction = dragging ?: progressFraction(position, duration)
    val shownPosition = dragging?.takeIf { duration > 0L }?.let { (it * duration).toLong() } ?: position
    val dim = fg.copy(alpha = 0.85f)   // the idle slider's thumb and track, as the link card dims its summary
    val track = fg.copy(alpha = 0.3f)
    val sliderEnabled = current && duration > 0L && error == null
    val latestLongPress by rememberUpdatedState(onLongPress)

    Surface(
        color = bg,
        shape = BubbleShape,
        modifier = Modifier.widthIn(max = Dimens.bubbleMaxWidth)
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Dimens.bubblePadH, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val description = if (playing) "Pause" else "Play voice message"
            Box(
                Modifier.size(Dimens.touchTarget)
                    .clip(CircleShape)
                    // Same node for both, like every other bubble element with its own tap:
                    // a long press on the glyph opens the actions and does not toggle playback.
                    .combinedClickable(onClick = { controller.toggle(guid, url) }, onLongClick = onLongPress)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) {
                    CircularProgressIndicator(Modifier.size(Dimens.iconSmall), color = fg, strokeWidth = Dimens.progressStroke)
                } else {
                    Icon(if (playing) Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = fg)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Slider(
                    value = fraction,
                    onValueChange = { if (!heldForActions) dragging = it },
                    onValueChangeFinished = {
                        if (!heldForActions) dragging?.let { controller.seek(guid, it) }
                        heldForActions = false
                        dragging = null
                    },
                    enabled = sliderEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = fg,
                        activeTrackColor = fg,
                        inactiveTrackColor = track,
                        activeTickColor = fg,
                        inactiveTickColor = track,
                        disabledThumbColor = dim,
                        disabledActiveTrackColor = dim,
                        disabledInactiveTrackColor = track,
                        disabledActiveTickColor = dim,
                        disabledInactiveTickColor = track,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Position" }
                        // A disabled slider lets touches through to the bubble's own long press.
                        // A live one takes every down for its tap and drag, so a still press is
                        // watched here in the Initial pass (nothing consumed) and opens the
                        // actions the same way; the release is then ignored by the slider.
                        .pointerInput(sliderEnabled) {
                            if (!sliderEnabled) return@pointerInput
                            val timeout = viewConfiguration.longPressTimeoutMillis
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                heldForActions = false
                                val held = withTimeoutOrNull(timeout) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val moved = change.position - down.position
                                        if (pastTouchSlop(moved.x, moved.y, slop)) break
                                    }
                                } == null
                                if (held) {
                                    heldForActions = true
                                    dragging = null
                                    latestLongPress()
                                }
                            }
                        },
                )
                Text(
                    "${formatClock(shownPosition)} / ${formatClock(duration)}",
                    style = MaterialTheme.typography.labelMedium,   // the timestamp size, in the bubble's full text colour
                    color = fg,
                )
                if (error != null) {
                    Text(
                        if (error == AudioError.Expired) AUDIO_EXPIRED_MESSAGE else AUDIO_ERROR_MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg,
                    )
                }
            }
        }
    }
}
