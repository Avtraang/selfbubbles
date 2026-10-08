package io.github.avtraang.selfbubbles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/**
 * First run of a build with no relay baked in and nothing saved yet
 * (`RelayConfigStore.current.isConfigured` false): App() shows this instead
 * of the thread list until a config is saved, before anything touches the
 * network. The owner's build always carries a relay, so it never gets here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySetupScreen(onSaved: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Connect to your relay") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "This app talks to your own relay over HTTPS only: the relay must be reachable at an https:// " +
                    "address with a publicly trusted certificate \u2014 for example Tailscale Serve, or a Cloudflare " +
                    "Tunnel behind Cloudflare Access, whose service-token id and secret go in below. See " +
                    "docs/setup.md in the project.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
            )
            RelayForm(onChanged = onSaved, showReset = false)
            // Settings is not reachable from here, so the line that says which build this is
            // (SettingsScreen.kt) closes this screen too.
            BuildStampLine()
        }
    }
}

/**
 * Keeps the [RelayDraft] (the token and the Cloudflare secret as typed) across
 * a rotation in the only place that never touches disk: a ViewModel lives with
 * the activity through configuration changes and is gone with the process.
 */
class RelayFormDraft : ViewModel() {
    val draft = RelayDraft()

    /**
     * After a Save that changed the relay: one `/health` on the saved config,
     * only to hand what the relay offers to [Features] (a fresh stranger build
     * takes its first switch values from it; otherwise it only annotates
     * Settings). The verdict is not shown. Launched here, not in the form's own
     * scope: a first-run Save replaces the form with the thread list at once,
     * which would cancel it.
     */
    fun learnFeatures(saved: RelayConfig) {
        viewModelScope.launch {
            (RelayProbe.probe(saved) as? ProbeOutcome.Connected)?.let {
                Features.relayReported(it)
                // What the relay says it can do to a sent message, when it says: Edit and Undo Send follow it.
                ChangeSupport.relayReported(saved.base, it.capabilities)
            }
        }
    }
}

/**
 * The relay form: the whole of [RelaySetupScreen], and the "Relay" section of
 * Settings (with [showReset]). Fields pre-fill from the store, so the owner
 * sees their values, masked. Save is disabled while the typed values break a
 * rule (the rule is shown by its field); Test connection runs on the typed
 * values, never the saved ones. [onChanged] runs after a Save or a Reset has
 * swapped the store (ChatVM.relayConfigChanged reconnects the socket).
 */
@Composable
fun RelayForm(onChanged: () -> Unit, showReset: Boolean) {
    val formModel = viewModel<RelayFormDraft>()
    val draft = formModel.draft
    // What survives: the relay URL, the Cloudflare switch and client id, and the
    // map URL ride the saved instance state (rotation, a lock cycle, process
    // death). The token and the secret never do — nothing a stranger types into
    // those two fields is written anywhere but this in-memory form: not to
    // SharedPreferences (only Save writes the store), not to a SavedStateHandle
    // or a Bundle. They survive a rotation in [draft] (memory only), and after
    // process death they come back as the store's values, so a half-typed
    // token or secret is lost then, by design (RelayFormSaver has the rules).
    // The two masked fields use the password keyboard but are not opted out of
    // the Android autofill framework; whether an autofill service offers to
    // save them is a check to make once on a device.
    val saver = remember(draft) {
        listSaver<RelayFormState, Any>(
            save = { f -> RelayFormSaver.save(f, draft) },
            restore = { s -> RelayFormSaver.restore(s, draft, RelayFormState.from(RelayConfigStore.current)) },
        )
    }
    var form by rememberSaveable(stateSaver = saver) {
        mutableStateOf(RelayFormSaver.open(RelayFormState.from(RelayConfigStore.current), draft))
    }
    var revealToken by remember { mutableStateOf(false) }
    var revealSecret by remember { mutableStateOf(false) }
    /** The last Test connection's verdict; cleared by any edit. */
    var outcome by remember { mutableStateOf<ProbeOutcome?>(null) }
    /** "Saved", or why the save was refused (field names only; see RelayConfigValidator). */
    var saveNote by remember { mutableStateOf<FormNote?>(null) }
    var testing by remember { mutableStateOf(false) }
    // Cancelled with the form: a probe in flight dies when the screen is left.
    val scope = rememberCoroutineScope()
    var testJob by remember { mutableStateOf<Job?>(null) }

    fun edit(next: RelayFormState) {
        form = next
        draft.token = next.token
        draft.cfClientSecret = next.cfClientSecret
        outcome = null
        saveNote = null
    }

    fun reload(note: String) {
        testJob?.cancel()
        form = form.reloaded(RelayConfigStore.current)
        draft.token = form.token
        draft.cfClientSecret = form.cfClientSecret
        outcome = null
        saveNote = FormNote(note, error = false)
    }

    fun test() {
        val typed = form.toConfig()
        testJob?.cancel()
        testJob = scope.launch {
            testing = true
            outcome = null
            try {
                val o = RelayProbe.probe(typed)
                outcome = o
                // What the relay offers: seeds a fresh stranger build once, annotates Settings otherwise.
                if (o is ProbeOutcome.Connected) {
                    Features.relayReported(o)
                    // Counts only when the typed address is the relay in force (ChangeSupport checks).
                    ChangeSupport.relayReported(typed.base, o.capabilities)
                }
            } finally {
                testing = false
            }
        }
    }

    // Save and Reset tell the caller only when the live config really changed: the
    // owner saving their untouched form stores nothing (RelayConfigStore.save) and
    // keeps the socket it has.
    fun save() {
        val before = RelayConfigStore.current
        val result = runCatching {
            val validated = RelayConfigValidator.validate(form.toConfig())
            RelayConfigStore.instance.save(validated)
        }
        result.onSuccess { after ->
            reload("Saved")
            if (after != before) {
                onChanged()
                formModel.learnFeatures(after)
            }
        }.onFailure { e ->
            // The validator's message names fields, never values; anything else gets a fixed line.
            val why = if (e is IllegalArgumentException) e.message ?: "Couldn't save" else "Couldn't save the settings"
            saveNote = FormNote(why, error = true)
        }
    }

    fun reset() {
        val before = RelayConfigStore.current
        RelayConfigStore.instance.reset()
        reload("Back to the build's own values")
        if (RelayConfigStore.current != before) onChanged()
    }

    RelayTextField(
        value = form.base,
        onValueChange = { edit(form.copy(base = it)) },
        label = "Relay URL",
        placeholder = "https://host[:port][/path]",
        problems = form.problemsFor(RelayField.BASE),
        keyboardType = KeyboardType.Uri,
    )
    RelaySecretField(
        value = form.token,
        onValueChange = { edit(form.copy(token = it)) },
        label = "Token",
        revealed = revealToken,
        onToggleReveal = { revealToken = !revealToken },
        problems = form.problemsFor(RelayField.TOKEN),
    )
    SwitchRow(
        title = "Behind Cloudflare Access",
        subtitle = "The relay sits behind a Cloudflare Tunnel with an Access policy; a service token's id and secret go with every request.",
        checked = form.cfEnabled,
        enabled = true,
        onCheckedChange = { on -> edit(form.copy(cfEnabled = on)) },
    )
    if (form.cfEnabled) {
        RelayTextField(
            value = form.cfClientId,
            onValueChange = { edit(form.copy(cfClientId = it)) },
            label = "Client ID",
            placeholder = "",
            problems = form.problemsForCf(secret = false),
            keyboardType = KeyboardType.Ascii,
        )
        RelaySecretField(
            value = form.cfClientSecret,
            onValueChange = { edit(form.copy(cfClientSecret = it)) },
            label = "Client Secret",
            revealed = revealSecret,
            onToggleReveal = { revealSecret = !revealSecret },
            problems = form.problemsForCf(secret = true),
        )
    }
    RelayTextField(
        value = form.mapUrl,
        onValueChange = { edit(form.copy(mapUrl = it)) },
        label = "Family map URL (optional)",
        placeholder = "https://",
        problems = form.problemsFor(RelayField.MAP),
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
    )
    form.otherProblems.forEach { Note(it, error = true) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = ::test, enabled = form.canTest && !testing) { Text("Test connection") }
        Button(onClick = ::save, enabled = form.canSave) { Text("Save") }
    }
    if (testing) {
        Row(
            Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
            Spacer(Modifier.width(Spacing.sm))
            Text("Testing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    outcome?.let { o ->
        Note(o.message, error = o !is ProbeOutcome.Connected)
        if (o is ProbeOutcome.Connected && o.outdated) {
            Note("Update the relay: it speaks protocol ${o.protocol}, this app expects $RELAY_PROTOCOL_EXPECTED.")
        }
    }
    saveNote?.let { Note(it.text, error = it.error) }
    if (showReset && RelayConfigStore.instance.hasBuildDefaults) {
        TextButton(onClick = ::reset, modifier = Modifier.padding(horizontal = Spacing.sm)) {
            Text("Reset to build defaults")
        }
    }
}

/** A line under the buttons: a confirmation, or a refusal in the error colour. */
private data class FormNote(val text: String, val error: Boolean)

@Composable
private fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    problems: List<String>,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        singleLine = true,
        isError = problems.isNotEmpty(),
        supportingText = if (problems.isEmpty()) null else ({ Text(problems.joinToString("\n")) }),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false, imeAction = imeAction),
    )
}

/** A masked field with a Show/Hide text toggle (the core icon set has no Visibility glyph). Password keyboard: no suggestions, nothing learned. */
@Composable
private fun RelaySecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    problems: List<String>,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
        label = { Text(label) },
        singleLine = true,
        isError = problems.isNotEmpty(),
        supportingText = if (problems.isEmpty()) null else ({ Text(problems.joinToString("\n")) }),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = onToggleReveal) { Text(if (revealed) "Hide" else "Show") } },
    )
}
