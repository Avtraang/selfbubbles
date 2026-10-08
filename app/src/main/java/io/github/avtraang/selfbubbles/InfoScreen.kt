package io.github.avtraang.selfbubbles

// The conversation info screen: the People / Photos / Videos / Files / Links tabs over the
// relay's shared media, the Contacts-app hand-offs, and the small EmptyTab / PlayOverlay
// widgets. Moved verbatim out of MainActivity.kt (SelfBubbles split, step C7); openUrl came
// along because InfoScreen is its only caller.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

private fun openUrl(ctx: android.content.Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(i) }
}

/** Jump to the Contacts app for this handle (offers create if unsaved). */
private fun openContact(ctx: Context, handle: String) {
    val uri = if (handle.contains("@")) "mailto:$handle" else "tel:$handle"
    val i = Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.parse(uri))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

/** Phone-book display name for a handle, or null if not saved. */
private fun lookupContactName(ctx: Context, handle: String): String? = runCatching {
    val (uri, col) = if (handle.contains("@")) {
        Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(handle),
        ) to ContactsContract.Contacts.DISPLAY_NAME
    } else {
        Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(handle),
        ) to ContactsContract.PhoneLookup.DISPLAY_NAME
    }
    ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(vm: ChatVM, t: Thread) {
    val infoScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val media by vm.media.collectAsState()
    var pdf by rememberPdfTarget()
    var video by rememberVideoTarget()
    // One player for the Files tab, like the chat's: nothing is built until a
    // play tap, one item plays at a time, released when this screen goes.
    val filesAudio = rememberAudioController()

    pdf?.let { target ->
        PdfViewer(target, onClose = { pdf = null })
    }

    video?.let { target ->
        VideoPlayer(target, onClose = { video = null })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // 1:1 chats: jump to the contact card (call, video, edit —
                    // the Contacts app's job, not ours). Shows a create-contact
                    // sheet instead if the number isn't saved yet.
                    val handle = t.handles.firstOrNull()
                    if (!t.is_group && handle != null) {
                        IconButton(onClick = { openContact(ctx, handle) }) {
                            Icon(Icons.Filled.Person, contentDescription = "Contact")
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (vm.mediaError) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Couldn't load shared media.\nMake sure the relay is running the latest version.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Scaffold
        }
        val m = media
        if (m == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val photos = m.attachments.filter { it.mime_type?.startsWith("image/") == true }
        val videos = m.attachments.filter { it.mime_type?.startsWith("video/") == true }
        val files = m.attachments.filter {
            val mm = it.mime_type ?: ""
            !mm.startsWith("image/") && !mm.startsWith("video/")
        }

        var tab by remember { mutableStateOf(0) }
        val showPeople = t.is_group && t.handles.isNotEmpty()
        val titles = buildList {
            if (showPeople) add("People ${t.handles.size}")
            add("Photos ${photos.size}"); add("Videos ${videos.size}")
            add("Files ${files.size}"); add("Links ${m.links.size}")
        }
        val off = if (showPeople) 1 else 0

        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(s, style = MaterialTheme.typography.labelMedium, maxLines = 1) })
                }
            }
            // Box gives the tab content the Column's *remaining* height. Without
            // a bounded height here, LazyVerticalGrid.fillMaxSize() collapses to
            // zero and the grid renders blank.
            Box(Modifier.weight(1f).fillMaxSize()) {
            when {
                showPeople && tab == 0 -> LazyColumn(Modifier.fillMaxSize()) {
                    items(t.handles, key = { it }) { h ->
                        val name = remember(h) { lookupContactName(ctx, h) }
                        ListItem(
                            leadingContent = { Avatar(name ?: h, Dimens.avatarSmall, listOf(h)) },
                            headlineContent = {
                                Text(name ?: h, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = if (name != null) {
                                {
                                    Text(h, style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else null,
                            trailingContent = {
                                IconButton(onClick = { openContact(ctx, h) }) {
                                    Icon(Icons.Filled.Person, contentDescription = "Contact",
                                         tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                tab == off + 0 -> if (photos.isEmpty()) EmptyTab("No photos shared yet.") else
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        contentPadding = PaddingValues(Spacing.xxs),
                    ) {
                        items(photos, key = { it.guid }) { a ->
                            AsyncImage(
                                // Decode at thumbnail size — full-res decodes of
                                // hundreds of photos blank the whole grid.
                                model = ImageRequest.Builder(ctx).data(BASE + a.url)
                                    .size(300).crossfade(true).build(),
                                contentDescription = a.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.aspectRatio(1f)
                                    .clip(RoundedCornerShape(Dimens.thumbRadius))
                                    .clickable { openExternal(ctx, BASE + a.url, a.mime_type ?: "image/*", a.name, infoScope) },
                            )
                        }
                    }
                tab == off + 1 -> if (videos.isEmpty()) EmptyTab("No videos shared yet.") else
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        contentPadding = PaddingValues(Spacing.xxs),
                    ) {
                        items(videos, key = { it.guid }) { a ->
                            Box(
                                modifier = Modifier.aspectRatio(1f)
                                    .clip(RoundedCornerShape(Dimens.thumbRadius)).background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable {
                                        // Built-in player: streams with the auth header, which another app can't send.
                                        if (isRelayUrl(BASE + a.url)) video = VideoTarget(BASE + a.url, a.name, videoMimeFor(a.mime_type, a.name))
                                        else openExternal(ctx, BASE + a.url, a.mime_type ?: "video/*", a.name, infoScope)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(ctx)
                                        .data("$BASE/thumbnail/${a.guid}").size(300).build(),
                                    contentDescription = a.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                )
                                PlayOverlay()
                            }
                        }
                    }
                tab == off + 2 -> if (files.isEmpty()) EmptyTab("No files shared yet.") else
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files) { a ->
                            val url = BASE + a.url
                            // Audio plays inline, the same player as the chat bubble (relay
                            // audio in iMessage chats only; SMS/RCS threads keep the file row).
                            if (!isTextThread(t) && isAudioAttachment(a.mime_type, a.name) && isRelayUrl(url)) {
                                val colors = MessagesTheme.colors
                                ListItem(
                                    headlineContent = {
                                        AudioBubble(
                                            guid = a.guid, url = url, name = a.name, controller = filesAudio,
                                            bg = colors.bubbleReceived, fg = colors.onBubbleReceived,
                                            onLongPress = {},
                                        )
                                    },
                                    supportingContent = { a.date?.let { Text(fmtTime(it)) } },
                                )
                                HorizontalDivider()
                                return@items
                            }
                            // Contact cards show the same card as the chat bubble, in the received pair.
                            if (isContactCardAttachment(a.mime_type, a.name) && isRelayUrl(url)) {
                                val colors = MessagesTheme.colors
                                ListItem(
                                    headlineContent = {
                                        ContactCardBubble(
                                            guid = a.guid, url = url, name = a.name,
                                            bg = colors.bubbleReceived, fg = colors.onBubbleReceived,
                                            onLongPress = {},
                                            onOpenExternally = { openExternal(ctx, url, a.mime_type ?: "*/*", a.name, infoScope) },
                                        )
                                    },
                                    supportingContent = { a.date?.let { Text(fmtTime(it)) } },
                                )
                                HorizontalDivider()
                                return@items
                            }
                            ListItem(
                                leadingContent = {
                                    Box(
                                        Modifier.size(Dimens.avatarRow).clip(RoundedCornerShape(Dimens.thumbRadius))
                                            .background(MaterialTheme.colorScheme.surfaceContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AsyncImage(
                                            model = "$BASE/thumbnail/${a.guid}",
                                            contentDescription = a.name,
                                            modifier = Modifier.matchParentSize(),
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(a.name ?: "file", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { a.date?.let { Text(fmtTime(it)) } },
                                modifier = Modifier.clickable {
                                    // PDFs open in the built-in viewer; the system chooser often offers none.
                                    if (isPdfAttachment(a.mime_type, a.name) && isRelayUrl(BASE + a.url)) pdf = PdfTarget(BASE + a.url, a.name)
                                    else if (isVideoAttachment(a.mime_type, a.name) && isRelayUrl(BASE + a.url)) video = VideoTarget(BASE + a.url, a.name, videoMimeFor(a.mime_type, a.name))
                                    else openExternal(ctx, BASE + a.url, a.mime_type ?: "*/*", a.name, infoScope)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                else -> if (m.links.isEmpty()) EmptyTab("No links shared yet.") else
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(m.links) { l ->
                            ListItem(
                                headlineContent = {
                                    Text(l.url, color = MaterialTheme.colorScheme.primary,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    val who = l.sender ?: ""
                                    val when_ = l.date?.let { fmtTime(it) } ?: ""
                                    Text(listOf(who, when_).filter { it.isNotBlank() }.joinToString(" \u00B7 "))
                                },
                                modifier = Modifier.clickable { openUrl(ctx, l.url) },
                            )
                            HorizontalDivider()
                        }
                    }
            }
            }  // close weighted Box
        }
    }
}

@Composable
fun EmptyTab(message: String) {
    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(message)
    }
}

@Composable
fun PlayOverlay() {
    Box(
        Modifier.size(Dimens.playOverlay).clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MessagesTheme.colors.onScrim)
    }
}
