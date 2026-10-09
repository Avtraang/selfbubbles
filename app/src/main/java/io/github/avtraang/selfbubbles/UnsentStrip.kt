package io.github.avtraang.selfbubbles

// The texts that did not get to the relay, as the owner sees them: rows above the
// composer of their chat (UnsentStrip) and a notice at the top of the conversation list
// (UnsentNotice). What a row says and which rows show is decided in SendRecovery.kt
// (unsentLine, unsentRowsFor, unsentChats); the texts themselves are held by Outbox.kt.

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/**
 * Above the composer: the texts of this chat that are not in it. A text that
 * failed, or whose send ended without an answer, shows what happened, the text
 * itself (two lines of it) and three actions; a send that is taking long shows
 * as "Sending…" with no actions, since it may still be delivered. The oldest
 * few show ([rows]), with one line for the rest. Nothing here touches the
 * composer: the owner's draft is his whatever happens to an earlier text.
 *
 * A row offers one way to send ([unsentSendAction]): "Send again", which
 * cannot deliver the text twice, or "Send anyway", which can, and is carried
 * out ([onSendAnyway]) only after the owner has confirmed the warning.
 */
@Composable
fun UnsentStrip(
    rows: UnsentRows,
    onSendAgain: (UnsentText) -> Unit,
    onSendAnyway: (UnsentText) -> Unit,
    onCopy: (UnsentText) -> Unit,
    onDiscard: (UnsentText) -> Unit,
) {
    var confirming by remember { mutableStateOf<UnsentText?>(null) }
    confirming?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(SEND_ANYWAY_TITLE) },
            text = { Text(SEND_ANYWAY_WARNING) },
            confirmButton = { TextButton(onClick = { confirming = null; onSendAnyway(entry) }) { Text(SEND_ANYWAY_LABEL) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
    if (rows.shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        rows.shown.forEachIndexed { i, entry ->
            if (i > 0) HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            UnsentRow(entry, onSendAgain, onAskSendAnyway = { confirming = it }, onCopy, onDiscard)
        }
        if (rows.more > 0) {
            Text(
                unsentMoreLine(rows.more),
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
private fun UnsentRow(
    entry: UnsentText,
    onSendAgain: (UnsentText) -> Unit,
    onAskSendAnyway: (UnsentText) -> Unit,
    onCopy: (UnsentText) -> Unit,
    onDiscard: (UnsentText) -> Unit,
) {
    val why = entry.why
    Column(Modifier.fillMaxWidth().padding(start = Dimens.screenGutter, end = Spacing.sm, top = Spacing.sm)) {
        Row(
            // TalkBack reads the row when it appears: the status and the text it is about.
            Modifier.fillMaxWidth().padding(end = Spacing.sm)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(Dimens.iconSmall), contentAlignment = Alignment.Center) {
                if (why == null) {
                    CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
                } else {
                    Icon(
                        Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Dimens.iconSmall),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    if (why == null) "Sending…" else unsentLine(why, maybeSent = entry.maybeSent),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (why == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Text(
                    entry.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (why == null) {
            Spacer(Modifier.height(Spacing.sm))
        } else {
            // TextButton's default content color is colorScheme.primary. A flow row, so the three
            // wrap onto a second line at a large font size instead of pushing "Send again" off screen.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDiscard(entry) }) { Text("Discard") }
                TextButton(onClick = { onCopy(entry) }) { Text("Copy") }
                when (unsentSendAction(entry)) {
                    UnsentSendAction.SEND_AGAIN -> TextButton(onClick = { onSendAgain(entry) }) { Text("Send again") }
                    UnsentSendAction.SEND_ANYWAY -> TextButton(onClick = { onAskSendAnyway(entry) }) { Text(SEND_ANYWAY_LABEL) }
                }
            }
        }
    }
}

/**
 * At the top of the conversation list: one row per chat that holds a text
 * which was not sent or may not have been ([unsentChats]), whether or not that
 * chat is in the list on screen (it may be pinned, archived, or belong to a
 * relay address that has since been changed). A tap opens the chat, where the
 * texts wait above the composer.
 */
@Composable
fun UnsentNotice(title: String, chat: UnsentChat, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .heightIn(min = Dimens.searchRowMinHeight)
            .padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning, contentDescription = null,
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Dimens.iconSmall),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                unsentChatLine(chat.notSent, chat.unconfirmed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
