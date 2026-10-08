package io.github.avtraang.selfbubbles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.fragment.app.FragmentActivity
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/**
 * The app's settings: the app lock (AppLock.kt), the secure-window flag, the
 * feature switches (Features.kt), the relay (RelaySetupScreen.kt) and, as the
 * last line, which build this is (BuildStamp.kt). Reached from the gear in the
 * thread list's top bar; the back arrow and the system back both return to the
 * list (ChatVM.closeSettings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    onBack: () -> Unit,
    /** The conversation-shortcuts switch was turned on: the caller publishes from the list it holds. */
    onShortcutsEnabled: () -> Unit = {},
    /** The relay form saved or reset the store: the caller reconnects (ChatVM.relayConfigChanged). */
    onRelayChanged: () -> Unit = {},
) {
    BackHandler { onBack() }
    val available = AppLock.availability
    var confirmError by remember { mutableStateOf<String?>(null) }
    // Bumped by a relay Save or Reset, so the Features rows re-read the map URL.
    var relayGeneration by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader("App lock")
            SwitchRow(
                title = "Require unlock",
                subtitle = when (available) {
                    is LockAvailability.Available ->
                        "Fingerprint, face or your PIN before messages show."
                    is LockAvailability.Unavailable -> available.reason
                },
                checked = AppPrefs.lockEnabled && available is LockAvailability.Available,
                enabled = available is LockAvailability.Available,
                onCheckedChange = { on ->
                    if (on) {
                        // Turning it on needs no proof: the next background is what locks.
                        confirmError = null
                        AppPrefs.updateLockEnabled(true)
                    } else {
                        // Turning it off is the sensitive direction: prove it is the owner first.
                        AppLock.prompt(activity, title = "Turn off Require unlock") { outcome, message ->
                            when (outcome) {
                                AppLock.AuthOutcome.SUCCESS -> { confirmError = null; AppPrefs.updateLockEnabled(false) }
                                AppLock.AuthOutcome.CANCELLED -> confirmError = null
                                AppLock.AuthOutcome.ERROR -> confirmError = message?.toString()
                            }
                        }
                    }
                },
            )
            confirmError?.let { Note(it, error = true) }
            Note("Notifications and replies from the shade are not locked: a notification still shows that conversation's recent messages. Hide them with the phone's lock-screen notification setting.")

            if (available is LockAvailability.Available) {
                SettingsHeader("Re-lock after")
                Column(Modifier.selectableGroup()) {
                    LockGrace.entries.forEach { option ->
                        RadioRow(
                            label = option.label,
                            selected = AppPrefs.lockGrace == option,
                            enabled = AppPrefs.lockEnabled,
                            onClick = { AppPrefs.updateLockGrace(option) },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SettingsHeader("Privacy")
            SwitchRow(
                title = "Hide in Recents and block screenshots",
                subtitle = "Shows a blank card in the Recents screen. Also blocks screenshots and screen recording of this app.",
                checked = AppPrefs.secureWindow,
                enabled = true,
                onCheckedChange = { on ->
                    AppPrefs.updateSecureWindow(on)
                    activity.applySecureWindow(on)   // takes effect on this window right away
                },
            )
            SwitchRow(
                title = "Show recent chats in the share sheet and launcher",
                subtitle = "Names and photos of your pinned and recent chats appear in the share sheet and on the app icon's long-press menu. A chat you dragged to the home screen stays there until you remove it yourself.",
                checked = AppPrefs.shortcutsEnabled,
                enabled = true,
                onCheckedChange = { on ->
                    AppPrefs.updateShortcutsEnabled(on)
                    // Off removes every shortcut at once (ConversationShortcuts.kt; a
                    // home-screen pin is the launcher's, so it is only disabled) and
                    // nothing is published again until it is on; on publishes right away.
                    if (on) onShortcutsEnabled() else ConversationShortcuts.removeAll(activity)
                },
            )

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SettingsHeader("Push notifications")
            // Both fixed for the life of the process (Push, PushService.kt), so read once.
            Note(Push.settingsNote(builtIn = Push.builtIn, initialised = remember { Push.available(activity) }))

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SettingsHeader("Features")
            Note("What the app shows beyond messages. A Test connection or Save asks the relay what it offers; that only annotates these rows, the switches are yours.")
            val mapUrlBlank = remember(relayGeneration) { RelayConfigStore.current.mapUrl.isBlank() }
            FeatureRow(
                feature = Feature.FAMILY_MAP,
                title = "Family map",
                description = "A page of your family's locations, opened from the thread list.",
                extra = if (mapUrlBlank) "Needs a family map URL (Relay section)." else null,
            )
            FeatureRow(
                feature = Feature.FACE_TIME,
                title = "FaceTime",
                description = "Rings for incoming FaceTime calls and answers them through the Mac.",
                // A ring already on the shade goes with the switch; its cancel push would be dropped now.
                onFlipped = { on -> if (!on) FaceTimeNotifs.cancelAll(activity) },
            )
            FeatureRow(
                feature = Feature.TRANSLATION,
                title = "Translation",
                description = "Translate and Always translate for messages in another language.",
            )
            FeatureRow(
                feature = Feature.VOICE_ASSISTANT,
                title = "Voice assistant",
                description = "The Voice Text launcher entry: speak a message, hear the relay's answer.",
                // The launcher entry follows the switch right away (VoiceComponent, VoiceActivity.kt).
                onFlipped = { VoiceComponent.apply(activity) },
            )

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SettingsHeader("Relay")
            Note("Where the app's messages come from: your relay's HTTPS address and its token. Test connection uses what is typed here; nothing changes until Save.")
            RelayForm(onChanged = { relayGeneration += 1; onRelayChanged() }, showReset = true)

            // The last line of the screen: which build this is.
            BuildStampLine()
        }
    }
}

/**
 * Which build this is, in one quiet line under a divider (versionLine,
 * BuildStamp.kt): the last line of Settings, and of the first-run relay screen,
 * where a first run that does not get as far as Settings can still be reported
 * with it. Every build is "1.0", so the commit it was made from, with the mark
 * for sources that were not committed, is what tells two of them apart. A long
 * press selects it, for copying into a bug report.
 */
@Composable
internal fun BuildStampLine() {
    HorizontalDivider(Modifier.padding(top = Spacing.sm))
    SelectionContainer {
        Text(
            versionLine(BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT, BuildConfig.BUILD_DATE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.lg),
        )
    }
}

/**
 * One feature switch. The subtitle is the description, then [extra] (a reason
 * the feature cannot work yet), then the relay's verdict when its last report
 * said the feature is unavailable — the switch stays enabled either way: the
 * report is advice, the switch is the user's (Features.kt).
 */
@Composable
private fun FeatureRow(
    feature: Feature,
    title: String,
    description: String,
    extra: String? = null,
    /** Runs after the switch is stored, with its new value. */
    onFlipped: (Boolean) -> Unit = {},
) {
    val unavailable = Features.reportedUnavailable(feature)
    val subtitle = listOfNotNull(
        description,
        extra,
        "The relay reports this as unavailable.".takeIf { unavailable },
    ).joinToString(" ")
    SwitchRow(
        title = title,
        subtitle = subtitle,
        checked = Features.isOn(feature),
        enabled = true,
        onCheckedChange = { on ->
            Features.set(feature, on)
            onFlipped(on)
        },
    )
}

@Composable
internal fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Spacing.lg, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** A whole-row toggle: title, explanation and the switch read as one control to TalkBack. Shared with the relay form. */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.threadRowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Dimens.screenGutter, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Spacer(Modifier.width(Spacing.lg))
        // The row owns the semantics; the switch is the visual only.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A secondary line under a setting; [error] for a refusal. Shared with the relay form. */
@Composable
internal fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
    )
}
