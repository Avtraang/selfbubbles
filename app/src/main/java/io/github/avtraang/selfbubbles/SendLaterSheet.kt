package io.github.avtraang.selfbubbles

// Send later on screen: the time chooser (a sheet of presets, then Material's date and time
// pickers) and the rows above the composer for the open chat's scheduled texts. Every rule
// here (the presets, the bounds, what a row says and offers) is a plain function in
// SendLater.kt; this file only draws them. ChatVM holds the list and makes the requests.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Schedule
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** What the chooser is choosing a time for: the composer's text, or a new time for a waiting item. */
sealed interface SendLaterTarget {
    data class NewText(val text: String) : SendLaterTarget
    data class Reschedule(val item: ScheduledItem) : SendLaterTarget
}

private enum class ChooserStep { PRESETS, DATE, TIME }

/**
 * The time chooser: the presets ([sendLaterPresets]) and "Pick date & time",
 * which goes through Material's DatePicker and TimePicker in the phone's zone.
 * [onPick] gets the chosen second the moment it is chosen, before anything
 * slides away, so the request goes to the chat that is open now; [onDismiss]
 * follows, as for a swipe down. A time outside the relay's bounds
 * ([scheduleBound]) is said under the rows and not picked: the relay would
 * answer the same with a 422, a round trip later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLaterChooser(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val locale = remember { Locale.getDefault() }
    // The moment the chooser opened: the presets are computed from it once, so the rows do not
    // drift under a finger; a pick is judged against the clock at the tap, below.
    val opened = remember { ZonedDateTime.now(zone) }
    val presets = remember(opened) { sendLaterPresets(opened, locale) }
    var step by remember { mutableStateOf(ChooserStep.PRESETS) }
    var dateMillis by remember { mutableStateOf<Long?>(null) }
    var boundNote by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    fun pick(at: Long) {
        val note = scheduleBoundLine(scheduleBound(at, Instant.now().epochSecond))
        if (note != null) { boundNote = note; return }
        onPick(at)
        // The sheet slides away; a dialog is simply gone (its sheet state is no longer composed).
        if (step == ChooserStep.PRESETS) scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } else onDismiss()
    }

    when (step) {
        ChooserStep.PRESETS -> ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter).padding(bottom = Spacing.xl)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.xs))
                presets.forEach { preset -> ChooserRow(preset.label) { pick(preset.sendAtSeconds) } }
                ChooserRow("Pick date & time…") { boundNote = null; step = ChooserStep.DATE }
                boundNote?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        ChooserStep.DATE -> {
            val today = opened.toLocalDate()
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = dateMillis ?: dateUtcMillisOf(today),
                // The relay's outer bound as the calendar shows it: today and the next thirty days.
                selectableDates = remember(today) {
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean = selectableScheduleDate(utcTimeMillis, today)
                        override fun isSelectableYear(year: Int): Boolean =
                            year == today.year || year == today.plusDays(SCHEDULE_MAX_LEAD_SECONDS / 86_400L).year
                    }
                },
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        enabled = dateState.selectedDateMillis != null,
                        onClick = { dateMillis = dateState.selectedDateMillis; step = ChooserStep.TIME },
                    ) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            ) { DatePicker(state = dateState) }
        }
        ChooserStep.TIME -> TimeStep(
            // The next full hour: on the chosen day that is a time the relay takes, where "now" would not be.
            initial = opened.toLocalTime().plusHours(1).withMinute(0),
            note = boundNote,
            onBack = { boundNote = null; step = ChooserStep.DATE },
            onDismiss = onDismiss,
            onPick = { hour, minute -> pick(pickedSendAt(dateMillis ?: dateUtcMillisOf(opened.toLocalDate()), hour, minute, zone)) },
        )
    }
}

/** One row of the chooser: a label the height of a touch target. */
@Composable
private fun ChooserRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).heightIn(min = Dimens.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The time picker in a dialog of its own: an AlertDialog's fixed width would
 * crop Material's clock face on a narrow phone, so the dialog takes the width
 * its content asks for. "Back" returns to the calendar; "Schedule" picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    initial: LocalTime,
    note: String?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (hour: Int, minute: Int) -> Unit,
) {
    // A 24-hour dial, whatever the locale (SEND_LATER_CLOCK_PATTERN): no AM/PM toggle to land a text twelve hours out.
    val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Column(Modifier.padding(Spacing.xl)) {
                Text(
                    "Pick a time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.lg),
                )
                TimePicker(state = timeState)
                note?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onBack) { Text("Back") }
                    TextButton(onClick = { onPick(timeState.hour, timeState.minute) }) { Text("Schedule") }
                }
            }
        }
    }
}

/**
 * Above the composer: the open chat's scheduled texts (ChatVM.scheduledRows),
 * in the unsent rows' style. A waiting text shows when it goes, the text
 * itself (two lines of it) and Cancel, Change time, Send now; one the relay is
 * sending shows "Sending…" with no actions; a sent one says so quietly until
 * dismissed; a failed one says why, with Delete and Retry (or "Send anyway"
 * where the relay cannot tell what became of the send, asked first in
 * [ScheduledSendAnywayDialog]). The soonest few show, with one line for the
 * rest. Nothing here touches the composer.
 */
@Composable
fun ScheduledStrip(rows: ScheduledRows, onAction: (ScheduledItem, ScheduledRowAction) -> Unit) {
    if (rows.shown.isEmpty()) return
    // Read at each composition: an action or a frame recomposes the strip, and "Today" turns into
    // yesterday's date only for a text the relay failed to send on time.
    val now = ZonedDateTime.now()
    val locale = Locale.getDefault()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        rows.shown.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            ScheduledRow(item, now, locale, onAction)
        }
        if (rows.more > 0) {
            Text(
                scheduledMoreLine(rows.more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
            )
        }
        HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledRow(item: ScheduledItem, now: ZonedDateTime, locale: Locale, onAction: (ScheduledItem, ScheduledRowAction) -> Unit) {
    val status = item.status
    val failed = status == ScheduledState.FAILED
    Column(Modifier.fillMaxWidth().padding(start = Dimens.screenGutter, end = Spacing.sm, top = Spacing.sm)) {
        Row(
            // TalkBack reads the row when it appears or changes: the status and the text it is about.
            Modifier.fillMaxWidth().padding(end = Spacing.sm)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(Dimens.iconSmall), contentAlignment = Alignment.Center) {
                when (status) {
                    ScheduledState.SENDING -> CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
                    ScheduledState.PENDING -> Icon(
                        Schedule, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconSmall),
                    )
                    ScheduledState.SENT -> Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.iconSmall),
                    )
                    ScheduledState.FAILED -> Icon(
                        Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Dimens.iconSmall),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    scheduledRowHead(item, now, locale),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val actions = scheduledRowActions(status, scheduledOutcomeUnknown(item))
        if (actions.isEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
        } else {
            // A flow row, as the unsent rows use: at a large font size the three wrap instead of pushing "Send now" off screen.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                actions.forEach { action -> TextButton(onClick = { onAction(item, action) }) { Text(action.label) } }
            }
        }
    }
}

/**
 * The question before "Send anyway" on a failed scheduled text (ChatVM.scheduledSendAnywayAsk):
 * the relay cannot say whether the earlier try went out and will not send under the same id
 * again, so a new id is the only way to send, and the owner hears what that risks first, as the
 * unsent rows' dialog says it (UnsentStrip). Cancel leaves the item as it is.
 */
@Composable
fun ScheduledSendAnywayDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SCHEDULED_SEND_ANYWAY_TITLE) },
        text = { Text(SCHEDULED_SEND_ANYWAY_WARNING) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(SEND_ANYWAY_LABEL) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
