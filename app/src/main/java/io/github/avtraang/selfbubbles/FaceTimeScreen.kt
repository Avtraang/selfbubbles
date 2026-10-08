package io.github.avtraang.selfbubbles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.avtraang.selfbubbles.ui.theme.CtaShape
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.FieldShape
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/** Loose "is this a phone/email" test — a file-private one already exists in
 *  MainActivity, so we keep a small local copy rather than widen its scope. */
private fun ftLooksLikeAddress(s: String): Boolean {
    val t = s.trim()
    if (t.contains("@") && t.contains(".")) return true
    val digits = t.count { it.isDigit() }
    return digits >= 7 && t.all { it.isDigit() || it in "+()-. " }
}

private fun openLink(ctx: Context, link: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Prefer Chrome — FaceTime web refuses to run in some browsers.
            setPackage("com.android.chrome")
        })
    }.onFailure {
        // Chrome not present/allowed — fall back to the default browser.
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private fun copyLink(ctx: Context, link: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("FaceTime link", link))
    Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
}

private fun shareLink(ctx: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    runCatching {
        ctx.startActivity(Intent.createChooser(send, "Share FaceTime link").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/**
 * FaceTime launcher. Two ways to start a call, both minting a link on the Mac
 * (BlueBubbles POST /facetime/session via the relay's /ft_link):
 *   - "New FaceTime Call": mint a link and open it in the browser (you land in
 *     the call's web lobby and wait to be admitted; the Mac joins, admits you
 *     and drops out by itself only when the relay's optional, display-specific
 *     auto-admit rig is enabled there, otherwise someone has to open the link
 *     in FaceTime on the Mac and admit you: docs/facetime-bridge.md in the
 *     relay repository). Copy/Share to invite.
 *   - Tap a contact: mint a link, text it to them over iMessage/SMS, then open
 *     yours — cross-platform "call this person".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceTimeScreen(vm: ChatVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ContactHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lastLink by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        results = runCatching { Api.searchContacts(q) }.getOrDefault(emptyList())
    }

    // Mint a link, optionally text it to a recipient, then open it here.
    fun startCall(recipient: ContactHit?) {
        if (busy) return
        busy = true
        status = if (recipient != null) "Calling ${recipient.name}…"
                 else "Creating your FaceTime room…"
        scope.launch {
            val link = runCatching { Api.ftNewLink() }.getOrNull()
            if (link.isNullOrBlank()) {
                busy = false
                status = ""
                Toast.makeText(ctx, "Couldn't create the call — link generation " +
                    "failed on the Mac. Try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            lastLink = link
            if (recipient != null) {
                // Text them the link so they can tap to join (works on iPhone
                // or Android). Reuse an existing thread if there is one.
                val msg = "FaceTime: $link"
                val existing = runCatching { Api.matchChat(listOf(recipient.address)) }.getOrNull()
                val sent = runCatching {
                    if (existing != null) Api.send(existing.chat_guid, msg) else Api.createChat(listOf(recipient.address), msg)
                }
                val ok = sent.getOrNull() != null
                // Not "couldn't" unless that is certain: a send that ended without an answer may have delivered the link.
                Toast.makeText(
                    ctx,
                    if (ok) "Sent ${recipient.name} the link" else faceTimeLinkFailureMessage(sent.exceptionOrNull()),
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
            busy = false
            status = ""
            openLink(ctx, link)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FaceTime") },
                navigationIcon = {
                    IconButton(onClick = { vm.closeFaceTime() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // Green like the SMS bubble, white content: the button's content color
            // flows to the icon and label.
            Button(
                onClick = { startCall(null) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MessagesTheme.colors.bubbleSms,
                    contentColor = MessagesTheme.colors.onBubbleSms,
                ),
                shape = CtaShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.md)
                    .height(Dimens.ctaButtonHeight),
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text("New FaceTime Call", style = MaterialTheme.typography.bodyLarge)
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.height(Dimens.iconSmall).width(Dimens.iconSmall),
                                              strokeWidth = Dimens.progressStroke)
                    Spacer(Modifier.width(Spacing.md))
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         style = MaterialTheme.typography.bodyMedium)
                }
            }

            // The last link stays available to copy/share/re-open.
            lastLink?.let { link ->
                Column(Modifier.padding(horizontal = Dimens.screenGutter, vertical = Spacing.xs)) {
                    Text(link, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = { copyLink(ctx, link) }) { Text("Copy") }
                        OutlinedButton(onClick = { shareLink(ctx, link) }) {
                            Icon(Icons.Filled.Share, contentDescription = null,
                                 modifier = Modifier.height(Dimens.iconSmall).width(Dimens.iconSmall))
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Share")
                        }
                        OutlinedButton(onClick = { openLink(ctx, link) }) { Text("Join") }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            Text("Call a contact", color = MaterialTheme.colorScheme.onSurfaceVariant,
                 style = MaterialTheme.typography.labelMedium,
                 modifier = Modifier.padding(horizontal = Dimens.screenGutter))

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
                placeholder = { Text("Name, phone, or email") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null,
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = FieldShape,
            )

            LazyColumn(Modifier.fillMaxSize()) {
                val q = query.trim()
                if (q.length >= 2 && ftLooksLikeAddress(q)) {
                    item(key = "raw") {
                        ListItem(
                            headlineContent = { Text("FaceTime “$q”", color = MaterialTheme.colorScheme.primary) },
                            leadingContent = { Avatar(name = q, size = Dimens.avatarSmall) },
                            modifier = Modifier.clickable(enabled = !busy) {
                                startCall(ContactHit(name = q, address = q))
                            },
                        )
                        HorizontalDivider()
                    }
                }
                items(results) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.name) },
                        supportingContent = {
                            if (hit.address != hit.name) {
                                Text(hit.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { Avatar(name = hit.name, size = Dimens.avatarSmall, handles = listOf(hit.address)) },
                        trailingContent = {
                            Icon(Icons.Filled.Call, contentDescription = null, tint = MessagesTheme.colors.bubbleSms)
                        },
                        modifier = Modifier.clickable(enabled = !busy) { startCall(hit) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
