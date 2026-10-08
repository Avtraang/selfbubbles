package io.github.avtraang.selfbubbles

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.max
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme

// Colors live in ui/theme/Color.kt: MaterialTheme.colorScheme.* and MessagesTheme.colors.*.

/**
 * What an intent asked MainActivity to open: a notification tap's chat
 * (chat_guid / chat_name), a share id from ShareActivity, or nothing (the
 * launcher). [seq] is unique per intent delivery, so ChatVM.openFromLaunch acts
 * on each once however often App() recomposes (lock cycles, rotation).
 */
data class LaunchRequest(val seq: Long, val chatGuid: String?, val chatName: String?, val shareId: String?)

private val launchSeq = java.util.concurrent.atomic.AtomicLong()
private const val STATE_LAUNCH_SEQ = "launch_seq"

// A FragmentActivity (not ComponentActivity) because BiometricPrompt needs one for
// the app lock; it extends ComponentActivity, so setContent, enableEdgeToEdge,
// viewModel() and BackHandler are unchanged.
class MainActivity : FragmentActivity() {
    /**
     * The request the content follows: the creating intent, replaced by every
     * onNewIntent (singleTop: a notification tap or a share while the activity
     * is up). Compose state, so App() sees the new one without a recreate.
     */
    private var launch by mutableStateOf<LaunchRequest?>(null)

    private fun requestFrom(intent: Intent?, seq: Long) = LaunchRequest(
        seq = seq,
        chatGuid = intent?.getStringExtra("chat_guid"),
        chatName = intent?.getStringExtra("chat_name"),
        // Only an id ShareActivity could have made is passed on; the folder it names is ours.
        shareId = intent?.getStringExtra(EXTRA_SHARE_ID)?.takeIf { isValidShareId(it) },
    )

    // A notification tap (PushService: NEW_TASK | CLEAR_TOP) and a share
    // (ShareActivity: NEW_TASK | SINGLE_TOP | CLEAR_TOP) both land here now that
    // the activity is singleTop, and route exactly as onCreate's intent does.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = requestFrom(intent, launchSeq.incrementAndGet())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launch?.let { outState.putLong(STATE_LAUNCH_SEQ, it.seq) }
    }
    // Pushes for the chat on screen are suppressed only while the app is in front.
    override fun onResume() { super.onResume(); Notifs.foreground = true }
    override fun onPause() { Notifs.foreground = false; super.onPause() }

    // App lock (AppLock.kt). onStop stamps when the app really left the screen —
    // not for a rotation or a dark-mode flip, which recreate the activity without
    // the owner ever looking away. onStart (first launch included) decides whether
    // the time away, or a fresh process, means the lock screen. Monotonic clock:
    // unaffected by the user or the network changing the wall time.
    override fun onStart() {
        super.onStart()
        AppLock.refreshAvailability(this)
        AppLock.onStart(AppPrefs.lockEnabled, AppPrefs.lockGrace.millis, SystemClock.elapsedRealtime())
    }
    override fun onStop() {
        // AppLock also skips the stamp when another instance is already in front
        // (a notification tap recreates this activity; the new onStart runs first).
        AppLock.onStop(SystemClock.elapsedRealtime(), isChangingConfigurations)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        // The Voice Text launcher entry follows its feature switch (a no-op when already there).
        VoiceComponent.apply(this)
        // FLAG_SECURE must be set before the first frame to blank this window's
        // Recents card; Settings toggles it live on the same window afterwards.
        applySecureWindow(AppPrefs.secureWindow)
        // Contact photos come from the phone's address book (the Mac's has none).
        // Registered unconditionally — launchers must not be created conditionally.
        val contactsPerm = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) ContactPhotos.reload(this) }
        ContactPhotos.load(this)
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            contactsPerm.launch(android.Manifest.permission.READ_CONTACTS)
        }
        // Fixes invisible status bar icons: declares proper edge-to-edge with
        // auto contrast — dark icons over the light theme, light icons in dark
        // mode. Layout is unchanged (Android 15+ already enforces edge-to-edge
        // for targetSdk 35+; the Scaffolds handle the insets).
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(ImageDecoderDecoder.Factory()) }
                // Wikimedia (and other strict CDNs) 403 any client that doesn't
                // identify itself, which silently killed link-preview images.
                .okHttpClient {
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val req = chain.request()
                            val b = req.newBuilder().header(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
                            )
                            // Relay images (attachments, thumbnails, group icons)
                            // need auth; third-party CDNs must not see the token.
                            // The shared relayAuthInterceptor below adds the relay
                            // token and the Cloudflare Access pair for the exact
                            // relay origin only, and relayAuthStripInterceptor
                            // removes them on any redirect hop that leaves it.
                            val resp = chain.proceed(b.build())
                            // Attachment bytes never change for a guid, but the relay's
                            // plain /attachment/{guid} response carries no Cache-Control
                            // (only its HEIC branch does), so Coil trusts its disk copy
                            // for just (age since Last-Modified)/10 and then re-downloads
                            // the whole file. Photos hide this behind the memory cache;
                            // GIFs can't (only BitmapDrawables are memory-cached), so a
                            // fresh GIF re-fetched over the tunnel every time it scrolled
                            // back into view. Stamp the header the relay's HEIC branch
                            // already sends — but only on a response that is really the
                            // finished GIF: the relay serves any file that merely exists
                            // (no transfer_state check, no temp-then-move on this branch),
                            // Coil writes the body to disk before decoding, and max-age=1y
                            // would then replay a truncated download for a year with no
                            // self-heal. So: Content-Type must be image/gif (rules out the
                            // HEIC transcode-failure fallback, which serves undecodable
                            // image/heic bytes under the same ?f=jpg URL, and any other
                            // non-GIF 200), no redirect must have been followed, and the
                            // file's Last-Modified (Starlette sets it from mtime) must be
                            // at least 10 min old by the server's own clock, i.e. nothing
                            // has written to it recently. Anything younger keeps today's
                            // heuristic freshness and is re-validated on the next view.
                            // Nothing else is touched: /chat_icon and the rest can change
                            // under a stable URL, and photos sit behind the memory cache.
                            val lastMod = resp.headers.getDate("Last-Modified")?.time
                            val served = resp.headers.getDate("Date")?.time
                                ?: resp.receivedResponseAtMillis
                            if (resp.isSuccessful && resp.priorResponse == null &&
                                resp.header("Cache-Control") == null &&
                                isRelayPath(req.url, "/attachment/") &&
                                resp.header("Content-Type")?.startsWith("image/gif") == true &&
                                lastMod != null && served - lastMod >= 10 * 60 * 1000L
                            ) {
                                resp.newBuilder()
                                    .header("Cache-Control", "private, max-age=31536000, immutable")
                                    .build()
                            } else resp
                        }
                        .addInterceptor(relayAuthInterceptor)
                        .addNetworkInterceptor(relayAuthStripInterceptor)
                        .build()
                }
                .build()
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Push.registerWithRelay(this, lifecycleScope)
        // A recreated activity (rotation, dark-mode flip) keeps its request's seq, so
        // the VM, which already handled it, does not open that chat or share again.
        // Guarding the seq, not the ids: a later share arrives with a new id anyway,
        // and a second tap on the same chat's notification still routes. After
        // process death the counter restarted at zero while the saved seq did not:
        // launchSeqOnCreate raises the counter to it, so the next onNewIntent (a
        // notification tap, a share) still gets a seq above everything handled.
        val seq = launchSeqOnCreate(launchSeq, savedInstanceState?.getLong(STATE_LAUNCH_SEQ, 0L) ?: 0L)
        launch = requestFrom(intent, seq)
        setContent {
            MessagesTheme {
                // While locked only the lock screen exists; App() (and with it the
                // ChatVM and its data) first composes after the unlock, so a
                // notification tap's chat extras, or a share id, are consumed then:
                // App's LaunchedEffect(launch) runs on that first composition. The
                // gate re-composes App() after every later unlock too, so the VM
                // (which outlives those cycles) honours each request only once.
                AppLockGate(activity = this) { App(launch) }
            }
        }
    }
}

@Composable
fun App(launch: LaunchRequest? = null, vm: ChatVM = viewModel()) {
    LaunchedEffect(launch) { if (launch != null) vm.openFromLaunch(launch) }
    val current = vm.current
    // System back mirrors the top-bar arrow: Compose/Info -> conversation ->
    // thread list. Disabled at the root so back still exits from the list.
    BackHandler(enabled = current != null || vm.composing) { vm.back() }
    // Full-screen overlays own their own back gesture.
    BackHandler(enabled = vm.showFaceTime) { vm.closeFaceTime() }
    // Settings needs the FragmentActivity for the confirm-to-disable prompt (AppLock.prompt).
    val activity = LocalActivity.current as FragmentActivity
    // The open chat's saveable state (its half-typed draft, its scroll position) is
    // parked here while another branch of the `when` below takes its place (the
    // share picker, Settings, Info, compose, the map) and restored when the chat
    // comes back, as the lock gate does across a lock; without it a share arriving
    // from any other app over a chat wiped whatever was typed there. The parked
    // state is dropped once the chat is left (the list, or another chat), so a
    // draft lives exactly as long as it did before. The drop runs as a side effect,
    // after the leaving screen has saved itself into the holder.
    val convState = rememberSaveableStateHolder()
    var parkedGuid by remember { mutableStateOf<String?>(null) }
    val openGuid = current?.chat_guid
    if (openGuid != parkedGuid) {
        SideEffect {
            parkedGuid?.let { convState.removeState(it) }
            parkedGuid = openGuid
        }
    }
    // Files from the share sheet wait for Send here, over whichever screen is up (the chosen chat).
    vm.shareConfirm?.let { share ->
        ShareConfirmSheet(
            share = share,
            chatTitle = current?.title ?: "this chat",
            onSend = { vm.sendShare(activity) },
            onCancel = { vm.cancelShare() },
        )
    }
    when {
        // No relay yet (a stranger's first run; the owner's build is always configured):
        // the relay form, before any screen that would talk to the network.
        !vm.isConfigured -> RelaySetupScreen(onSaved = { vm.relayConfigChanged() })
        // A share waits for its chat before anything else; its own back cancels it.
        vm.shareResolving != null -> ShareWaitScreen(onCancel = { vm.cancelShare() })
        vm.pendingShare != null -> ChatPickerScreen(vm)
        vm.showFaceTime -> FaceTimeScreen(vm)
        vm.showMap -> MapScreen(onBack = { vm.closeMap() })
        vm.showSettings -> SettingsScreen(
            activity, onBack = { vm.closeSettings() },
            onShortcutsEnabled = { vm.republishShortcuts() },
            onRelayChanged = { vm.relayConfigChanged() },
        )
        vm.composing -> ComposeScreen(vm)
        current == null -> ThreadList(vm)
        vm.showInfo -> InfoScreen(vm, current)
        // Keyed on the thread so the composer's saveable draft (rememberTextFieldState
        // keys on the composite position alone) is restored only into the same
        // thread after process death, never into whichever thread opens first.
        else -> convState.SaveableStateProvider(current.chat_guid) { Conversation(vm, current) }
    }
}
