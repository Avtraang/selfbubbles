package io.github.avtraang.selfbubbles

// The new-message screen: recipient search with chips, the raw-address entry, the matched
// existing chat's history preview (with the in-app image / PDF / video viewers), and the
// first-message composer. Moved verbatim out of MainActivity.kt (SelfBubbles split, step C5).

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.FieldShape
import io.github.avtraang.selfbubbles.ui.theme.Spacing

// internal for PureHelpersTest
internal fun looksLikeAddress(s: String): Boolean {
    val t = s.trim()
    if (t.contains("@") && t.contains(".")) return true
    return t.count { it.isDigit() } >= 7
}

// internal for PureHelpersTest
internal fun addRecipient(list: MutableList<ContactHit>, hit: ContactHit) {
    if (list.none { it.address.equals(hit.address, ignoreCase = true) }) list.add(hit)
}

/** The recipients as saved instance state keeps them: each one's name, then its address. */
internal fun recipientsSaved(list: List<ContactHit>): List<String> = list.flatMap { listOf(it.name, it.address) }

/** The recipients [saved] holds ([recipientsSaved]); a trailing half pair is dropped. */
internal fun recipientsRestored(saved: List<String>): List<ContactHit> =
    saved.chunked(2).filter { it.size == 2 }.map { ContactHit(name = it[0], address = it[1]) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(vm: ChatVM) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // What the owner typed here (the recipients, the half-typed name, the message) is
    // saveable: it used to be wiped by a rotation, a dark-mode flip and every lock/unlock
    // cycle, each of which rebuilds this screen. Everything else below is looked up again.
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ContactHit>>(emptyList()) }
    val recipients = rememberSaveable(
        saver = listSaver<SnapshotStateList<ContactHit>, String>(
            save = { recipientsSaved(it) },
            restore = { recipientsRestored(it).toMutableStateList() },
        ),
    ) { mutableStateListOf<ContactHit>() }
    var text by rememberSaveable { mutableStateOf("") }
    var existing by remember { mutableStateOf<Thread?>(null) }
    var history by remember { mutableStateOf<List<Msg>>(emptyList()) }
    // The history preview's attachments open as they do in the conversation: the
    // in-app image, PDF and video viewers, and one voice-message player for the
    // screen (released with it), so nothing falls back to the system chooser.
    var fullscreen by remember { mutableStateOf<String?>(null) }
    var pdf by rememberPdfTarget()
    var video by rememberVideoTarget()
    val audio = rememberAudioController()
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        val ex = existing
        if (uris.isNotEmpty() && ex != null) vm.openExistingAndSendAttachments(ex, ctx, uris)
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(250)  // debounce typing before hitting the relay
        results = runCatching { Api.searchContacts(q) }.getOrDefault(emptyList())
    }

    // Recipient set changed: does it match an existing chat? If so, pull its
    // history in — same behavior as iMessage's compose view.
    LaunchedEffect(recipients.toList()) {
        existing = null
        history = emptyList()
        if (recipients.isEmpty()) return@LaunchedEffect
        val match = runCatching { Api.matchChat(recipients.map { it.address }) }.getOrNull()
        existing = match
        if (match != null) {
            history = runCatching { Api.messages(match.chat_guid) }.getOrDefault(emptyList())
        }
    }

    Scaffold(
        // C1, as in the conversation: the bottom bar pads itself for the gesture
        // bar and the keyboard (windowInsetsPadding below), and Scaffold's
        // innerPadding already ends at the top of that bar — so no blanket
        // imePadding here, it would pad twice. History and composer stay visible
        // above the keyboard even though the focused To: field is at the top.
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New Message") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // Same padding as the conversation composer (reference Composer).
            Row(
                Modifier.fillMaxWidth()
                    // C1: clear the gesture bar and rise with the keyboard; safeDrawing
                    // covers the navigation bar, the display cutout and the IME.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                    // Baseline 8dp all round until the field itself shrinks to 40dp (spec C8, step 18);
                    // the reference Composer's tighter padding was sized for the 40dp field.
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    enabled = existing != null,  // a brand-new chat needs a first text
                    modifier = Modifier.size(Dimens.touchTarget),
                ) {
                    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(Icons.Filled.Add, contentDescription = "Attach",
                         tint = if (existing != null) secondary else secondary.copy(alpha = 0.38f))
                }
                Spacer(Modifier.width(Spacing.xs))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f), placeholder = { Text(if (isTextThread(existing ?: vm.current)) "Text message" else "iMessage") },
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                Spacer(Modifier.width(Spacing.sm))
                val ready = recipients.isNotEmpty() && text.isNotBlank() && !vm.creatingChat
                val scheme = MaterialTheme.colorScheme
                val sendContent = if (ready) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                IconButton(
                    onClick = {
                        if (ready) {
                            val ex = existing
                            if (ex != null) {
                                vm.openExistingAndSend(ex, text)
                            } else {
                                vm.createChat(
                                    ctx, recipients.map { it.address },
                                    // Blank names dropped so no title ends in a comma (spec L7).
                                    recipients.map { it.name }.filter { it.isNotBlank() }.joinToString(", "), text,
                                )
                            }
                        }
                    },
                    // A 36dp circle in a 48dp slot, as in the conversation composer.
                    modifier = Modifier.minimumInteractiveComponentSize().size(Dimens.sendButton).clip(CircleShape)
                        .background(if (ready) scheme.primaryContainer else scheme.surfaceContainerHigh),
                ) {
                    if (vm.creatingChat) {
                        CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke,
                                                  color = sendContent)
                    } else {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Send", tint = sendContent)
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "To:", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                recipients.forEach { hit ->
                    InputChip(
                        selected = false,
                        onClick = { recipients.remove(hit) },
                        label = { Text(hit.name, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove",
                                 modifier = Modifier.size(Dimens.chipIcon))
                        },
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.align(Alignment.CenterVertically)
                        .weight(1f).widthIn(min = Dimens.recipientFieldMinWidth)
                        .padding(vertical = Spacing.md),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty() && recipients.isEmpty()) {
                                Text("Name, phone, or email",
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
            }
            HorizontalDivider()
            val typing = query.trim().length >= 2
            when {
                typing -> LazyColumn(Modifier.weight(1f)) {
                    val q = query.trim()
                    if (looksLikeAddress(q)) {
                        item(key = "raw-entry") {
                            ListItem(
                                headlineContent = { Text("Send to \u201C$q\u201D", color = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    addRecipient(recipients, ContactHit(name = q, address = q))
                                    query = ""
                                    keyboard?.hide()
                                    focus.clearFocus()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    items(results) { hit ->
                        ListItem(
                            leadingContent = { Avatar(hit.name, Dimens.avatarSmall, listOf(hit.address)) },
                            headlineContent = {
                                Text(hit.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(hit.address, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.clickable {
                                addRecipient(recipients, hit)
                                query = ""
                                keyboard?.hide()
                                focus.clearFocus()
                            },
                        )
                        HorizontalDivider()
                    }
                }
                history.isNotEmpty() -> {
                    val (bubbles, reactions) = remember(history) { splitReactions(history) }
                    val histState = rememberLazyListState()
                    val imeVisible = WindowInsets.isImeVisible
                    LaunchedEffect(bubbles.size, imeVisible) {
                        if (bubbles.isNotEmpty()) histState.scrollToItem(bubbles.size - 1)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = histState,
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                    ) {
                        itemsIndexed(bubbles, key = { _, m -> if (m.rowid != 0L) m.rowid else m.guid }) { i, m ->
                            val first = i == 0 || !sameRun(bubbles[i - 1], m)
                            val last = i == bubbles.size - 1 || !sameRun(m, bubbles[i + 1])
                            Bubble(m, reactions[m.guid], firstInRun = first, lastInRun = last,
                                   onOpenImage = { fullscreen = it },
                                   onOpenPdf = { pdf = it },
                                   onOpenVideo = { video = it },
                                   audio = audio,
                                   onLongPress = {})
                        }
                    }
                }
                else -> Spacer(Modifier.weight(1f))
            }
        }
    }

    fullscreen?.let { url ->
        FullscreenImage(url, onClose = { fullscreen = null })
    }

    pdf?.let { target ->
        PdfViewer(target, onClose = { pdf = null })
    }

    video?.let { target ->
        VideoPlayer(target, onClose = { video = null })
    }
}
