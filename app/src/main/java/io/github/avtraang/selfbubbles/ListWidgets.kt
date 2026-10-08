package io.github.avtraang.selfbubbles

// Shared list widgets: avatars, network/unread dots, pinned cells, thread and search-result rows,
// section headers and dividers, the pill text field, and the per-sender name color. Moved verbatim
// out of MainActivity.kt (SelfBubbles split, step C3).

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import kotlin.math.abs
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.FieldShape
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

// Sender-name colors: per-person like the avatars, but the theme's brighter
// palette tuned for text legibility rather than for filled circles.
@Composable
fun senderColor(name: String?): Color {
    val names = MessagesTheme.colors.senderNames
    return names[abs((name ?: "").hashCode()) % names.size]
}

/**
 * Thread or person avatar: the relay's group icon, the contact photo, the stacked photos of a
 * group, or initials on a theme fill. `colorKey` picks that fill; pass something stable for the
 * thread (its chat_guid) so a thread keeps its color when its name changes. Defaults to the name.
 */
@Composable
fun Avatar(
    name: String?,
    size: Dp,
    handles: List<String>? = null,
    iconUrl: String? = null,
    sms: Boolean = false,
    colorKey: String? = null,
) {
    val photos = remember(handles, name, ContactPhotos.version) {
        ContactPhotos.photosFor(handles, name)
    }
    var iconFailed by remember(iconUrl) { mutableStateOf(false) }
    // Wrap so the network dot draws as the avatar's own top layer — a parent slot
    // can't clip what's inside the avatar itself.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
    when {
        // Real iMessage group photo, served by the relay. Falls through to the
        // member cluster if the group has no icon set.
        iconUrl != null && !iconFailed -> AsyncImage(
            model = BASE + iconUrl, contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
            onError = { iconFailed = true },
        )
        photos.size == 1 -> AsyncImage(
            model = photos[0], contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        // Group with several photographed members: cluster them like iMessage (spec L9) —
        // the first 74% at the top start, the second 52% at the bottom end inside a 2dp
        // ring of the background color.
        photos.size > 1 -> Box(Modifier.size(size)) {
            AsyncImage(
                model = photos[0], contentDescription = null,
                modifier = Modifier.size(size * Dimens.groupPhotoFirstFraction).align(Alignment.TopStart).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            AsyncImage(
                model = photos[1], contentDescription = null,
                modifier = Modifier.size(size * Dimens.groupPhotoSecondFraction).align(Alignment.BottomEnd)
                    .clip(CircleShape).border(Spacing.xxs, MaterialTheme.colorScheme.background, CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        else -> InitialsAvatar(name ?: "", (colorKey ?: name ?: "").hashCode().toLong(), size)
    }
    // Text (SMS/RCS) thread mark — drawn inside the avatar's own Box, so a
    // surrounding slot can't clip it.
    if (sms) NetworkDot(Modifier.align(Alignment.BottomEnd))
    }  // close wrapper Box
}

/** Convenience: thread avatar that shows the network dot when the thread is a text thread. */
@Composable
internal fun AvatarWithSms(t: Thread, size: Dp) =
    Avatar(t.chat_name, size, t.handles, t.icon_url, sms = isTextThread(t))

/**
 * Avatar for a thread with no photo (spec L11). `colorKey` is anything stable for the thread,
 * such as its id, so a thread keeps its color. Fills come from the theme, not from hex values.
 */
@Composable
fun InitialsAvatar(title: String, colorKey: Long, size: Dp, modifier: Modifier = Modifier) {
    val colors = MessagesTheme.colors
    val initials = initialsOf(title)
    Box(
        modifier.size(size).background(colors.avatarFills[colorKey.mod(colors.avatarFills.size)], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isEmpty()) {
            // A digit says nothing about who it is (short codes, raw numbers); a person icon does.
            Icon(Icons.Filled.Person, contentDescription = null, tint = colors.onAvatar, modifier = Modifier.size(size / 2))
        } else {
            // The one size outside the type scale: initials are sized from the avatar,
            // not from the font setting, so the circle does not grow with large text.
            val fontSize = with(LocalDensity.current) { (size * Dimens.avatarInitialsFraction).toSp() }
            Text(
                initials, color = colors.onAvatar, fontSize = fontSize, lineHeight = fontSize,
                fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},      // TalkBack reads the name, not "DF" before it
            )
        }
    }
}

/**
 * The mark text threads carry on the avatar's bottom end (spec L12): the theme's green inside a
 * 2dp ring of the background color. Its contentDescription merges into the row's semantics.
 */
@Composable
fun NetworkDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .semantics { contentDescription = "Text message" }      // read as part of the row
            .border(Spacing.xxs, MaterialTheme.colorScheme.background, CircleShape)
            .padding(Spacing.xxs)
            .size(Dimens.networkDot)
            .background(MessagesTheme.colors.bubbleSms, CircleShape)
    )
}

/** Pinned cell (spec L3, L6, L7): a 64dp avatar inside 8dp of cell padding, a ringed 14dp unread dot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinItem(t: Thread, onOpen: () -> Unit, onLong: () -> Unit) {
    val unread = t.unread > 0
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(Dimens.pinCellWidth)
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
            .semantics(mergeDescendants = true) { if (unread) stateDescription = "Unread" }
            .padding(Spacing.sm),
    ) {
        Box {
            Avatar(t.chat_name, Dimens.avatarPinned, t.handles, t.icon_url)
            if (unread) {
                // Ring in the background color so the dot reads on any photo.
                UnreadDot(
                    Modifier.align(Alignment.TopEnd)
                        .border(Spacing.xxs, MaterialTheme.colorScheme.background, CircleShape)
                        .padding(Spacing.xxs),
                    size = Dimens.unreadDotPinned,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            t.title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (largeText) 2 else 1,
            overflow = TextOverflow.Ellipsis,     // every cut-off name ends in "…"
            textAlign = TextAlign.Center,
        )
    }
}

private val WhitespaceRun = Regex("\\s+")

/** Unread marker (spec L6): 10dp in rows; pins pass 14dp and add their own ring. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier, size: Dp = Dimens.unreadDot) {
    Box(modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
}

/**
 * One conversation in the list (spec L1, L13). Unlike Material's ListItem, the avatar, dot and
 * time keep the same alignment whether the preview is one line or two: 72dp is a minimum, the
 * row centers vertically, and the time sits on the name's baseline. The caller wraps it in the
 * SwipeToDismissBox; the long press opens the pin menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    title: String,
    preview: String,
    time: String,
    unread: Boolean,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            // Opaque, so the swipe panel behind the row stays hidden until the row moves.
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { if (unread) stateDescription = "Unread" }
            .heightIn(min = Dimens.threadRowMinHeight)     // a minimum, never a fixed height
            .padding(end = Dimens.screenGutter)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,     // avatar and dot stay centered in every row
    ) {
        // Fixed gutter so read and unread rows line up.
        Box(Modifier.width(Dimens.unreadGutter), contentAlignment = Alignment.Center) {
            if (unread) UnreadDot()
        }
        avatar()
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                if (time.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        time,                                // always on the name's baseline
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            // A line break inside the message must not use up one of the two preview lines.
            val body = remember(preview) { preview.trim().replace(WhitespaceRun, " ") }
            if (body.isNotEmpty()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The field look for the whole app: filled for search, outlined for the composer. A value-based
 * BasicTextField with the pill drawn in its decoration box; the 4dp above and below are inside the
 * field, so the full 48dp height takes taps while 40dp is drawn.
 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,     // a 48dp IconButton, such as the search field's clear button
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            PillDecoration(value.isEmpty(), placeholder, filled, leading, trailing, innerTextField)
        },
    )
}

/**
 * The pill drawn around a field's input (spec C8 / L2): a 40dp minimum that grows with the font
 * size and with lines, FieldShape, a 1dp outline border (or a surfaceContainer fill when [filled]),
 * the placeholder in bodyLarge secondary text. Shared by PillTextField and the thread composer,
 * which keeps its own state-based BasicTextField so the keyboard can still insert GIFs and stickers.
 */
@Composable
fun PillDecoration(
    empty: Boolean,
    placeholder: String,
    filled: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    inner: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier
                // The 4dp above and below are inside the field, so the full 48dp height takes taps.
                .padding(vertical = Spacing.xs)
                .fillMaxWidth()
                .heightIn(min = Dimens.fieldHeight)   // a minimum: grows with font size and with lines
                .then(
                    if (filled) Modifier.background(scheme.surfaceContainer, FieldShape)
                    else Modifier.border(Dimens.fieldBorder, scheme.outline, FieldShape)
                )
                .padding(
                    start = Spacing.md,
                    end = if (trailing != null) Dimens.touchTarget else Spacing.md,
                    top = Spacing.sm, bottom = Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.sm))
            }
            Box(Modifier.weight(1f)) {
                if (empty) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        }
        // Laid over the pill's end, so the button keeps its full 48dp target.
        if (trailing != null) {
            Box(Modifier.size(Dimens.touchTarget), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/**
 * The list's search field (spec L2): surfaceContainer fill, no outline in any state, a 20dp icon,
 * the placeholder in secondary text and a clear button once there is text. Focus shows as the cursor.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    PillTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search",
        filled = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The keyboard's Search key closes the keyboard; the results stay.
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); onSearch() }),
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter),
        leading = {
            Icon(
                Icons.Filled.Search, contentDescription = null,
                modifier = Modifier.size(Dimens.iconSmall),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Clear search",
                        modifier = Modifier.size(Dimens.iconSmall),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Hairline that starts where the text starts, not at the screen edge (spec L5). */
@Composable
fun ThreadDivider(start: Dp = Dimens.unreadGutter + Dimens.avatarRow + Spacing.md) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        thickness = Dimens.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Section header over search results (spec L15): secondary text, read as a heading by TalkBack. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics { heading() }
            .padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Spacing.md, bottom = Spacing.xs),
    )
}

/**
 * A search result (spec L15): 56dp minimum, a 40dp avatar, the name in bodyLarge. Name-only for a
 * conversation hit, so five or more fit above the keyboard; a message hit adds its time on the
 * name's baseline and a two-line snippet under it, laid out like a thread row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultRow(
    title: String,
    avatar: @Composable () -> Unit,      // at Dimens.avatarSmall
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    snippet: String? = null,
    time: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = Dimens.searchRowMinHeight)
            .padding(horizontal = Dimens.screenGutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                if (!time.isNullOrEmpty()) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (!snippet.isNullOrBlank()) {
                Text(
                    remember(snippet) { snippet.trim().replace(WhitespaceRun, " ") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Divider for search results: starts under the name, like the thread list's. */
@Composable
fun SearchResultDivider() = ThreadDivider(start = Dimens.screenGutter + Dimens.avatarSmall + Spacing.md)
