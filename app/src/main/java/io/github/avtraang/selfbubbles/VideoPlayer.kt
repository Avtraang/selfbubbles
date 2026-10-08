package io.github.avtraang.selfbubbles

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

private val VideoTargetSaver: Saver<VideoTarget?, Any> = Saver(
    save = { saveVideoTarget(it) },
    restore = { saved -> restoreVideoTarget(saved) { isRelayUrl(it) } },
)

private val VideoPlaybackSaver: Saver<VideoPlayback, Any> = Saver(
    save = { saveVideoPlayback(it) },
    restore = { restoreVideoPlayback(it) },
)

/**
 * Which video the player is showing, if any. Saved with the screen's state, so
 * rotating the phone (or any other configuration change) keeps it open.
 */
@Composable
fun rememberVideoTarget(): MutableState<VideoTarget?> =
    rememberSaveable(stateSaver = VideoTargetSaver) { mutableStateOf(null) }

/** Fades [content] in and out with the player's controls. Receiver-free, so it resolves the same inside any layout. */
@Composable
private fun WithControls(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// A save or a hand-off takes as long as the video takes to download (tens of
// seconds for an iPhone clip), and pressing back meanwhile must not throw it
// away. So the work runs in a scope that outlives the dialog, and which URLs are
// busy is kept here too, so a player that comes back after a rotation still
// shows its buttons disabled and cannot start the same download twice.
private val videoWork = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val videoBusyUrls = mutableStateMapOf<String, Unit>()

/** Runs [block] for [url] unless one is already running for it. [block] gets the application context. */
private fun startVideoWork(ctx: Context, url: String, block: suspend (Context) -> Unit) {
    if (videoBusyUrls.containsKey(url)) return
    videoBusyUrls[url] = Unit
    val app = ctx.applicationContext
    videoWork.launch {
        try {
            block(app)
        } finally {
            videoBusyUrls.remove(url)
        }
    }
}

private suspend fun saveVideo(ctx: Context, target: VideoTarget) {
    val ok = Downloads.save(ctx, target.url, target.name, target.mime)
    Downloads.toast(ctx, ok)
}

/**
 * Downloads the video into its own folder under cacheDir/shared, under a
 * sanitised name and the hand-off cap, then offers it to other apps through the
 * system chooser. A complete copy from an earlier hand-off is reused.
 */
private suspend fun handOffVideo(ctx: Context, target: VideoTarget) {
    val got = Downloads.fetchToCache(
        ctx, target.url, sanitizeVideoFileName(target.name, target.mime), VIDEO_HANDOFF_MAX_BYTES,
        subDir = videoCacheDirName(target.url), reuse = true,
    )
    val message = when (got) {
        is Downloads.CacheFetch.Ok ->
            if (Downloads.openInAnotherApp(ctx, got.file, target.mime)) null else VIDEO_OPEN_FAILED_MESSAGE
        Downloads.CacheFetch.TooLarge -> VIDEO_TOO_LARGE_MESSAGE
        Downloads.CacheFetch.Missing -> ATTACHMENT_MISSING_MESSAGE
        Downloads.CacheFetch.Failed -> VIDEO_OPEN_FAILED_MESSAGE
    }
    if (message != null) Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
}

/** The status of the HTTP error in [error]'s cause chain, if the relay answered one. */
private fun httpStatusOf(error: PlaybackException): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

/**
 * Built-in video player for a relay attachment. Streams through the shared
 * `http` client (range requests included), so the relay credentials are added
 * and stripped per hop by the same interceptors as every other request; nothing
 * is set on the data source itself.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(target: VideoTarget, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The dialog window owns back while it is up, so this runs before any handler of the screen beneath.
        BackHandler(onBack = onClose)

        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val displayName = remember(target) { videoDisplayName(target.name) }
        // Where playback is. Written when the activity pauses, which is before it
        // saves its state, and read once when the player is built, so a player
        // that comes back after a rotation carries on from the same spot.
        var playback by rememberSaveable(target, stateSaver = VideoPlaybackSaver) {
            mutableStateOf(VIDEO_PLAYBACK_START)
        }
        var error by remember { mutableStateOf<VideoError?>(null) }
        var playing by remember { mutableStateOf(false) }   // the screen stays on only while this is true
        var controlsVisible by remember { mutableStateOf(true) }
        val busy = videoBusyUrls.containsKey(target.url)   // a save or a hand-off download is running
        // A file the relay no longer has cannot be saved or handed off either.
        val missing = error == VideoError.MISSING

        val player = remember(target) {
            val audio = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
                .setAudioAttributes(audio, true)   // takes audio focus: other audio pauses
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(VIDEO_SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(VIDEO_SEEK_INCREMENT_MS)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(target.url))
                    val start = playback
                    if (start.positionMs > 0L) seekTo(start.positionMs)
                    playWhenReady = start.playWhenReady
                    prepare()
                }
        }

        fun snapshot() = VideoPlayback(
            clampVideoPosition(player.currentPosition, player.duration),
            player.playWhenReady,
        )

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = videoError(httpStatusOf(e))
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        DisposableEffect(player, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> playback = snapshot()
                    Lifecycle.Event.ON_STOP -> {
                        // Gone to the background: stop and stay stopped. A rotation
                        // also passes through here, but then the player is about to
                        // be rebuilt from the snapshot, so it keeps playing.
                        if (ctx.findActivity()?.isChangingConfigurations != true) {
                            player.pause()
                            playback = snapshot()
                        }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val scrim = MaterialTheme.colorScheme.scrim      // black in both themes, like the image viewer
        val onScrim = MessagesTheme.colors.onScrim
        val buttonColors = ButtonDefaults.textButtonColors(contentColor = onScrim)

        Column(Modifier.fillMaxSize().background(scrim)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            contentDescription = "Video"
                            // Our own bars follow the controller: a tap on the video toggles all of them.
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                },
                            )
                        }
                    },
                    update = { view ->
                        view.player = player
                        // Paused, ended or failed, the display may dim as usual.
                        view.keepScreenOn = playing
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize(),
                )

                val shownError = error
                if (shownError != null) {
                    Column(
                        Modifier.align(Alignment.Center)
                            .background(scrim.copy(alpha = 0.6f))
                            .padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            shownError.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onScrim,
                            textAlign = TextAlign.Center,
                        )
                        if (shownError != VideoError.MISSING) {   // a missing file would only 404 again
                            TextButton(
                                colors = buttonColors,
                                onClick = {
                                    error = null
                                    player.prepare()   // after an error this retries from where it was
                                    player.play()
                                },
                            ) { Text("Retry") }
                        }
                    }
                }

                WithControls(visible = controlsVisible, modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(
                        Modifier.fillMaxWidth().background(scrim.copy(alpha = 0.6f)).statusBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onScrim)
                        }
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = onScrim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = Spacing.lg),
                        )
                    }
                }
            }

            // The row keeps its height while hidden, so the video never jumps when the controls come and go.
            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(Dimens.touchTarget)) {
                WithControls(visible = controlsVisible) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !busy && !missing,
                            colors = buttonColors,
                            onClick = { startVideoWork(ctx, target.url) { app -> saveVideo(app, target) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save to Photos", textAlign = TextAlign.Center) }
                        TextButton(
                            enabled = !busy && !missing,
                            colors = buttonColors,
                            onClick = { startVideoWork(ctx, target.url) { app -> handOffVideo(app, target) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open in another app", textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
}
