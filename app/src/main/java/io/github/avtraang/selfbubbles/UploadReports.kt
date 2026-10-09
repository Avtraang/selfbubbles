package io.github.avtraang.selfbubbles

// What is said about an attachment that did not go out, or may not have. Plain
// functions (UploadReportsTest); Uploads.kt is the Android side. The words for
// one failed upload are attachmentFailureMessage() in SendRecovery.kt.

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * One upload that has started and whose end this process has not seen yet.
 * Written to the phone before the request starts and removed when it ends,
 * well or not: what is still there at the next start was in flight when the
 * process ended. It names the chat and nothing of the file.
 */
@Serializable
data class PendingUpload(
    val id: Long,
    val chatGuid: String,
    val chatTitle: String = "",
    val startedAtMillis: Long = 0L,
)

fun uploadsEncode(uploads: List<PendingUpload>): String = json.encodeToString(uploads)

/** The stored records; empty for nothing stored or something else. Each is read by itself. */
fun uploadsDecode(stored: String?): List<PendingUpload> =
    if (stored.isNullOrBlank()) emptyList()
    else runCatching {
        json.parseToJsonElement(stored).jsonArray.mapNotNull { runCatching { json.decodeFromJsonElement<PendingUpload>(it) }.getOrNull() }
    }.getOrDefault(emptyList()).filter { it.chatGuid.isNotBlank() }

/** What the notification for one chat says. No file name in it: it may show on the lock screen. */
data class UploadNotice(val chatGuid: String, val chatTitle: String, val title: String, val text: String)

const val ATTACHMENT_MAYBE_TITLE = "Attachment may not have been sent"
const val ATTACHMENT_NOT_SENT_TITLE = "Attachment not sent"

private fun interruptedLine(count: Int): String =
    if (count == 1) "An attachment may not have been sent — check the chat before sending it again"
    else "$count attachments may not have been sent — check the chat before sending them again"

/**
 * For the uploads the last process left in flight ([stored] as read at start):
 * one notice per chat. Nothing in this process is sending them, and the relay
 * may have had the whole file or none of it.
 */
fun interruptedUploadNotices(stored: List<PendingUpload>): List<UploadNotice> =
    stored.groupBy { it.chatGuid }.map { (guid, uploads) ->
        val name = uploads.last().chatTitle
        UploadNotice(guid, name, title = name.ifBlank { ATTACHMENT_MAYBE_TITLE }, text = interruptedLine(uploads.size))
    }

/**
 * How the owner is told about an upload into [chatGuid] that just failed with
 * [error]. A toast is enough only while he is looking at that very chat: it is
 * gone in seconds, and unlike a text an attachment leaves no row behind.
 * Everywhere else (another chat, another screen, the background, the lock
 * screen) it is a notification that opens the chat and stays until he has
 * seen it.
 */
fun attachmentAlert(
    chatGuid: String, chatTitle: String, error: Throwable?, appInFront: Boolean, locked: Boolean, openChat: String?,
): UnsentAlert {
    val line = attachmentFailureMessage(error)
    if (appInFront && !locked && openChat == chatGuid) return UnsentAlert.Toast(line)
    val certain = line == sendFailureMessage(SendFailure.OTHER) || line == sendFailureMessage(SendFailure.TOO_LARGE)
    return UnsentAlert.Notification(
        title = chatTitle.ifBlank { if (certain) ATTACHMENT_NOT_SENT_TITLE else ATTACHMENT_MAYBE_TITLE },
        text = line,
    )
}
