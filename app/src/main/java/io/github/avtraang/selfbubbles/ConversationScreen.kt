package io.github.avtraang.selfbubbles

// The open chat: the top bar, the message list with day dividers, tapbacks and the in-chat
// search overlay, the composer with its attachment panel, the relay's warning strip and the
// send sound. Moved verbatim out of MainActivity.kt (SelfBubbles split, step C6).

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.max
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.avtraang.selfbubbles.ui.theme.ArrowUpward
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

private val REACTION_CHOICES = listOf(
    "\u2764\uFE0F" to "love", "\uD83D\uDC4D" to "like", "\uD83D\uDC4E" to "dislike",
    "\uD83D\uDE02" to "laugh", "\u203C\uFE0F" to "emphasize", "\u2753" to "question",
)

/** One tapback emoji on a message: how many people added it, and whether you are one of them. */
data class Reaction(val emoji: String, val count: Int, val mine: Boolean)

// internal for PureHelpersTest
internal fun splitReactions(all: List<Msg>): Pair<List<Msg>, Map<String, List<Reaction>>> {
    val bubbles = ArrayList<Msg>()
    val perTarget = HashMap<String, LinkedHashMap<String, MutableList<String>>>()
    for (m in all.sortedBy { it.rowid }) {
        val t = m.assoc_type ?: 0
        if (t in 2000..3999) {
            if (t in 2000..2005 || t in 3000..3005) {
                val target = m.assoc_guid?.removePrefix("bp:")?.substringAfterLast("/") ?: continue
                val emoji = TAPBACK_EMOJI[t % 1000]
                val who = if (m.is_from_me) "me" else (m.sender_handle ?: m.sender ?: "?")
                val bySender = perTarget.getOrPut(target) { LinkedHashMap() }
                val list = bySender.getOrPut(who) { mutableListOf() }
                if (t < 3000) list.add(emoji) else list.remove(emoji)
            }
            continue
        }
        bubbles.add(m)
    }
    // One chip per emoji, in first-seen order, counting senders (spec C12).
    val grouped = perTarget.mapValues { (_, bySender) ->
        val byEmoji = LinkedHashMap<String, Reaction>()
        for ((who, emojis) in bySender) for (e in emojis.distinct()) {
            val cur = byEmoji[e]
            byEmoji[e] = Reaction(e, (cur?.count ?: 0) + 1, (cur?.mine ?: false) || who == "me")
        }
        byEmoji.values.toList()
    }.filterValues { it.isNotEmpty() }
    return bubbles to grouped
}

/**
 * Spec C14 (reference WarningStrip): the relay's send_warning on Mac-side text threads, full
 * width directly above the composer. Informational only — no action, by the owner's decision.
 * TalkBack reads it when it appears (liveRegion = Polite).
 */
@Composable
private fun WarningStrip(text: String) {
    val colors = MessagesTheme.colors
    Surface(
        color = colors.warningContainer,
        contentColor = colors.onWarningContainer,
        // mergeDescendants so the live region carries the strip's text (a bare container announces nothing).
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(Dimens.iconSmall))
            Spacer(Modifier.width(Spacing.sm))
            Text(text, style = MaterialTheme.typography.bodyMedium)    // wraps; no maxLines
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun Conversation(vm: ChatVM, t: Thread) {
    val messages by vm.messages.collectAsState()
    // State-based text field: the only Compose text field that accepts keyboard
    // GIF/sticker content (InputConnection.commitContent). Saveable, so a
    // half-typed draft now survives rotation as well (per thread: App() wraps
    // this screen in key(chat_guid)).
    val input = rememberTextFieldState()
    // ---- edit mode (EditUnsend.kt has the rules; ChatVM holds the mode, so a rotation keeps it) ----
    // "Edit" in the long-press panel turns the composer into an editor for that message: a banner,
    // the message's text in the field, the send button submitting the edit. This screen only shows
    // what the mode says and reports what is typed.
    val edit = editModeFor(vm.editing, t.chat_guid)
    // Saved with the field (the same saveable scope, so the same chat): the draft edit mode set
    // aside, while it is on. The field outlives the process and edit mode does not; this note is
    // how a field that comes back holding a half-edited message is told from a draft, and how the
    // set-aside draft gets back into it (composerSettle, EditUnsend.kt).
    var editStash by rememberSaveable { mutableStateOf<String?>(null) }
    // A text the mode puts into the field (the message's text on entering, the stashed draft on
    // leaving) is taken here, once, and the saved note brought in line. The taps that change the
    // mode settle at once, so the field and the banner never disagree for a frame; the effect
    // settles what the answer to an edit brings, whatever arrived while another screen was over
    // this one, and a field restored without its edit mode.
    fun settleComposer() {
        val pending = vm.composerText?.takeIf { it.chatGuid == t.chat_guid }
        val settled = composerSettle(editStash, editModeFor(vm.editing, t.chat_guid), pending)
        settled.field?.let { text ->
            input.edit {
                replace(0, length, text)
                selection = androidx.compose.ui.text.TextRange(length)   // cursor at the end
            }
        }
        if (pending != null) vm.consumeComposerText(pending)
        editStash = settled.stash
    }
    // A text share (ShareSupport.kt) lands in the draft, never sent on its own: on a
    // new line after anything already typed, cursor at the end.
    val draftAppend = vm.draftAppend
    LaunchedEffect(draftAppend) {
        if (draftAppend != null && draftAppend.chatGuid == t.chat_guid) {
            // First what edit mode left for the field (an edit answered while the chat picker was up,
            // a field restored without its edit mode): the share is appended to that, not replaced by it.
            settleComposer()
            if (editModeFor(vm.editing, t.chat_guid) != null) {
                // The field holds a message being edited: the shared text waits with the stashed draft.
                vm.stashDraftAppend(draftAppend.text)
                settleComposer()      // the saved note follows the stash
            } else input.edit {
                val merged = appendToDraft(asCharSequence(), draftAppend.text)
                replace(0, length, merged)
                selection = androidx.compose.ui.text.TextRange(length)   // cursor at the end
            }
            vm.consumeDraftAppend(draftAppend)
        }
    }
    LaunchedEffect(vm.composerText, edit?.stashedDraft, edit == null) { settleComposer() }
    LaunchedEffect(input) { snapshotFlow { input.text.toString() }.collect { vm.editTextChanged(it) } }
    // Entering edit mode puts the cursor in the field. A tick, not the mode itself: coming back to a
    // chat that is still in edit mode (a rotation, the lock) does not raise the keyboard again.
    val composerFocus = remember { FocusRequester() }
    var editFocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(editFocusTick) { if (editFocusTick > 0) runCatching { composerFocus.requestFocus() } }
    // The texts of this chat that did not get to the relay (Outbox.kt) show above the
    // composer, each with "Send again", "Copy" and "Discard" (UnsentStrip). They are never
    // written back into the field: what is typed there is left exactly as the owner has it.
    val outbox by Outbox.texts.collectAsState()
    val unsentRows = remember(outbox, t.chat_guid) { unsentRowsFor(outbox, t.chat_guid) }
    var reactTarget by remember { mutableStateOf<Msg?>(null) }
    var fullscreen by remember { mutableStateOf<String?>(null) }
    var pdf by rememberPdfTarget()
    var video by rememberVideoTarget()
    val audio = rememberAudioController()   // one voice-message player per chat; released with the screen
    val listState = rememberLazyListState()
    val (bubbles, reactions) = remember(messages) { splitReactions(messages) }
    val ctx = LocalContext.current
    // This chat is on screen again (back from the launcher or Recents, or the app lock just opened):
    // its "not sent" notification has done its work, the row it points at is right here. Opening the
    // chat clears it too (ChatVM.open), but coming back to a chat that was already open opens nothing.
    LifecycleResumeEffect(t.chat_guid) {
        if (!AppLock.gated) Notifs.clearUnsent(ctx, t.chat_guid)
        onPauseOrDispose {}
    }
    // System Photo Picker — no storage permissions needed on any API level.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris -> if (uris.isNotEmpty()) vm.sendAttachments(ctx, uris) }
    // Document picker for everything else — PDFs, zips, any file type.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.sendAttachments(ctx, uris) }
    // Inline attachment panel (recent photos grid + category chips), GM-style.
    var attachPanel by remember { mutableStateOf(false) }
    // Nothing can be attached to an edit: the panel closes when edit mode starts and its button is off meanwhile.
    LaunchedEffect(edit != null) { if (edit != null) attachPanel = false }
    val focusManager = LocalFocusManager.current

    // ---- search in this chat (InChatSearch.kt) ----
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    // null = nothing asked yet (or the query is too short); empty = the relay found nothing.
    var searchResults by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    // A jump that is paging older history in; its job is cancelled by Cancel, the X and back.
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var jumpLoading by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<BubbleHighlight?>(null) }
    val scope = rememberCoroutineScope()
    val query = normalizeQuery(searchQuery)
    // Out of search mode with the field cleared, so the next search starts fresh.
    fun exitSearch() { searchQuery = ""; searchMode = false; focusManager.clearFocus() }
    fun closeSearch() { jumpJob?.cancel(); exitSearch() }
    // System back leaves edit mode before it leaves the chat, as the banner's X does: leaving the
    // chat would take the stashed draft with it. Registered before the search handler, so search
    // mode still closes first when both are up.
    BackHandler(enabled = editBackCancels(edit)) { vm.cancelEdit(); settleComposer() }
    // System back leaves search mode first. Registered after App()'s handler (which
    // leaves the chat), so it wins while searching.
    BackHandler(enabled = searchMode) { closeSearch() }
    LaunchedEffect(searchMode) { if (searchMode) searchFocus.requestFocus() }
    // One request at a time: a new query restarts the effect, which drops the older
    // request's result (its coroutine is cancelled, so nothing below runs for it).
    LaunchedEffect(query) {
        searching = false; searchError = false; searchResults = null
        if (query == null) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        try {
            searchResults = Api.searchInChat(t.chat_guid, query, SEARCH_IN_CHAT_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = true
        }
        searching = false
    }
    // Jump to a hit: scroll to it if held, else page older history in until it is
    // (JUMP_PAGE_CAP pages at most), then scroll and tint its bubble. Only the view
    // moves: nothing is sent and the draft is untouched.
    fun jumpTo(hit: SearchHit) {
        jumpJob?.cancel()
        jumpJob = scope.launch {
            // This launch's own Job, so the clean-up below can tell whether it is
            // still the jump the screen holds (ownsJumpSlot).
            val me = coroutineContext[Job]
            try {
                var pages = 0
                while (true) {
                    val held = splitReactions(vm.messages.value).first
                    val idx = indexOfMessage(held, hit.rowid, hit.guid)
                    if (idx != null) {
                        exitSearch()
                        listState.animateScrollToItem(idx)
                        highlight = BubbleHighlight(hit.rowid, hit.guid, SystemClock.uptimeMillis())
                        return@launch
                    }
                    if (!needsPaging(found = false, hasOlder = vm.hasOlder, pagesLoaded = pages)) {
                        Toast.makeText(ctx, "Too far back to jump", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    jumpLoading = true
                    val heldBefore = vm.messages.value.size
                    vm.loadOlder()
                    snapshotFlow { vm.loadingOlder }.first { !it }
                    pages++
                    if (vm.messages.value.size == heldBefore && vm.hasOlder) {
                        // The page failed (loadOlder keeps hasOlder so a scroll can retry); don't spin to the cap.
                        Toast.makeText(ctx, "Couldn't load older messages", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
            } finally {
                // A jump that a newer tap replaced is cancelled, but its cancellation
                // resumes here after the newer Job was stored: only the held jump may
                // clear the state, or the newer jump would run on with no handle on it.
                if (ownsJumpSlot(jumpJob, me)) {
                    jumpLoading = false
                    jumpJob = null
                }
            }
        }
    }

    // reverseLayout anchors the viewport to the bottom: the chat opens on the
    // newest message by construction, and late-loading images can't push you
    // off it. This effect only pulls you down for new messages when you're
    // already at/near the bottom (index 0-1 in reversed space). It keys on the
    // newest message, not the list size: a page of older history lengthens the
    // list at the far end and must not move the viewport.
    LaunchedEffect(newestKey(bubbles)) {
        if (bubbles.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    // Older history: when the last visible item (the oldest on screen, in
    // reversed space) comes within a few items of the oldest held, ask for the
    // page before it. derivedStateOf collapses layout churn to a boolean and
    // distinctUntilChanged fires once per approach, so a scroll gesture that
    // lingers at the end requests one page, not one per frame.
    val nearOldest by remember(bubbles.size) {
        derivedStateOf {
            nearOldestEnd(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, bubbles.size)
        }
    }
    LaunchedEffect(listState, bubbles.size) {
        snapshotFlow { nearOldest }.distinctUntilChanged().filter { it }.collect { vm.loadOlder() }
    }

    // Your own sends pull the view to the bottom immediately (instant
    // feedback), and again when the refetched data actually lands — bound to
    // the data via scrollTick rather than a timer, since the network doesn't
    // keep a schedule. Incoming messages still respect your scroll position.
    LaunchedEffect(vm.sendTick) {
        if (vm.sendTick > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(vm.scrollTick) {
        if (vm.scrollTick > 0) {
            kotlinx.coroutines.yield()   // let the new list finish its layout pass
            listState.scrollToItem(0)
        }
    }

    // Keyboard opening shrinks the viewport; re-pin the bottom so the newest
    // message stays visible above the IME instead of behind it.
    val imeOpen = WindowInsets.isImeVisible
    LaunchedEffect(imeOpen) {
        if (imeOpen && bubbles.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        // C1: the bottom bar pads itself for the gesture bar and the keyboard
        // (windowInsetsPadding below), and Scaffold's innerPadding already ends
        // at the top of that bar — so no imePadding here, it would pad twice.
        // reverseLayout keeps the newest message pinned when the list shrinks.
        topBar = {
            val type = MaterialTheme.typography
            // Spec C4 (reference ConversationTopBar): two lines of text must fit, so the bar
            // grows with the font size instead of clipping the second line.
            val barHeight = with(LocalDensity.current) {
                max(Dimens.topBarMinHeight, type.titleMedium.lineHeight.toDp() + type.labelMedium.lineHeight.toDp() + Spacing.lg)
            }
            Column {
                if (searchMode) {
                    // Search mode: the thread list's field in place of the title, an X that
                    // leaves search mode (the field's own clear button only empties the query).
                    TopAppBar(
                        expandedHeight = barHeight,
                        title = {
                            SearchField(
                                query = searchQuery, onQueryChange = { searchQuery = it },
                                modifier = Modifier.focusRequester(searchFocus),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { closeSearch() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        },
                    )
                } else CenterAlignedTopAppBar(
                    expandedHeight = barHeight,
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Spec C4: the name is 17sp Medium (titleMedium), not the 22sp screen title.
                            Text(t.title, style = type.titleMedium,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // How this thread travels (e.g. "RCS · Google Messages ·
                            // from Pixel (…0100)"): a plain second line under the name,
                            // no pill (it looked like a tappable chip), single line
                            // so the icons never move.
                            t.via_label?.let { via ->
                                Text(
                                    via,
                                    style = type.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchMode = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search in chat", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { vm.openInfo() }) {
                            Icon(Icons.Filled.Info, contentDescription = "Info", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
                // Spec C9: bubbles passing under the header meet an edge, not a flat cut.
                HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            // C1: clear the gesture bar and rise with the keyboard. safeDrawing
            // covers the navigation bar, the display cutout and the IME; the
            // strip, the composer and the attachment panel all sit inside.
            Column(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                ),
            ) {
                // Spec C9: a hairline separates the messages from the composer area
                // (reply banner, warning strip, field and attachment panel).
                HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
                // The chat could not be read: said here, with Retry; what is shown above stays (ChatLoading.kt).
                if (chatLoadFailureShows(vm.chatLoad)) ChatLoadFailedStrip(onRetry = { vm.retryChatLoad() })
                UnsentStrip(
                    rows = unsentRows,
                    onSendAgain = { vm.sendAgain(it); Sfx.playSend(ctx) },
                    onSendAnyway = { vm.sendAnyway(it); Sfx.playSend(ctx) },
                    onCopy = { copyUnsentText(ctx, it.text) },
                    onDiscard = { vm.discardUnsent(it) },
                )
                // Edit mode's banner takes the reply banner's place: the reply target is stashed
                // with the draft while a message is being edited (ChatVM.startEdit).
                if (edit != null) EditBanner(edit, onCancel = { vm.cancelEdit(); settleComposer() })
                vm.replyingTo?.let { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MessagesTheme.colors.bubbleReceived.copy(alpha = 0.5f))
                            .padding(start = Dimens.bubbleGutter, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Replying to " + (if (r.is_from_me) "yourself" else (r.sender ?: "")),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                (r.text ?: "Attachment").replace("\uFFFC", "").trim(),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.cancelReply() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
                if (vm.fallbackMode) {
                    Text(
                        "Fallback mode \u2014 texts & photos only",
                        style = MaterialTheme.typography.labelMedium, color = MessagesTheme.colors.onWarningContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    )
                }
                // Mac-side SMS/RCS thread: replies leave through the Fold's text
                // relay, not iMessage. Informational only — no action here.
                t.send_warning?.let { warning -> WarningStrip(warning) }
            // Reference Composer padding, now that the field is 40dp: 6dp of slot around the
            // 36dp send circle plus Dimens.composerEndPad puts its edge on the bubble edge.
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = Spacing.xs, end = Dimens.composerEndPad, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.Bottom,     // buttons stay on the last line as the field grows
            ) {
                if (vm.sending > 0) {
                    Box(Modifier.size(Dimens.touchTarget), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (attachPanel) attachPanel = false
                            else { focusManager.clearFocus(); attachPanel = true }
                        },
                        enabled = edit == null,      // an edit takes no attachments
                        modifier = Modifier.size(Dimens.touchTarget),
                    ) {
                        Icon(
                            if (attachPanel) Icons.Filled.Close else Icons.Filled.Add,
                            contentDescription = if (attachPanel) "Close attachments" else "Attach",
                            // Material's disabled alpha in edit mode, said here because the tint is set by hand.
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.let { if (edit == null) it else it.copy(alpha = 0.38f) },
                        )
                    }
                }
                // Spec C8: the PillTextField look drawn around the existing state-based field
                // (decorator), not a swap — the field itself stays what the IME can hand GIFs to.
                BasicTextField(
                    state = input,
                    // Read-only while an edit is on its way: what was submitted is what the field shows.
                    readOnly = edit?.sending == true,
                    modifier = Modifier.weight(1f)
                        .focusRequester(composerFocus)
                        // Keyboard GIF/sticker button (InputConnection.commitContent):
                        // only the state-based text field supports it, and this
                        // receiver's presence is what advertises image support to
                        // the IME (Gboard / Samsung Keyboard). Image items from the
                        // keyboard become attachments; anything else (text,
                        // clipboard pastes, drags) is handed back to the field
                        // untouched so it still gets typed.
                        .contentReceiver { content ->
                            if (content.source != TransferableContent.Source.Keyboard ||
                                !content.hasMediaType(MediaType.Image)
                            ) return@contentReceiver content
                            // In edit mode a keyboard GIF would leave as a new message at once, which
                            // is not what "editing" promises: it is refused, and said so.
                            if (editModeFor(vm.editing, t.chat_guid) != null) {
                                Toast.makeText(ctx, "Attachments can't be added while editing", Toast.LENGTH_SHORT).show()
                                return@contentReceiver content.consume { it.uri != null }
                            }
                            val mime = content.clipMetadata.clipDescription
                                .filterMimeTypes("image/*")?.firstOrNull() ?: "image/gif"
                            content.consume { item ->
                                val uri = item.uri ?: return@consume false
                                // Open now, while the IME's URI grant is alive;
                                // the copy itself happens off the main thread.
                                val stream = runCatching { ctx.contentResolver.openInputStream(uri) }
                                    .onFailure { android.util.Log.e("Imsg", "keyboard content open failed (${failureLabel(it)})") }
                                    .getOrNull()
                                if (stream == null) {
                                    Toast.makeText(ctx, "Couldn't send attachment", Toast.LENGTH_SHORT).show()
                                } else {
                                    vm.sendKeyboardContent(ctx, stream, mime)
                                }
                                true
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // Compose defaults to no auto-capitalization; Sentences makes the
                    // first letter (and each one after a period) capitalize itself,
                    // the way every other messaging app behaves.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    // Grows with lines up to six, then scrolls inside (spec C8). Still
                    // no onKeyboardAction, matching the legacy field: Enter = newline.
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    decorator = TextFieldDecorator { inner ->
                        PillDecoration(
                            empty = input.text.isEmpty(),
                            placeholder = if (isTextThread(vm.current)) "Text message" else "iMessage",
                            inner = inner,
                        )
                    },
                )
                val hasText = input.text.isNotBlank()
                val textThread = isTextThread(vm.current)
                val scheme = MaterialTheme.colorScheme
                val msgColors = MessagesTheme.colors
                // Spec C5 (reference Composer): an up arrow in a 36dp circle centered in a 48dp
                // slot — reserve the slot first, then size the circle. Filled with the thread's
                // bubble color when there is something to send. Attachments, GIFs and stickers
                // leave as soon as they are picked, so typed text is the only thing that waits here.
                // In edit mode the same button submits the edit (a check mark), and while the edit
                // is on its way a spinner stands in its slot: the Mac may take twenty seconds over it.
                if (edit?.sending == true) {
                    Box(
                        Modifier.minimumInteractiveComponentSize().size(Dimens.sendButton),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(Dimens.iconSmall).semantics { contentDescription = "Saving edit" },
                            strokeWidth = Dimens.progressStroke,
                        )
                    }
                } else FilledIconButton(
                    onClick = {
                        val text = input.text.toString()
                        if (edit != null) { vm.submitEdit(text); settleComposer() }
                        else if (text.isNotBlank()) { vm.send(text); input.clearText(); Sfx.playSend(ctx) }
                    },
                    enabled = if (edit != null) edit.canSubmit else hasText,
                    modifier = Modifier.minimumInteractiveComponentSize().size(Dimens.sendButton),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (textThread) msgColors.bubbleSms else msgColors.bubbleIMessage,
                        contentColor = if (textThread) msgColors.onBubbleSms else msgColors.onBubbleIMessage,
                        disabledContainerColor = scheme.surfaceContainerHigh,
                        disabledContentColor = scheme.onSurfaceVariant,
                    ),
                ) {
                    if (edit != null) Icon(Icons.Filled.Check, contentDescription = "Save edit", modifier = Modifier.size(Dimens.iconSmall))
                    else Icon(ArrowUpward, contentDescription = "Send", modifier = Modifier.size(Dimens.iconSmall))
                }
            }
            if (attachPanel && edit == null) {
                AttachmentPanel(
                    onGallery = {
                        attachPanel = false
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    onGif = {
                        attachPanel = false
                        // Same permissionless photo picker, filtered to GIFs; the
                        // result lands in the existing pickMedia callback ->
                        // sendAttachments, exactly like a photo.
                        pickMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")
                            )
                        )
                    },
                    onFiles = {
                        attachPanel = false
                        pickFiles.launch(arrayOf("*/*"))
                    },
                    onSend = { uris ->
                        attachPanel = false
                        vm.sendAttachments(ctx, uris)
                    },
                )
            }
            }
        },
    ) { pad ->
        // Drag the conversation leftward to reveal each message's send time,
        // iMessage-style. Springs back on release.
        var peekPx by remember { mutableFloatStateOf(0f) }
        val peek = with(LocalDensity.current) { peekPx.toDp() }
        LazyColumn(
            // pad already reaches the top of the bottom bar (keyboard included).
            Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { peekPx = 0f },
                        onDragCancel = { peekPx = 0f },
                    ) { _, drag ->
                        peekPx = (peekPx - drag).coerceIn(0f, Dimens.timePeekWidth.toPx())
                    }
                },
            state = listState,
            reverseLayout = true,
            // Bubbles carry their gap above only (C3), so the list pads its own ends.
            contentPadding = PaddingValues(vertical = Spacing.sm),
        ) {
            val rev = bubbles.asReversed()
            itemsIndexed(rev, key = { _, m -> if (m.rowid != 0L) m.rowid else m.guid }) { i, m ->
                // reversed space: chronological-previous is i+1, next is i-1
                val prev = rev.getOrNull(i + 1)
                val first = i == rev.size - 1 || !sameRun(rev[i + 1], m)
                val last = i == 0 || !sameRun(m, rev[i - 1])
                // The bubble a search jump landed on: a primary tint that fades out over
                // HIGHLIGHT_MS, frame by frame from the one start time, so a bubble that
                // leaves and re-enters the viewport mid-fade carries on rather than restarts.
                val lit = highlight?.takeIf { it.isHighlightedNow(m, SystemClock.uptimeMillis()) }
                var tint by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(lit) {
                    if (lit == null) { tint = 0f; return@LaunchedEffect }
                    while (true) {
                        val a = withFrameMillis { now -> lit.alphaFor(m, now) }
                        tint = a
                        if (a <= 0f) break
                    }
                    if (highlight == lit) highlight = null
                }
                Box(
                    Modifier.fillMaxWidth().then(
                        if (tint > 0f) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = tint)) else Modifier
                    )
                ) {
                Bubble(m, reactions[m.guid], firstInRun = first, lastInRun = last,
                       peek = peek, onOpenImage = { fullscreen = it },
                       onOpenPdf = { pdf = it },
                       onOpenVideo = { video = it },
                       audio = audio,
                       translation = if (Features.translation) vm.translations[m.guid] else null,
                       onTranslate = if (Features.translation) ({ vm.translate(it) }) else null,
                       onDismissTranslation = { vm.translations.remove(it.guid) },
                       // An Undo Send on its way: said under the bubble until it has emptied.
                       note = if (showsUnsending(m, vm.unsending)) "Unsending…" else null,
                       onLongPress = { reactTarget = it })
                }
                // reverseLayout draws upward, so the divider is emitted *after*
                // its message to land above it on screen.
                if (needsDivider(prev, m)) m.date?.let { DayDivider(it) }
            }
            // The last item in reversed space sits above the oldest message: the
            // page in flight. Its own key, so the bubbles' keys (and the viewport
            // anchored on them) are untouched when it comes and goes.
            if (vm.loadingOlder) item(key = "loading-older") {
                Box(Modifier.fillMaxWidth().padding(Spacing.sm), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(Dimens.iconSmall).semantics { contentDescription = "Loading older messages" },
                        strokeWidth = Dimens.progressStroke,
                    )
                }
            }
        }
        // The hits lie over the messages while there is a query to show them for; an
        // emptied query uncovers the list again. The list stays composed underneath, so
        // the jump can scroll it before the overlay goes.
        if (searchMode && query != null) {
            SearchInChatResults(
                thread = t,
                results = searchResults,
                searching = searching,
                error = searchError,
                jumping = jumpLoading,
                onCancelJump = { jumpJob?.cancel() },
                onPick = { jumpTo(it) },
                modifier = Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize(),
            )
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

    reactTarget?.let { target ->
        // Decided once, as the panel opens (panelChangesFor, EditUnsend.kt): "Edit" and "Undo Send"
        // show only for the owner's own recent iMessage. If Apple's window closes while the panel
        // is open, the relay refuses and the toast says so.
        val changes = remember(target) {
            panelChangesFor(
                target, vm.current ?: t, System.currentTimeMillis(), ChangeSupport.memory(),
                unsending = vm.unsending, editing = vm.editing, saving = vm.savingEdits,
            )
        }
        AlertDialog(
            onDismissRequest = { reactTarget = null },
            confirmButton = {},
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        REACTION_CHOICES.forEach { (emoji, name) ->
                            TextButton(onClick = { vm.react(target, name); reactTarget = null }) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = Spacing.xs))
                    // TextButton's default content color is colorScheme.primary.
                    TextButton(
                        onClick = { vm.startReply(target); reactTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reply") }
                    // Manual translate for languages the script detector can't
                    // spot — Spanish, French, anything written in Latin letters.
                    if (Features.translation && !target.text.isNullOrBlank()) {
                        TextButton(
                            onClick = { vm.translate(target); reactTarget = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Translate") }
                    }
                    if (changes.edit) {
                        TextButton(
                            onClick = {
                                vm.startEdit(target, input.text.toString())
                                settleComposer()
                                editFocusTick++
                                reactTarget = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Edit") }
                    }
                    if (changes.unsend) {
                        TextButton(
                            onClick = { vm.unsend(target); reactTarget = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Undo Send") }
                    }
                }
            },
        )
    }
}

/**
 * Above the composer while it edits a message: the "Replying to" banner's look, with the
 * message's text as it was and a close button that leaves edit mode (the stashed draft comes
 * back). The button is off while the edit is on its way: that request cannot be called back.
 */
@Composable
private fun EditBanner(edit: EditMode, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MessagesTheme.colors.bubbleReceived.copy(alpha = 0.5f))
            .padding(start = Dimens.bubbleGutter, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Focus goes to the field on entering edit mode; the live region is what tells TalkBack why
        // (mergeDescendants so it carries the two lines, as WarningStrip does).
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(
                "Editing message",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                edit.original,
                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel, enabled = !edit.sending) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel edit")
        }
    }
}

/**
 * The in-chat search's hits, laid over the message list: each row is the hit's date
 * (formatted like the thread list) over its snippet, in the thread list's result-row
 * styling with this chat's avatar. One status line for "Searching…", "No results" and a
 * failure; a jump that is paging older history in shows its own line with Cancel.
 */
@Composable
private fun SearchInChatResults(
    thread: Thread,
    results: List<SearchHit>?,
    searching: Boolean,
    error: Boolean,
    jumping: Boolean,
    onCancelJump: () -> Unit,
    onPick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The overlay owns every touch over it: hit-testing only stops at a sibling with
    // a pointer-input node, so without this the "Searching…", "No results", error and
    // blank states would let taps, long-presses and flings through to the hidden
    // message list underneath (its bubbles' menus, viewers and scroll-paging).
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (jumping) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = Dimens.screenGutter, end = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
                Spacer(Modifier.width(Spacing.md))
                Text(
                    "Loading older messages…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // TextButton's default content color is colorScheme.primary.
                TextButton(onClick = onCancelJump) { Text("Cancel") }
            }
            HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
        }
        when {
            searching -> SearchStatusLine("Searching…")
            error -> SearchStatusLine("Couldn't search this chat", color = MaterialTheme.colorScheme.error)
            results == null -> {}
            results.isEmpty() -> SearchStatusLine("No results")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // The index keeps keys unique should the relay repeat a hit (rowid 0 repeats by design).
                itemsIndexed(results, key = { i, hit -> "s-$i-${hit.rowid}-${hit.guid ?: ""}" }) { _, hit ->
                    SearchResultRow(
                        title = searchHitTime(hit.date),
                        avatar = { AvatarWithSms(thread, Dimens.avatarSmall) },
                        onClick = { onPick(hit) },
                        snippet = hit.snippet,
                    )
                    SearchResultDivider()
                }
            }
        }
    }
}

/** One centred line of secondary text for a search state, as the thread list's "No results". */
@Composable
private fun SearchStatusLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text, color = color, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * "Copy" on an unsent text's row: the whole text to the clipboard, so it can be
 * pasted and edited; its row stays until it is sent again or discarded. Android
 * 13 and newer confirm a copy themselves; older versions get a toast.
 */
private fun copyUnsentText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

/** Plays the send sound if res/raw/imsg_send exists; silent otherwise, so the
 *  build never depends on sound files being present. */
internal object Sfx {
    private var pool: android.media.SoundPool? = null
    private var sendId = 0
    private var loaded = false

    fun playSend(ctx: Context) {
        val res = ctx.resources.getIdentifier("imsg_send", "raw", ctx.packageName)
        if (res == 0) return
        val existing = pool
        if (existing == null) {
            val sp = android.media.SoundPool.Builder().setMaxStreams(2)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        // Notification stream, same as the receive tone — one
                        // volume slider governs both (media volume is usually
                        // zero when nothing's playing, which silently ate this).
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build()
            pool = sp
            sp.setOnLoadCompleteListener { p, _, _ -> loaded = true; p.play(sendId, 1f, 1f, 1, 0, 1f) }
            sendId = sp.load(ctx, res, 1)
        } else if (loaded) {
            existing.play(sendId, 1f, 1f, 1, 0, 1f)
        }
    }
}
