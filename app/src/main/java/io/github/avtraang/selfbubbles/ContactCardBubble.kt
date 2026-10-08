package io.github.avtraang.selfbubbles

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Intents.Insert
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.avtraang.selfbubbles.ui.theme.BubbleShape
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import java.io.ByteArrayOutputStream

/** What is known about one contact-card attachment, by guid, for the life of the process. */
private sealed interface ContactCardState {
    data object Loading : ContactCardState
    class Ready(val cards: List<LoadedCard>) : ContactCardState
    /**
     * [retryable] when the download failed (relay away, HTTP error): fetched again next time; not when the
     * file itself cannot be read. [openable] when a tap may still hand the file to another app: not when the
     * relay says it is gone from the Mac, which is what [message] then reads instead of [CONTACT_CARD_ERROR_MESSAGE].
     */
    class Failed(
        val retryable: Boolean,
        val message: String = CONTACT_CARD_ERROR_MESSAGE,
        val openable: Boolean = true,
    ) : ContactCardState
}

/** A parsed card with its photo already decoded to avatar size, so drawing it allocates nothing. */
private class LoadedCard(val card: ContactCard, val avatar: ImageBitmap?)

// Remembered per attachment guid, so a bubble that scrolls out and back in (or is shown again in
// the Files tab) does not download and parse the file again. Bounded: the oldest entries go first.
private const val CARD_CACHE_CAP = 32
private val cardStates = mutableStateMapOf<String, ContactCardState>()
private val cardOrder = ArrayDeque<String>()   // main thread only

private fun rememberState(guid: String, state: ContactCardState) {
    if (!cardStates.containsKey(guid)) {
        cardOrder.addLast(guid)
        while (cardOrder.size > CARD_CACHE_CAP) cardStates.remove(cardOrder.removeFirst())
    }
    cardStates[guid] = state
}

/** The longer side of a photo handed to the Contacts app, and the most it may weigh once compressed (Binder limits). */
private const val INSERT_PHOTO_PX = 720
private const val INSERT_PHOTO_MAX_BYTES = 400 * 1024
private val INSERT_PHOTO_QUALITIES = intArrayOf(85, 60, 40)

/**
 * The card decoded with inJustDecodeBounds first and then at a sample size that
 * brings it down to about [targetPx], so a huge picture never allocates a huge
 * bitmap. Null when the bytes are not a picture.
 */
private fun decodeScaled(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
}

/** The photo re-encoded small enough to ride in an Intent; null when it cannot be made small enough. */
private fun photoForInsert(bytes: ByteArray): ByteArray? {
    val bitmap = decodeScaled(bytes, INSERT_PHOTO_PX) ?: return null
    try {
        for (q in INSERT_PHOTO_QUALITIES) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)) return null
            if (out.size() <= INSERT_PHOTO_MAX_BYTES) return out.toByteArray()
        }
        return null
    } finally {
        bitmap.recycle()
    }
}

private suspend fun loadContactCards(ctx: Context, url: String, name: String?, avatarPx: Int): ContactCardState {
    val got = Downloads.fetchToCache(
        ctx, url, sanitizeContactFileName(name), VCARD_MAX_INPUT_BYTES,
        subDir = contactCardCacheDirName(url), reuse = true,
    )
    val file = when (got) {
        is Downloads.CacheFetch.Ok -> got.file
        Downloads.CacheFetch.TooLarge -> return ContactCardState.Failed(retryable = false)
        Downloads.CacheFetch.Missing ->
            return ContactCardState.Failed(retryable = false, message = ATTACHMENT_MISSING_MESSAGE, openable = false)
        Downloads.CacheFetch.Failed -> return ContactCardState.Failed(retryable = true)
    }
    return withContext(Dispatchers.Default) {
        val cards = runCatching { parseVCards(vcardText(file.readBytes())) }.getOrDefault(emptyList())
        if (cards.isEmpty()) ContactCardState.Failed(retryable = false)
        else ContactCardState.Ready(cards.map { c ->
            LoadedCard(c, c.photo?.let { decodeScaled(it, avatarPx)?.asImageBitmap() })
        })
    }
}

private fun phoneType(label: String): Int = when (label) {
    "Mobile" -> Phone.TYPE_MOBILE
    "Home" -> Phone.TYPE_HOME
    "Work" -> Phone.TYPE_WORK
    "Main" -> Phone.TYPE_MAIN
    "Fax" -> Phone.TYPE_FAX_WORK
    "Pager" -> Phone.TYPE_PAGER
    else -> Phone.TYPE_OTHER
}

private fun emailType(label: String): Int = when (label) {
    "Home" -> Email.TYPE_HOME
    "Work" -> Email.TYPE_WORK
    else -> Email.TYPE_OTHER
}

private fun postalType(label: String): Int = when (label) {
    "Home" -> StructuredPostal.TYPE_HOME
    "Work" -> StructuredPostal.TYPE_WORK
    else -> StructuredPostal.TYPE_OTHER
}

private fun isStandardLabel(label: String) = label in setOf("Mobile", "Home", "Work", "Main", "Fax", "Pager", "Other")

/** A row for [Insert.DATA]: a custom label becomes TYPE_CUSTOM with its text, which the extras alone cannot carry. */
private fun dataRow(mime: String, valueKey: String, value: String, typeKey: String, type: Int, label: String): ContentValues =
    ContentValues().apply {
        put(ContactsContract.Data.MIMETYPE, mime)
        put(valueKey, value)
        if (isStandardLabel(label)) put(typeKey, type) else {
            put(typeKey, ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM)
            put(ContactsContract.Data.DATA3, label)   // LABEL for every kind
        }
    }

/**
 * The system "new contact" screen, filled in from [card]. Needs no permission:
 * the Contacts app does the writing once the person taps Save. [photo] is the
 * already shrunken picture from [photoForInsert], if any.
 */
private fun contactInsertIntent(card: ContactCard, photo: ByteArray?): Intent {
    val i = Intent(Insert.ACTION).apply { type = ContactsContract.RawContacts.CONTENT_TYPE }
    i.putExtra(Insert.NAME, card.displayName)
    card.phones.getOrNull(0)?.let { i.putExtra(Insert.PHONE, it.value); i.putExtra(Insert.PHONE_TYPE, phoneType(it.label)) }
    card.phones.getOrNull(1)?.let { i.putExtra(Insert.SECONDARY_PHONE, it.value); i.putExtra(Insert.SECONDARY_PHONE_TYPE, phoneType(it.label)) }
    card.phones.getOrNull(2)?.let { i.putExtra(Insert.TERTIARY_PHONE, it.value); i.putExtra(Insert.TERTIARY_PHONE_TYPE, phoneType(it.label)) }
    card.emails.getOrNull(0)?.let { i.putExtra(Insert.EMAIL, it.value); i.putExtra(Insert.EMAIL_TYPE, emailType(it.label)) }
    card.emails.getOrNull(1)?.let { i.putExtra(Insert.SECONDARY_EMAIL, it.value); i.putExtra(Insert.SECONDARY_EMAIL_TYPE, emailType(it.label)) }
    card.emails.getOrNull(2)?.let { i.putExtra(Insert.TERTIARY_EMAIL, it.value); i.putExtra(Insert.TERTIARY_EMAIL_TYPE, emailType(it.label)) }
    card.org?.let { i.putExtra(Insert.COMPANY, it) }
    card.title?.let { i.putExtra(Insert.JOB_TITLE, it) }
    card.addresses.getOrNull(0)?.let { i.putExtra(Insert.POSTAL, it.value); i.putExtra(Insert.POSTAL_TYPE, postalType(it.label)) }
    card.note?.let { i.putExtra(Insert.NOTES, it) }

    val rows = ArrayList<ContentValues>()
    card.phones.drop(3).forEach { rows += dataRow(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, it.value, Phone.TYPE, phoneType(it.label), it.label) }
    card.emails.drop(3).forEach { rows += dataRow(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, it.value, Email.TYPE, emailType(it.label), it.label) }
    card.addresses.drop(1).forEach {
        rows += dataRow(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS, it.value, StructuredPostal.TYPE, postalType(it.label), it.label)
    }
    card.urls.forEach { rows += dataRow(Website.CONTENT_ITEM_TYPE, Website.URL, it.value, Website.TYPE, Website.TYPE_OTHER, it.label) }
    card.birthday?.let {
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
            put(Event.START_DATE, it)
            put(Event.TYPE, Event.TYPE_BIRTHDAY)
        }
    }
    photo?.let {
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            put(Photo.PHOTO, it)
        }
    }
    if (rows.isNotEmpty()) i.putParcelableArrayListExtra(Insert.DATA, rows)
    return i
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

private fun start(ctx: Context, intent: Intent, missing: String): Boolean =
    try {
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        toast(ctx, missing)
        false
    } catch (e: RuntimeException) {   // a refused or oversized hand-off
        toast(ctx, "Couldn't open")
        false
    }

/** Puts [text] on the clipboard. Android 13+ shows its own confirmation; older versions get a toast. */
private fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(ctx, "Copied")
}

private fun dial(ctx: Context, number: String) =
    start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "No phone app")

private fun mail(ctx: Context, address: String) =
    start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))), "No email app")

private fun openAddress(ctx: Context, address: String) =
    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))), "No maps app")

/** [launchUrl] is the http(s) address [websiteLaunchUrl] allowed; any other scheme never gets here. */
private fun openWebsite(ctx: Context, launchUrl: String) =
    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)), "No browser")

/**
 * A contact card inside its bubble: the photo or initials, the name, one
 * secondary line, and "Add to contacts" / "Details". Several cards in one file
 * stack. The file is fetched once per attachment guid when the bubble first
 * appears; while it loads a placeholder stands in, and a file that cannot be
 * read shows a plain chip that still opens it elsewhere ([onOpenExternally]).
 * A download that failed is not held against the message: it is fetched again
 * when the bubble next appears, and the chip's first tap retries before a
 * second tap opens the file elsewhere. A file the relay no longer has shows
 * [ATTACHMENT_MISSING_MESSAGE] and offers neither. A long press anywhere opens
 * the message actions like any other bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactCardBubble(
    guid: String,
    url: String,
    name: String?,
    bg: Color,
    fg: Color,
    onLongPress: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    val ctx = LocalContext.current
    val avatarPx = with(LocalDensity.current) { Dimens.avatarPinned.roundToPx() }   // sharp in the dialog too
    val state = cardStates[guid]
    var attempt by remember(guid) { mutableIntStateOf(0) }

    LaunchedEffect(guid, attempt) {
        // A load left at Loading by a bubble that went away, or a download that failed, is started again here.
        val cached = cardStates[guid]
        if (cached is ContactCardState.Ready || (cached is ContactCardState.Failed && !cached.retryable)) return@LaunchedEffect
        rememberState(guid, ContactCardState.Loading)
        rememberState(guid, loadContactCards(ctx.applicationContext, url, name, avatarPx))
    }

    val width = Modifier.widthIn(max = Dimens.bubbleMaxWidth).fillMaxWidth()
    when (state) {
        is ContactCardState.Ready -> Column(width, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.cards.forEach { loaded -> OneCard(loaded, bg, fg, onLongPress) }
        }
        is ContactCardState.Failed -> {
            val canRetry = state.retryable && attempt == 0
            Surface(
                color = bg, shape = BubbleShape,
                modifier = width.combinedClickable(
                    onClick = { if (canRetry) attempt++ else if (state.openable) onOpenExternally() },
                    onLongClick = onLongPress,
                ),
            ) {
                Row(Modifier.padding(horizontal = Dimens.bubblePadH, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar("", 0L, Dimens.avatarRow)
                    Column(Modifier.padding(start = Spacing.sm)) {
                        Text(state.message, style = MaterialTheme.typography.bodyLarge, color = fg)
                        Text(
                            if (canRetry) CONTACT_CARD_RETRY_LABEL else name ?: CONTACT_CARD_LOADING_LABEL,
                            style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        else -> Surface(
            color = bg, shape = BubbleShape,
            modifier = width.combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Row(Modifier.padding(horizontal = Dimens.bubblePadH, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar("", 0L, Dimens.avatarRow)
                Text(
                    CONTACT_CARD_LOADING_LABEL, style = MaterialTheme.typography.bodyLarge, color = fg,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
                )
                CircularProgressIndicator(Modifier.size(Dimens.iconSmall), color = fg, strokeWidth = Dimens.progressStroke)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OneCard(loaded: LoadedCard, bg: Color, fg: Color, onLongPress: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val card = loaded.card
    var details by remember(loaded) { mutableStateOf(false) }
    var adding by remember(loaded) { mutableStateOf(false) }
    val secondary = remember(card) { contactSecondaryLine(card) }

    if (details) ContactDetailsDialog(loaded, onDismiss = { details = false })

    Surface(
        color = bg, shape = BubbleShape,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { details = true }, onLongClick = onLongPress),
    ) {
        Column {
            Row(
                Modifier.padding(start = Dimens.bubblePadH, end = Dimens.bubblePadH, top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardAvatar(loaded, Dimens.avatarRow)
                Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                    Text(card.displayName, style = MaterialTheme.typography.bodyLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (secondary != null) {
                        Text(secondary, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                CardButton("Add to contacts", fg, enabled = !adding, onLongPress = onLongPress) {
                    adding = true
                    scope.launch {
                        try {
                            val photo = card.photo?.let { withContext(Dispatchers.Default) { photoForInsert(it) } }
                            start(ctx, contactInsertIntent(card, photo), CONTACT_CARD_NO_APP_MESSAGE)
                        } finally {
                            adding = false
                        }
                    }
                }
                CardButton("Details", fg, enabled = true, onLongPress = onLongPress) { details = true }
            }
        }
    }
}

/** A text button in the bubble's own colour whose long press still opens the message actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardButton(label: String, fg: Color, enabled: Boolean, onLongPress: () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minHeight = Dimens.touchTarget)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.38f))
    }
}

/** The vCard's own photo, else initials on a theme fill as the thread list draws them. */
@Composable
private fun CardAvatar(loaded: LoadedCard, size: androidx.compose.ui.unit.Dp) {
    val avatar = loaded.avatar
    if (avatar != null) {
        Image(
            bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        InitialsAvatar(loaded.card.displayName, loaded.card.displayName.hashCode().toLong(), size)
    }
}

/**
 * Every field of the card with its label. A phone offers Call or Copy, an email
 * opens the mail app, an address the map and a website the browser; a long
 * press on any row copies it.
 */
@Composable
private fun ContactDetailsDialog(loaded: LoadedCard, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val card = loaded.card
    var phone by remember { mutableStateOf<Labeled?>(null) }

    phone?.let { p ->
        AlertDialog(
            onDismissRequest = { phone = null },
            title = { Text(p.value) },
            text = { Text(p.label, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { phone = null; dial(ctx, p.value) }) { Text("Call") } },
            dismissButton = { TextButton(onClick = { phone = null; copyText(ctx, p.label, p.value) }) { Text("Copy") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardAvatar(loaded, Dimens.avatarRow)
                Text(card.displayName, modifier = Modifier.padding(start = Spacing.md), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val nameParts = listOfNotNull(card.givenName, card.familyName).joinToString(" ")
                if (nameParts.isNotBlank() && nameParts != card.displayName) DetailRow("Name", nameParts, onClick = null) { copyText(ctx, "Name", nameParts) }
                card.phones.forEach { p -> DetailRow(p.label, p.value, onClick = { phone = p }) { copyText(ctx, p.label, p.value) } }
                card.emails.forEach { e -> DetailRow(e.label, e.value, onClick = { mail(ctx, e.value) }) { copyText(ctx, e.label, e.value) } }
                card.org?.let { DetailRow("Company", it, onClick = null) { copyText(ctx, "Company", it) } }
                card.title?.let { DetailRow("Title", it, onClick = null) { copyText(ctx, "Title", it) } }
                card.addresses.forEach { a -> DetailRow(a.label, a.value, onClick = { openAddress(ctx, a.value) }) { copyText(ctx, a.label, a.value) } }
                card.urls.forEach { u ->
                    // Only an http(s) address opens; anything else is copy-only, drawn like the other plain rows.
                    val launch = websiteLaunchUrl(u.value)
                    DetailRow(u.label, u.value, onClick = launch?.let { l -> { openWebsite(ctx, l) } }) { copyText(ctx, u.label, u.value) }
                }
                card.birthday?.let {
                    val shown = contactBirthdayLabel(it)
                    DetailRow("Birthday", shown, onClick = null) { copyText(ctx, "Birthday", shown) }
                }
                card.note?.let { DetailRow("Note", it, onClick = null) { copyText(ctx, "Note", it) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)?, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick ?: onCopy, onLongClick = onCopy)
            .padding(vertical = Spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
