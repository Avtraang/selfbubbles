package io.github.avtraang.selfbubbles

// The send sheet: opened by a long press on Send (ConversationScreen.kt). In an iMessage chat it has
// the bubble effects and the screen effects in two labelled groups (MessageEffects.kt); choosing one
// sends the typed text with it, along the same path as a tap. When the relay offers Send later
// (SendLater.kt) a first row opens the time chooser instead, in every kind of chat.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Schedule
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The sheet. [onPick] gets the chosen effect's Apple id the moment it is tapped,
 * before the sheet has slid away: the send goes to the chat that is open now, not
 * to whatever is open when an animation ends. [onDismiss] follows, as it does for
 * a swipe down or a tap outside. [effects] is false in a chat without effects
 * (SMS, RCS, Google Messages), where the sheet is the "Send later" row alone;
 * [onSendLater] is null when the relay does not offer it. It runs once this sheet
 * has slid away, since it opens a sheet of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectPicker(onDismiss: () -> Unit, onPick: (String) -> Unit, effects: Boolean = true, onSendLater: (() -> Unit)? = null) {
    val state = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun pick(effect: MessageEffect) {
        onPick(effect.id)
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }
    fun sendLater(open: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); open() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter).padding(bottom = Spacing.xl)) {
            if (onSendLater != null) SendLaterRow(onClick = { sendLater(onSendLater) }, withEffects = effects)
            if (effects) {
                Text("Send with effect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.xs))
                EffectGroup("Bubble", MessageEffects.bubble, ::pick)
                EffectGroup("Screen", MessageEffects.screen, ::pick)
            }
        }
    }
}

/**
 * The "Send later" row. Under it, in a chat with effects, that the text goes
 * without one: an effect and a time are not combined in this version, and a
 * sheet that offers both must say so rather than let the owner pick an effect
 * first and wonder where it went.
 */
@Composable
private fun SendLaterRow(onClick: () -> Unit, withEffects: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).heightIn(min = Dimens.touchTarget).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconSmall))
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text("Send later", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (withEffects) "Sent at the time you pick, without an effect" else "Sent at the time you pick",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (withEffects) {
        HorizontalDivider(
            thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = Spacing.md),
        )
    }
}

/** One labelled group: its name in the quiet label style, the effects as chips that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectGroup(title: String, effects: List<MessageEffect>, onPick: (MessageEffect) -> Unit) {
    Text(
        title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xxs),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        effects.forEach { effect -> SuggestionChip(onClick = { onPick(effect) }, label = { Text(effect.name) }) }
    }
}
