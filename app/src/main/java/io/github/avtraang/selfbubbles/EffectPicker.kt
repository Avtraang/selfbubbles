package io.github.avtraang.selfbubbles

// The effect picker: a sheet over the composer, opened by a long press on Send in an iMessage chat
// (ConversationScreen.kt), with the bubble effects and the screen effects in two labelled groups
// (MessageEffects.kt). Choosing one sends the typed text with it, along the same path as a tap.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The picker. [onPick] gets the chosen effect's Apple id the moment it is tapped,
 * before the sheet has slid away: the send goes to the chat that is open now, not
 * to whatever is open when an animation ends. [onDismiss] follows, as it does for
 * a swipe down or a tap outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun pick(effect: MessageEffect) {
        onPick(effect.id)
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter).padding(bottom = Spacing.xl)) {
            Text("Send with effect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.xs))
            EffectGroup("Bubble", MessageEffects.bubble, ::pick)
            EffectGroup("Screen", MessageEffects.screen, ::pick)
        }
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
