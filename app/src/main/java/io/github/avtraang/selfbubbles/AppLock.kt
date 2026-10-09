package io.github.avtraang.selfbubbles

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/*
 * App lock: fingerprint, face or the device PIN before the message history shows.
 *
 * Three layers, so the decisions can be unit-tested without Android:
 *  - pure helpers (shouldLock, allowedAuthenticators, lockAvailabilityFor,
 *    pushIsRedundant, LockGrace) — plain Kotlin;
 *  - LockState / AppLock, the lock state machine (plain Kotlin, testable with
 *    fresh instances) and its process-wide instance the activity drives from
 *    onStart/onStop;
 *  - AppLockGate, the Compose gate that shows only a lock screen while locked.
 *
 * Only MainActivity is gated. VoiceActivity ("Voice Text", the hands-free entry)
 * and the notification shade's direct reply are not; see the notes there and in
 * Settings.
 */

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/** How long the app may sit in the background before it locks again. */
enum class LockGrace(val millis: Long, val label: String, val prefValue: String) {
    IMMEDIATELY(0L, "Immediately", "immediately"),
    ONE_MINUTE(60_000L, "1 minute", "1m"),
    FIVE_MINUTES(5 * 60_000L, "5 minutes", "5m"),
    THIRTY_MINUTES(30 * 60_000L, "30 minutes", "30m");

    companion object {
        val DEFAULT = ONE_MINUTE

        /** The stored value back to an option; anything unknown (or null) is the default. */
        fun fromPref(value: String?): LockGrace = entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

/**
 * Should the app be locked right now?
 *
 * - lock disabled -> never;
 * - never unlocked in this process (fresh start) -> yes;
 * - never backgrounded since the last unlock -> no (a configuration change lands here);
 * - otherwise locked once the time away reaches the grace, or when the clock ran
 *   backwards (a negative gap is not evidence of a short absence).
 */
fun shouldLock(
    enabled: Boolean,
    now: Long,
    lastBackground: Long?,
    graceMillis: Long,
    everUnlocked: Boolean,
): Boolean {
    if (!enabled) return false
    if (!everUnlocked) return true
    if (lastBackground == null) return false
    val elapsed = now - lastBackground
    if (elapsed < 0) return true
    return elapsed >= graceMillis
}

/**
 * Authenticators the prompt may accept. The device PIN/pattern/password is always
 * allowed, so the system offers it as the fallback itself (no custom negative
 * button). Before API 30 the library forbids STRONG together with DEVICE_CREDENTIAL,
 * so those versions accept WEAK biometrics.
 */
fun allowedAuthenticators(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    } else {
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }

/** Whether the device can authenticate at all, with the reason when it cannot (shown in Settings). */
sealed class LockAvailability {
    object Available : LockAvailability()
    data class Unavailable(val reason: String) : LockAvailability()
}

/**
 * Maps a BiometricManager.canAuthenticate() result; pure so the mapping is testable.
 *
 * Fail closed: only the three answers that mean "nothing on this phone can ever
 * authenticate" (nothing enrolled, no hardware, unsupported) make the lock
 * unavailable, which is what lets the content show without it. Everything else,
 * including the transient HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED and any code
 * this library version does not know, keeps the lock in force: the prompt itself
 * reports the problem on the lock screen, and the owner still has the PIN.
 */
fun lockAvailabilityFor(canAuthenticate: Int): LockAvailability = when (canAuthenticate) {
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
        LockAvailability.Unavailable("No screen lock, fingerprint or face is set up on this phone. Set one in the phone's Settings to use the app lock.")
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
        LockAvailability.Unavailable("This phone has no screen lock or biometric hardware the app can use.")
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
        LockAvailability.Unavailable("This phone does not support the authentication the app lock needs.")
    else -> LockAvailability.Available
}

/**
 * Whether a push for [chatGuid] is redundant because that chat is open on screen.
 * Only when the app is in front AND not gated by the lock: while the lock screen
 * is up the ChatVM behind it still names its last chat as open, but nothing of it
 * shows, so the message must still reach the shade. And only while the live
 * connection is up ([socketLive], RelaySocket.kt): it is the socket's frame that
 * draws the message in the open chat, and with no socket nothing would.
 */
fun pushIsRedundant(foreground: Boolean, gated: Boolean, openChat: String?, chatGuid: String, socketLive: Boolean): Boolean =
    foreground && !gated && openChat == chatGuid && socketLive

// ---------------------------------------------------------------------------
// Persisted preferences
// ---------------------------------------------------------------------------

/**
 * The app's few settings, in SharedPreferences "app_prefs". Exposed as Compose
 * state so the gate and the Settings screen follow changes at once. Call [init]
 * once per process before reading (MainActivity.onCreate does).
 */
object AppPrefs {
    private const val FILE = "app_prefs"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_LOCK_GRACE = "lock_grace"
    private const val KEY_SECURE_WINDOW = "secure_window"
    private const val KEY_SHORTCUTS = "conversation_shortcuts"
    private const val KEY_SHORTCUT_MAP = "shortcut_map"
    private const val KEY_SHORTCUT_COMPONENT = "shortcut_component"
    private const val KEY_BLUEBUBBLES_RELAY = "bluebubbles_relay"

    private var prefs: SharedPreferences? = null

    var lockEnabled by mutableStateOf(false)
        private set
    var lockGrace by mutableStateOf(LockGrace.DEFAULT)
        private set
    var secureWindow by mutableStateOf(false)
        private set
    /** Conversation shortcuts (ConversationShortcuts.kt): recent chats in the share sheet and the launcher. */
    var shortcutsEnabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        if (!p.contains(KEY_LOCK_ENABLED)) {
            // First run: on when the phone can authenticate, so a fresh install is
            // protected without a visit to Settings. Stored, so a later change in the
            // phone's lock setup does not silently flip it.
            val canAuth = BiometricManager.from(context).canAuthenticate(allowedAuthenticators(Build.VERSION.SDK_INT))
            p.edit().putBoolean(KEY_LOCK_ENABLED, lockAvailabilityFor(canAuth) is LockAvailability.Available).apply()
        }
        lockEnabled = p.getBoolean(KEY_LOCK_ENABLED, true)
        lockGrace = LockGrace.fromPref(p.getString(KEY_LOCK_GRACE, null))
        secureWindow = p.getBoolean(KEY_SECURE_WINDOW, false)
        if (!p.contains(KEY_SHORTCUTS)) {
            // First time only: shortcuts put chat names and photos on the launcher and
            // in the share sheet, outside the app and its lock. An owner who already
            // hides the app in Recents and blocks screenshots has said they do not
            // want that, so the default follows that switch; later flips of either
            // switch are the owner's own and are left alone.
            p.edit().putBoolean(KEY_SHORTCUTS, !secureWindow).apply()
        }
        shortcutsEnabled = p.getBoolean(KEY_SHORTCUTS, true)
    }

    fun updateShortcutsEnabled(on: Boolean) {
        shortcutsEnabled = on
        prefs?.edit()?.putBoolean(KEY_SHORTCUTS, on)?.apply()
    }

    /** Shortcut id -> chat guid for every shortcut published, so an id from an intent resolves without scanning. */
    fun shortcutMap(): Map<String, String> = shortcutMapDecode(prefs?.getStringSet(KEY_SHORTCUT_MAP, null))

    fun shortcutGuid(id: String?): String? = if (isValidShortcutId(id)) shortcutMap()[id!!] else null

    fun replaceShortcutMap(map: Map<String, String>) {
        prefs?.edit()?.putStringSet(KEY_SHORTCUT_MAP, shortcutMapEncode(map))?.apply()
    }

    fun putShortcutMapping(id: String, guid: String) {
        replaceShortcutMap(shortcutMap() + (id to guid))
    }

    /**
     * The class name of the activity the conversation shortcuts were last published
     * for; null when they never were, or were by a build from before this key. A
     * shortcut's intent names that class, so one published under another name
     * (the source package was renamed) opens nothing until it is rebuilt.
     */
    fun shortcutComponent(): String? = prefs?.getString(KEY_SHORTCUT_COMPONENT, null)

    fun putShortcutComponent(className: String) {
        prefs?.edit()?.putString(KEY_SHORTCUT_COMPONENT, className)?.apply()
    }

    /**
     * The mark ([relayMark]: a digest, not the address) of the relay a send was
     * last seen to go through BlueBubbles on; null when none was. It lets the
     * "BlueBubbles down" notice (ChatVM.noteSendPath) survive a restart of the app.
     */
    fun blueBubblesRelay(): String? = prefs?.getString(KEY_BLUEBUBBLES_RELAY, null)

    fun putBlueBubblesRelay(mark: String) {
        prefs?.edit()?.putString(KEY_BLUEBUBBLES_RELAY, mark)?.apply()
    }

    fun updateLockEnabled(on: Boolean) {
        lockEnabled = on
        prefs?.edit()?.putBoolean(KEY_LOCK_ENABLED, on)?.apply()
    }

    fun updateLockGrace(grace: LockGrace) {
        lockGrace = grace
        prefs?.edit()?.putString(KEY_LOCK_GRACE, grace.prefValue)?.apply()
    }

    fun updateSecureWindow(on: Boolean) {
        secureWindow = on
        prefs?.edit()?.putBoolean(KEY_SECURE_WINDOW, on)?.apply()
    }
}

// ---------------------------------------------------------------------------
// Process-wide lock state
// ---------------------------------------------------------------------------

/**
 * The lock's state machine. Plain Kotlin (Compose state only, nothing from
 * Android), so JVM tests drive fresh instances; [AppLock] is the one the app uses.
 *
 * A fresh instance starts LOCKED; the activity's onStart decides (through
 * [shouldLock]) whether that stands, and onStop records when the app went to the
 * background. Once locked, only [unlock] opens.
 */
open class LockState {
    var locked by mutableStateOf(true)
        private set
    var everUnlocked = false
        private set
    var lastBackgroundedAt: Long? = null
        private set

    /**
     * MainActivity instances between onStart and onStop. Normally 0 or 1; a
     * notification tap recreates the activity (standard launch mode, CLEAR_TOP),
     * and Android runs the new instance's onStart BEFORE the old one's onStop, so
     * for that moment it is 2. [onStop] uses it to tell that hand-over from a real
     * trip to the background.
     */
    var startedInstances = 0
        private set

    /** True from authenticate() until the library's callback; stops a resume from stacking a second prompt. */
    var promptInFlight = false
        protected set

    /** Lock-screen status line; the system's own message after a lockout. */
    var status by mutableStateOf<String?>(null)
        protected set

    /**
     * Whether the lock screen's next appearance (or resume) may prompt on its own.
     * Set by a fresh process and by every real trip to the background; consumed by
     * the one automatic prompt. A configuration change sets nothing, so a rotation
     * after a cancelled prompt does not bring the prompt back; the button does.
     */
    var autoPromptPending = true
        private set

    /** The lock screen asks before its automatic prompt; true once per pending resume. */
    fun consumeAutoPrompt(): Boolean {
        val pending = autoPromptPending
        autoPromptPending = false
        return pending
    }

    /**
     * The activity is coming back (or starting): decide, then consume the background
     * mark. Never unlocks on its own: a locked state stays locked until [unlock], so
     * an activity recreated over the lock screen (notification tap) cannot find
     * "unlocked once, never backgrounded since" and skip it. With the lock disabled
     * the state is simply open.
     */
    fun onStart(enabled: Boolean, graceMillis: Long, now: Long) {
        startedInstances += 1
        locked = enabled && (locked || shouldLock(enabled, now, lastBackgroundedAt, graceMillis, everUnlocked))
        lastBackgroundedAt = null
        if (!locked) status = null
    }

    /**
     * The activity's onStop. The time away is stamped only when this was the last
     * started instance and it is not a configuration change: during a
     * notification-tap hand-over the old instance stops while the new one is
     * already in front, and a stamp then would make the next rotation re-lock
     * (the owner never looked away). A back-press exit still stamps: the count
     * reaches zero. Any prompt belonged to the stopping activity's fragment, and the
     * library need not deliver a terminal callback when that activity is destroyed,
     * so a real stop also ends the in-flight mark; a configuration change carries
     * the prompt over and keeps it.
     */
    fun onStop(now: Long, changingConfigurations: Boolean) {
        startedInstances = (startedInstances - 1).coerceAtLeast(0)
        if (changingConfigurations) return
        promptInFlight = false
        if (startedInstances > 0) return
        lastBackgroundedAt = now
        autoPromptPending = true
    }

    fun unlock() {
        locked = false
        everUnlocked = true
        lastBackgroundedAt = null
        status = null
    }
}

/** One per process: the [LockState] MainActivity drives, plus the Android pieces. */
object AppLock : LockState() {
    var availability: LockAvailability by mutableStateOf(LockAvailability.Available)
        private set

    /** The lock screen is (or would be) in front: enabled, possible on this phone, and locked. */
    val gated: Boolean
        get() = AppPrefs.lockEnabled && availability is LockAvailability.Available && locked

    fun refreshAvailability(context: Context) {
        val result = BiometricManager.from(context).canAuthenticate(allowedAuthenticators(Build.VERSION.SDK_INT))
        availability = lockAvailabilityFor(result)
    }

    /** Title of every prompt this app shows: "Unlock <app label>" (R.string.app_name, set at build time). */
    fun promptTitle(context: Context): String = "Unlock ${context.getString(R.string.app_name)}"

    /** Outcome of one prompt, reduced to what the callers act on. */
    enum class AuthOutcome { SUCCESS, CANCELLED, ERROR }

    /**
     * Shows the system prompt. The device credential is always allowed, so the
     * system offers "Use PIN" itself. [onResult] runs on the main thread; the
     * error message (when any) is the system's own text, never anything personal.
     */
    fun prompt(
        activity: FragmentActivity,
        title: String = promptTitle(activity),
        onResult: (AuthOutcome, CharSequence?) -> Unit,
    ) {
        promptInFlight = true
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptInFlight = false
                onResult(AuthOutcome.SUCCESS, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptInFlight = false
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onResult(if (cancelled) AuthOutcome.CANCELLED else AuthOutcome.ERROR, errString)
            }

            // A single rejected attempt: the system UI already says so; the prompt stays up.
            override fun onAuthenticationFailed() {}
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(allowedAuthenticators(Build.VERSION.SDK_INT))
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    /**
     * The lock screen's own prompt: success unlocks, a cancel keeps the screen, errors
     * show their text. The automatic (per-resume) call skips when a prompt is already
     * up; the Unlock button passes [force] so a tap always gets a prompt.
     */
    fun promptToUnlock(activity: FragmentActivity, force: Boolean = false) {
        if (promptInFlight && !force) return
        prompt(activity) { outcome, message ->
            when (outcome) {
                AuthOutcome.SUCCESS -> unlock()
                AuthOutcome.CANCELLED -> status = null
                AuthOutcome.ERROR -> status = message?.toString()
            }
        }
    }
}

/** FLAG_SECURE: blank preview in Recents, no screenshots or screen recording of the app. */
fun FragmentActivity.applySecureWindow(on: Boolean) {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

// ---------------------------------------------------------------------------
// Compose gate
// ---------------------------------------------------------------------------

/**
 * Composes [content] only when the lock is off, unavailable on this phone, or
 * unlocked. While locked it composes the lock screen alone, so nothing of the
 * real UI (and none of its data loading) exists until the owner authenticates.
 *
 * The content's rememberSaveable state (a half-typed draft, the list scroll
 * positions) is parked in a SaveableStateHolder while the lock screen is up and
 * restored into the same composition slots after the unlock, exactly as across a
 * rotation; without it every re-lock would wipe that state when the subtree left
 * composition.
 */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    val saveable = rememberSaveableStateHolder()
    if (AppLock.gated) LockScreen(activity) else saveable.SaveableStateProvider(CONTENT_STATE_KEY) { content() }
}

private const val CONTENT_STATE_KEY = "app_lock_content"

@Composable
private fun LockScreen(activity: FragmentActivity) {
    // One automatic prompt per resume while locked: the first appearance counts
    // (the registry replays ON_RESUME to a new observer), later resumes add one.
    // Only a fresh process or a real trip to the background arms it
    // (AppLock.autoPromptPending), so a rotation or a dark-mode flip, which
    // recreates this screen, does not re-prompt. A prompt already in flight
    // (the library carries it across the rotation itself) is never doubled;
    // the button re-shows it after a cancel.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumes += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumes) {
        if (resumes > 0 && AppLock.consumeAutoPrompt()) AppLock.promptToUnlock(activity)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,   // the title below carries the meaning
                modifier = Modifier.size(Dimens.lockGlyph),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                AppLock.status ?: "Use your fingerprint or PIN",
                style = MaterialTheme.typography.bodyMedium,
                color = if (AppLock.status != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            Button(onClick = { AppLock.promptToUnlock(activity, force = true) }) { Text("Unlock") }
        }
    }
}
