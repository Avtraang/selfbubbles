package io.github.avtraang.selfbubbles

// The thread list screen: inbox / archive, pinned grid, search over conversation names and
// messages, swipe actions, and the long-press pin menu. Moved verbatim out of MainActivity.kt
// (SelfBubbles split, step C4).

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadList(vm: ChatVM) {
    val threads by vm.threads.collectAsState()
    // Chats holding a text that was not sent, or may not have been (Outbox.kt).
    val outbox by Outbox.texts.collectAsState()
    val unsent = remember(outbox) { unsentChats(outbox) }
    var pinMenuFor by remember { mutableStateOf<Thread?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // The archived view has a back arrow (spec L10); the system back mirrors it.
    BackHandler(enabled = vm.showArchived) { vm.toggleArchiveView() }
    // The system back only dismisses the keyboard. The next back fully exits
    // search: releases focus AND clears the query, which empties the results.
    // Registered after the archived handler, so it wins while searching.
    BackHandler(enabled = searchFocused || searchQuery.isNotEmpty()) {
        focusManager.clearFocus()
        searchQuery = ""
    }
    var searchResults by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) { searchResults = emptyList(); return@LaunchedEffect }
        delay(300)  // debounce before hitting chat.db
        searchResults = runCatching { Api.searchMessages(q) }.getOrDefault(emptyList())
    }
    val visible = threads.filter { it.archived == vm.showArchived }
    // The archived view has no pin grid (spec L10): every archived thread is a plain row.
    val pinned = if (vm.showArchived) emptyList() else visible.filter { it.pinned }.sortedBy { it.pin_index }
    val rest = if (vm.showArchived) visible else visible.filter { !it.pinned }
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    // Spec L14: no New chat over the results just above the keyboard. Not the field's focus:
    // Compose keeps the focus after the keyboard closes, so the button would stay hidden.
    val searching = searchQuery.isNotEmpty() || WindowInsets.isImeVisible

    Scaffold(
        // System bars and keyboard come through innerPadding, so results scroll clear of the
        // keyboard without a blanket imePadding() padding twice.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (vm.showArchived) "Archived" else stringResource(R.string.app_name)) },
                navigationIcon = {
                    if (vm.showArchived) {
                        IconButton(onClick = { vm.toggleArchiveView() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (!vm.showArchived) {
                        val accent = MaterialTheme.colorScheme.primary
                        if (Features.faceTime) {
                            IconButton(onClick = { vm.openFaceTime() }) {
                                Icon(Icons.Filled.Call, contentDescription = "FaceTime", tint = accent)
                            }
                        }
                        if (Features.familyMapUsable) {
                            IconButton(onClick = { vm.openMap() }) {
                                Icon(Icons.Filled.Place, contentDescription = "Family Map", tint = accent)
                            }
                        }
                        // TextButton's default content color is already colorScheme.primary.
                        TextButton(onClick = { vm.toggleArchiveView() }) { Text("Archived") }
                        IconButton(onClick = { vm.openSettings() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = accent)
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // No New chat in the archived view (spec L10) or while searching (L14).
            if (!vm.showArchived && !searching) {
                ExtendedFloatingActionButton(
                    onClick = { vm.openCompose() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // Material hides the text from TalkBack, so the icon carries the button's name.
                    icon = { Icon(Icons.Filled.Create, contentDescription = "New chat") },
                    text = { Text("New chat") },
                    // Full label only at the top of the list and at normal text sizes (spec L4).
                    expanded = atTop && !largeText,
                )
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.pullRefresh() },
            modifier = Modifier.padding(pad).consumeWindowInsets(pad),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            // Room for the New chat button, so the last row can scroll clear of it (spec L4).
            // Room for the New chat button only while it is on screen (not in the archived view or while searching).
            contentPadding = PaddingValues(bottom = if (!vm.showArchived && !searching) Dimens.listBottomPad else Spacing.none),
        ) {
            item(key = "search-bar") {
                // Query state and search logic stay here; only the field's look changed (spec L2).
                SearchField(
                    query = searchQuery, onQueryChange = { searchQuery = it },
                    modifier = Modifier.onFocusChanged { searchFocused = it.isFocused },
                )
            }
            val q = searchQuery.trim()
            // With no conversation to show: that the first load is taking a while, why it failed
            // (with a retry), or that there are none yet. Nothing while a list is on screen, while
            // a search is typed, or for the first moment of the first load, which is all an
            // ordinary launch has of it (threadListNotice, ThreadsLoad.kt).
            val notice = threadListNotice(
                threads.size, searchActive = q.isNotEmpty(), archivedView = vm.showArchived,
                load = vm.threadsLoad, loadingSlow = vm.threadsLoadSlow,
            )
            if (q.isEmpty()) {
            // First of all, a text that did not get out: one row per chat, above the list, in
            // the inbox and the archived view alike, also when its chat is not among the rows
            // below (pinned, archived, or the list could not be loaded). A tap opens the chat,
            // where the text waits above the composer with "Send again" and "Discard".
            items(unsent, key = { "unsent-" + it.chatGuid }) { chat ->
                val thread = threads.firstOrNull { it.chat_guid == chat.chatGuid }
                UnsentNotice(
                    title = thread?.title ?: chat.chatTitle.ifBlank { "Conversation" },
                    chat = chat,
                    onOpen = {
                        vm.open(thread ?: Thread(chat_guid = chat.chatGuid, chat_name = chat.chatTitle.ifBlank { null }, last_rowid = 0))
                    },
                )
                ThreadDivider(start = Spacing.none)
            }
            when (notice) {
                ThreadListNotice.Loading -> item(key = "loading") { LoadingRow() }
                is ThreadListNotice.Failed -> item(key = "load-failed") {
                    LoadFailedPanel(notice.reason, notice.retrying, onRetry = { vm.refreshThreads() })
                }
                ThreadListNotice.Empty -> item(key = "no-threads") {
                    Text(
                        "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    )
                }
                ThreadListNotice.None -> {}
            }
            if (pinned.isNotEmpty()) {
                item(key = "pin-grid") {
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                        pinned.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { t ->
                                    PinItem(t, onOpen = { vm.open(t) }, onLong = { pinMenuFor = t })
                                }
                                repeat(3 - row.size) { Spacer(Modifier.width(Dimens.pinCellWidth)) }
                            }
                        }
                    }
                    ThreadDivider(start = Spacing.none)   // full width under the pins, but a hairline (L5)
                }
            }
            // Not while the first load runs and not under a failed one: the list was never
            // fetched, so "nothing archived" would be a guess (showsNoArchivedLine, ThreadsLoad.kt).
            if (showsNoArchivedLine(vm.showArchived, archivedCount = rest.size, threadCount = threads.size, load = vm.threadsLoad)) {
                item(key = "empty") {
                    // One line of secondary text when there is nothing archived (spec L10).
                    Text(
                        "No archived conversations",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    )
                }
            }
            items(rest, key = { it.chat_guid }) { t ->
                val unread = t.unread > 0
                // Keyed on archived so moving between Inbox and Archive gets a
                // fresh swipe state — a reused dismissed state would leave the
                // row stuck off-screen showing only the coloured panel.
                val dismiss = key(t.chat_guid, t.archived) {
                    rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            when (v) {
                                SwipeToDismissBoxValue.EndToStart ->
                                    vm.archive(t, !t.archived)      // swipe left: archive/restore
                                SwipeToDismissBoxValue.StartToEnd ->
                                    vm.markUnread(t)                // swipe right: mark unread
                                else -> {}
                            }
                            // Never settle as dismissed: snap back, let the list refresh.
                            false
                        },
                    )
                }
                SwipeToDismissBox(
                    state = dismiss,
                    enableDismissFromStartToEnd = !vm.showArchived,   // no mark-unread in Archive view
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        // Direction-aware panel: right swipe = blue "Unread",
                        // left swipe = grey Archive / green Restore.
                        val goingRight = dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                        val bg = when {
                            goingRight -> MaterialTheme.colorScheme.primaryContainer
                            vm.showArchived -> MessagesTheme.colors.bubbleSms      // Restore
                            else -> MaterialTheme.colorScheme.outline              // Archive
                        }
                        val label = when {
                            goingRight -> "Unread"
                            vm.showArchived -> "Restore"
                            else -> "Archive"
                        }
                        Box(
                            Modifier.fillMaxSize().background(bg)
                                .padding(horizontal = Spacing.xl),
                            contentAlignment = if (goingRight) Alignment.CenterStart else Alignment.CenterEnd,
                        ) {
                            // White on every panel, as on the blue one.
                            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                 fontWeight = FontWeight.SemiBold)
                        }
                    },
                ) {
                    ThreadRow(
                        title = t.title,
                        preview = t.preview ?: t.last_date?.let { fmtTime(it) } ?: "",
                        time = t.last_date?.let { fmtListTime(it) } ?: "",
                        unread = unread,
                        avatar = { AvatarWithSms(t, Dimens.avatarRow) },
                        onClick = { vm.open(t) },
                        onLongClick = { pinMenuFor = t },
                    )
                }
                ThreadDivider()
            }
            } else {
                val nameHits = threads.filter { (it.chat_name ?: "").contains(q, ignoreCase = true) }
                if (nameHits.isNotEmpty()) {
                    item(key = "sec-conv") { SectionHeader("Conversations") }
                    items(nameHits, key = { "c-" + it.chat_guid }) { t ->
                        SearchResultRow(
                            title = t.title,
                            avatar = { AvatarWithSms(t, Dimens.avatarSmall) },
                            onClick = { vm.open(t) },
                            // Same long-press menu as the main list: pin,
                            // always-translate — reachable from search too.
                            onLongClick = { pinMenuFor = t },
                        )
                        SearchResultDivider()
                    }
                }
                if (searchResults.isNotEmpty()) {
                    item(key = "sec-msg") { SectionHeader("Messages") }
                    items(searchResults, key = { "m-" + it.rowid }) { hit ->
                        SearchResultRow(
                            title = cleanTitle(hit.chat_name) ?: hit.chat_guid,
                            avatar = { Avatar(hit.chat_name, Dimens.avatarSmall) },
                            onClick = {
                                vm.open(Thread(chat_guid = hit.chat_guid, chat_name = hit.chat_name, last_rowid = 0))
                            },
                            snippet = hit.snippet,
                            time = hit.date?.let { fmtListTime(it) },
                        )
                        SearchResultDivider()
                    }
                }
                if (nameHits.isEmpty() && searchResults.isEmpty() && q.length >= 2) {
                    item(key = "no-results") {
                        Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                             modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl))
                    }
                }
            }
        }
        }
    }

    pinMenuFor?.let { t ->
        AlertDialog(
            onDismissRequest = { pinMenuFor = null },
            title = { Text(cleanTitle(t.chat_name) ?: "Conversation", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (t.pinned) {
                    Row {
                        TextButton(onClick = { vm.movePin(t, -1); pinMenuFor = null }) {
                            Text("\u25C0 Move left")
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(onClick = { vm.movePin(t, +1); pinMenuFor = null }) {
                            Text("Move right \u25B6")
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    // Shown while the feature is on, and as "Stop translating" for a thread
                    // already flagged, so the relay-side flag can be cleared with the feature off.
                    if (TranslationGate.showAutoTranslateControl(Features.translation, t.auto_translate)) {
                        TextButton(onClick = { vm.setAutoTranslate(t, !t.auto_translate); pinMenuFor = null }) {
                            Text(if (t.auto_translate) "Stop translating" else "Always translate")
                        }
                    }
                    TextButton(onClick = { vm.togglePin(t); pinMenuFor = null }) {
                        Text(if (t.pinned) "Unpin" else "Pin")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pinMenuFor = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The first load has been in flight for longer than a moment and there is nothing
 * to show yet: one quiet line, so a slow or unreachable relay does not look like
 * an app without conversations. Sized and placed like "No conversations yet",
 * which may take its place, as the list or the failure panel may. TalkBack reads
 * it when it appears.
 */
@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = Dimens.screenGutter, vertical = Spacing.xl)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            Modifier.size(Dimens.iconSmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            strokeWidth = Dimens.progressStroke,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            "Loading conversations…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The list could not be loaded and there is nothing to show in its place: what
 * happened, the reason in one line (loadFailureMessage, ThreadsLoad.kt) and a way
 * to try again. Shown only while no conversation is held; TalkBack reads it when
 * it appears.
 */
@Composable
private fun LoadFailedPanel(reason: LoadFailure, retrying: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Couldn't load conversations",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                loadFailureMessage(reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // Disabled while a load is in flight, so a tap is seen to have done something.
        Button(onClick = onRetry, enabled = !retrying) {
            Text(if (retrying) "Trying again…" else "Try again")
        }
    }
}
