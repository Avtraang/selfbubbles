package io.github.avtraang.selfbubbles

import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import java.util.Locale

/*
 * The two screens of a share from another app (ShareActivity -> ChatVM.pendingShare):
 *  - ChatPickerScreen: "Share to", the list's own search field, pins and rows;
 *  - ShareConfirmSheet: "Send to <chat>?" with the files listed, Send / Cancel.
 * Both reuse the thread list's pieces (SearchField, PinItem, ThreadRow,
 * SearchResultRow, the dividers and headers) rather than drawing their own.
 */

/**
 * Where a share goes: pinned threads, then the rest, searched by name with the
 * list's field. A tap chooses; the system back (or the X) cancels the share and
 * its copied files. Only threads that can take the share are offered: for files
 * that is [canReceiveSharedFiles].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPickerScreen(vm: ChatVM) {
    val share = vm.pendingShare ?: return
    val threads by vm.threads.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // As on the list: the first back leaves search, the next one cancels the share.
    // The dispatcher runs the most recently registered enabled handler, so the
    // search one is registered after the cancel one and wins while searching;
    // the other way round a back meant for the keyboard threw the share away.
    BackHandler { vm.cancelShare() }
    BackHandler(enabled = searchFocused || searchQuery.isNotEmpty()) {
        focusManager.clearFocus()
        searchQuery = ""
    }

    val visible = threads.filter { !it.archived && (!share.hasFiles || canReceiveSharedFiles(it)) }
    val pinned = visible.filter { it.pinned }.sortedBy { it.pin_index }
    val rest = visible.filter { !it.pinned }
    val q = searchQuery.trim()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Share to", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = { vm.cancelShare() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel share")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad),
            contentPadding = PaddingValues(bottom = Spacing.lg),
        ) {
            item(key = "search-bar") {
                SearchField(
                    query = searchQuery, onQueryChange = { searchQuery = it },
                    modifier = Modifier.onFocusChanged { searchFocused = it.isFocused },
                )
            }
            if (q.isEmpty()) {
                if (pinned.isNotEmpty()) {
                    item(key = "pin-grid") {
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                            pinned.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    row.forEach { t ->
                                        PinItem(t, onOpen = { vm.chooseShareTarget(t) }, onLong = {})
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.width(Dimens.pinCellWidth)) }
                                }
                            }
                        }
                        ThreadDivider(start = Spacing.none)
                    }
                }
                items(rest, key = { it.chat_guid }) { t ->
                    ThreadRow(
                        title = t.title,
                        preview = t.preview ?: "",
                        time = t.last_date?.let { fmtListTime(it) } ?: "",
                        unread = false,       // the picker is about where to send, not what is unread
                        avatar = { AvatarWithSms(t, Dimens.avatarRow) },
                        onClick = { vm.chooseShareTarget(t) },
                        onLongClick = {},
                    )
                    ThreadDivider()
                }
            } else {
                val hits = visible.filter { (it.chat_name ?: "").contains(q, ignoreCase = true) }
                if (hits.isNotEmpty()) {
                    item(key = "sec-conv") { SectionHeader("Conversations") }
                    items(hits, key = { "c-" + it.chat_guid }) { t ->
                        SearchResultRow(
                            title = t.title,
                            avatar = { AvatarWithSms(t, Dimens.avatarSmall) },
                            onClick = { vm.chooseShareTarget(t) },
                        )
                        SearchResultDivider()
                    }
                } else {
                    item(key = "no-results") {
                        Text(
                            "No results", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A share aimed at one chat by a direct-share target, while the thread list
 * loads on a cold start (ChatVM.shareResolving): the same "Share to" bar as the
 * picker with a spinner under it, so the owner is not asked to pick a chat they
 * just picked. Its own back, or the X, cancels the share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareWaitScreen(onCancel: () -> Unit) {
    BackHandler { onCancel() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Share to", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel share")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier.size(Dimens.iconSmall).semantics { contentDescription = "Opening the chat" },
                strokeWidth = Dimens.progressStroke,
            )
        }
    }
}

/**
 * "Send to <chat>?": every copied file with its name, size and a thumbnail
 * (photos through Coil, videos from their first frame), then Send or Cancel.
 * Dismissing it any other way counts as Cancel: the copied files go.
 */
@Composable
fun ShareConfirmSheet(
    share: PendingShare,
    chatTitle: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send to $chatTitle?", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                share.items.forEach { item -> ShareItemRow(item) }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ShareItemRow(item: ShareItem) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.avatarRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShareThumb(item)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatByteSize(item.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A 48dp tile (the list's avatar size, the grid's corner): the photo itself, a
 * video's first frame under a play glyph, or the file's extension on a plain
 * tile. Decorative: the name beside it carries the meaning.
 */
@Composable
private fun ShareThumb(item: ShareItem) {
    val shape = RoundedCornerShape(Dimens.thumbRadius)
    val tile = Modifier.size(Dimens.avatarRow).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
    when {
        item.isImage -> AsyncImage(
            model = item.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = tile,
        )
        item.isVideo -> {
            // No video decoder in the image loader: the first frame comes from the
            // platform's retriever, read once per file off the main thread.
            val frame by produceState<ImageBitmap?>(initialValue = null, item.file) {
                value = withContext(Dispatchers.IO) {
                    // Explicit release(): the retriever is AutoCloseable only from API 29.
                    val r = MediaMetadataRetriever()
                    try {
                        runCatching {
                            r.setDataSource(item.file.absolutePath)
                            r.getFrameAtTime(0)?.asImageBitmap()
                        }.getOrNull()
                    } finally {
                        runCatching { r.release() }
                    }
                }
            }
            Box(tile, contentAlignment = Alignment.Center) {
                frame?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSmall),
                    tint = if (frame != null) MessageColorsOnScrim() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Box(tile, contentAlignment = Alignment.Center) {
            Text(
                extensionOf(item.name)?.drop(1)?.uppercase(Locale.ROOT)?.take(4) ?: "FILE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The play glyph over a frame reads in white on any picture, as over the chat's video bubbles. */
@Composable
private fun MessageColorsOnScrim() = io.github.avtraang.selfbubbles.ui.theme.MessagesTheme.colors.onScrim
