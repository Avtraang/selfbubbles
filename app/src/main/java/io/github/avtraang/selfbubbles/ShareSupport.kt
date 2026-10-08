package io.github.avtraang.selfbubbles

import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

/*
 * Receiving from Android's share sheet (ShareActivity.kt, ShareScreens.kt):
 * the decisions, as plain Kotlin so they run under JUnit without Android.
 *
 *  - checkShareIntent: accept or reject what another app handed us;
 *  - the caps: SHARE_MAX_ITEMS, and the tunnel's upload limit (exceedsTunnelLimit);
 *  - names: the per-share folder under cacheDir/shared, the share id, the copied
 *    files' names;
 *  - formatByteSize for the confirmation sheet;
 *  - canReceiveSharedFiles: which threads the picker offers for files.
 */

/** Intent actions, spelled out so this file needs nothing from android.*. */
const val SHARE_ACTION_SEND = "android.intent.action.SEND"
const val SHARE_ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

/** The most items one share may carry; anything above is refused whole. */
const val SHARE_MAX_ITEMS = 10

/** Share folders older than this are removed on the next share (Downloads prunes the same way). */
const val SHARE_MAX_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

/** Folder under cacheDir/shared that holds one subfolder per share (the FileProvider covers it). */
const val SHARE_IN_DIR = "share-in"

/** Intent extra MainActivity receives: the share id alone, never the content URIs. */
const val EXTRA_SHARE_ID = "share_id"

/** Progress toast threshold: copying that takes longer than this says so. */
const val SHARE_PROGRESS_TOAST_AFTER_MILLIS: Long = 1_000

/** How much longer to wait before the progress toast when the copy has already run for [elapsedMillis]: never more than [threshold]. */
fun shareProgressDelay(elapsedMillis: Long, threshold: Long = SHARE_PROGRESS_TOAST_AFTER_MILLIS): Long =
    (threshold - elapsedMillis).coerceIn(0, threshold)

/** The share's record on disk, in its folder beside the copied files. */
const val SHARE_MANIFEST_NAME = "share.json"

/**
 * A direct-share target's chat is looked up in the thread list once a load of it has
 * finished; this bounds that wait when the relay cannot be reached at all (the
 * picker then shows, as for a chat the list does not know).
 */
const val SHARE_TARGET_LIST_WAIT_MILLIS: Long = 15_000

/** The thread a direct-share target named, from the loaded list; null (the picker) when the list does not know it. */
fun resolveShareTarget(threads: List<Thread>, targetGuid: String?): Thread? =
    if (targetGuid.isNullOrBlank()) null else threads.firstOrNull { it.chat_guid == targetGuid }

/**
 * Names no copied file may take in a share folder: the manifest's. A stream whose
 * display name is "share.json" is otherwise copied and then overwritten by the
 * record of the share, which the confirmation sheet would show under the original
 * name and Send would upload. Seeded into the taken-name set before any file is named.
 */
fun shareReservedNames(): Set<String> = setOf(SHARE_MANIFEST_NAME)

/**
 * The seq MainActivity uses for the request it is created with: the one saved
 * before a recreation, else the next fresh one. [counter] is process-wide and
 * restarts at zero with the process, while the saved seq does not: without
 * raising the counter to the restored value, every delivery after a
 * process-death restore (a notification tap, a share) would get a seq the VM
 * has already handled and be dropped. Restoring returns the saved seq itself,
 * so the VM (which saves the seq it handled) does not act on it again.
 */
fun launchSeqOnCreate(counter: java.util.concurrent.atomic.AtomicLong, saved: Long): Long {
    if (saved <= 0) return counter.incrementAndGet()
    counter.updateAndGet { maxOf(it, saved) }
    return saved
}

/** Whether a request with [seq] is one the VM has not handled yet ([handled] is the last one it did). */
fun launchRequestIsNew(seq: Long, handled: Long): Boolean = seq > handled

/** Why a share intent was refused. */
enum class ShareReject { WRONG_ACTION, NOTHING, TOO_MANY, BAD_URI }

/** Outcome of [checkShareIntent]. */
sealed class ShareCheck {
    /** Worth handling: [text] came along, and [streams] content URIs are to be copied (0 for text only). */
    data class Accept(val text: Boolean, val streams: Int) : ShareCheck()
    data class Reject(val reason: ShareReject) : ShareCheck()
}

/**
 * Validates what the share sheet delivered before anything is read.
 *
 * - only ACTION_SEND (at most one stream) and ACTION_SEND_MULTIPLE (streams only) are
 *   accepted; [mime] is informational, the per-item type is read from the resolver;
 * - every stream must be a content:// URI: file:// and anything else is refused
 *   whole, since those bypass the temporary grant the sheet gives us;
 * - more than [SHARE_MAX_ITEMS] streams are refused whole;
 * - nothing usable (no stream and no text) is refused.
 *
 * [count] is the number of stream extras as delivered, [uriSchemes] their schemes
 * (null when a URI had none); they are given separately so a mismatch (a null
 * entry in the list) also counts as a bad URI.
 */
fun checkShareIntent(
    action: String?,
    @Suppress("UNUSED_PARAMETER") mime: String?,
    hasText: Boolean,
    uriSchemes: List<String?>,
    count: Int = uriSchemes.size,
    /** Authorities of the stream URIs, same order as [uriSchemes]; a stream on
     *  [ownAuthority] (this app's own FileProvider) is refused, so another app
     *  cannot bounce our cached attachments back into a share. */
    uriAuthorities: List<String?> = emptyList(),
    ownAuthority: String? = null,
): ShareCheck {
    if (ownAuthority != null && uriAuthorities.any { it.equals(ownAuthority, ignoreCase = true) }) {
        return ShareCheck.Reject(ShareReject.BAD_URI)
    }
    val multiple = when (action) {
        SHARE_ACTION_SEND -> false
        SHARE_ACTION_SEND_MULTIPLE -> true
        else -> return ShareCheck.Reject(ShareReject.WRONG_ACTION)
    }
    if (count > SHARE_MAX_ITEMS) return ShareCheck.Reject(ShareReject.TOO_MANY)
    if (!multiple && count > 1) return ShareCheck.Reject(ShareReject.TOO_MANY)
    if (count != uriSchemes.size) return ShareCheck.Reject(ShareReject.BAD_URI)
    if (uriSchemes.any { !it.equals("content", ignoreCase = true) }) return ShareCheck.Reject(ShareReject.BAD_URI)
    val text = hasText && !multiple
    if (count == 0 && !text) return ShareCheck.Reject(ShareReject.NOTHING)
    // [mime] decides nothing: text/plain with a stream is a text file (the stream
    // wins), and a text share arrives under whatever type the sender chose.
    return ShareCheck.Accept(text = text, streams = count)
}

/** True when a file of [sizeBytes] may be copied for sending: the tunnel's own limit. */
fun shareItemFits(sizeBytes: Long?): Boolean = !exceedsTunnelLimit(sizeBytes)

/** The limit, in bytes, that [shareItemFits] applies (for a copy loop that counts as it goes). */
val SHARE_MAX_ITEM_BYTES: Long = TUNNEL_MAX_BODY_BYTES

private val SHARE_ID = Regex("^[0-9a-f]{16}$")

/** A share id is 16 lowercase hex characters: [shareIdFrom] makes one, and anything else from an intent is refused. */
fun isValidShareId(id: String?): Boolean = id != null && SHARE_ID.matches(id)

/** A share id from 8 random bytes. */
fun shareIdFrom(randomBytes: ByteArray): String =
    randomBytes.take(8).joinToString("") { "%02x".format(it) }.padEnd(16, '0')

/** Path of a share's folder relative to cacheDir/shared: "share-in/<id>". Null for an invalid id. */
fun shareFolderPath(id: String?): String? = if (isValidShareId(id)) "$SHARE_IN_DIR/$id" else null

/** Whether a share folder last touched at [lastModified] is old enough to be removed. */
fun shareFolderIsStale(lastModified: Long, now: Long, maxAge: Long = SHARE_MAX_AGE_MILLIS): Boolean =
    now - lastModified > maxAge

/** Extension (with its dot, lowercase) off the end of a cleaned display name; null when there is none. */
fun extensionOf(name: String?): String? {
    val s = cleanFileName(name)
    val dot = s.lastIndexOf('.')
    if (dot <= 0 || dot == s.length - 1) return null
    val ext = s.substring(dot)
    // A "file.name with spaces" is not an extension; neither is anything long.
    if (ext.length > 8 || ext.any { it.isWhitespace() }) return null
    return ext.lowercase(Locale.ROOT)
}

/**
 * The copied file's name: the display name sanitised by the app's one name
 * cleaner, keeping its extension when it has one, else [extFromMime] (without a
 * dot, as MimeTypeMap gives it), else none. "shared-N" stands in for a blank name.
 */
fun shareFileName(displayName: String?, extFromMime: String?, index: Int): String {
    val ext = extensionOf(displayName) ?: extFromMime?.trim()?.trimStart('.')
        ?.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
    return sanitizeFileName(displayName, ext, "shared-$index")
}

/** [name] made distinct from [taken] by " (2)", " (3)", … before the extension. */
fun uniqueFileName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 2
    while (true) {
        val candidate = "$stem ($n)$ext"
        if (candidate !in taken) return candidate
        n++
    }
}

/** "12 B", "340 KB", "1.2 MB", "2.5 GB": what the confirmation sheet shows beside each item. */
fun formatByteSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        b < 1024 -> "$b B"
        b < mb -> "${(b / kb).toLong()} KB"
        b < gb -> String.format(Locale.ROOT, "%.1f MB", b / mb)
        else -> String.format(Locale.ROOT, "%.1f GB", b / gb)
    }
}

/**
 * Whether the picker offers [t] for a file share. The attachment path
 * (ChatVM.sendAttachments -> Api.sendAttachment) posts to the relay by chat_guid
 * for every network, Google Messages (bp:) chats included, and the composer's
 * Attach button is never hidden there, so every thread with a guid qualifies.
 * Kept as the one place to narrow it if the relay ever refuses a network.
 */
fun canReceiveSharedFiles(t: Thread): Boolean = t.chat_guid.isNotBlank()

/** One copied item of a share. */
data class ShareItem(val file: File, val mime: String, val name: String, val size: Long) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * A share waiting for a thread: the text and the copied files, in [dir] under cacheDir/shared.
 * [targetGuid] is the chat a direct-share target (a conversation shortcut) named, so the
 * picker can be skipped; null when the owner is to pick.
 */
data class PendingShare(
    val id: String, val dir: File, val text: String?, val items: List<ShareItem>,
    val targetGuid: String? = null,
) {
    val hasFiles: Boolean get() = items.isNotEmpty()
}

/** The share's record on disk (share.json in its folder), so a share survives until MainActivity reads it. */
@Serializable
data class ShareManifest(
    val text: String? = null,
    val items: List<ShareManifestItem> = emptyList(),
    /** The chat guid a direct-share target resolved to (ConversationShortcuts.kt), if any. */
    val target: String? = null,
)

@Serializable
data class ShareManifestItem(val file: String, val mime: String, val name: String, val size: Long)

/** Manifest -> [PendingShare]: entries whose file is missing, escapes the folder, or names the manifest itself are dropped. */
fun pendingShareFrom(id: String, dir: File, m: ShareManifest): PendingShare {
    val items = m.items.mapNotNull { e ->
        if (e.file.isEmpty() || e.file.contains('/') || e.file.contains('\\') || e.file == "..") return@mapNotNull null
        if (e.file in shareReservedNames()) return@mapNotNull null
        val f = File(dir, e.file)
        if (!f.isFile) return@mapNotNull null
        ShareItem(f, e.mime, e.name, e.size)
    }
    return PendingShare(id, dir, m.text?.takeIf { it.isNotBlank() }, items, m.target?.takeIf { it.isNotBlank() })
}

/** Text appended to the composer draft of [chatGuid] (a text share); [seq] makes each one distinct. */
data class DraftAppend(val chatGuid: String, val text: String, val seq: Int)

/** [text] joined onto a [draft]: on a new line when the draft has something, else alone. */
fun appendToDraft(draft: CharSequence, text: String): String =
    if (draft.isBlank()) text else if (draft.endsWith("\n")) "$draft$text" else "$draft\n$text"
