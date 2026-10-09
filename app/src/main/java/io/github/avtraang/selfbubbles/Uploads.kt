package io.github.avtraang.selfbubbles

// Attachment uploads, from the tap until the relay has answered. The rules (what a
// failure means, what is said) are plain functions in UploadReports.kt and
// SendRecovery.kt; this file is the Android side: the stored record, the coroutine
// that uploads, the toast and the notification.

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How one upload ended: [via] is the path it was delivered by, null when it was not. */
data class UploadResult(val chatGuid: String, val via: String?)

/**
 * One per process, like [Outbox]; every function is called on the main thread.
 *
 * An upload used to run in the conversation screen's own scope and to report
 * a failure with a toast only (audit A3-F5): leaving the chat ended it without
 * a word, and a failure with the screen off, or the process ending mid-upload,
 * left no trace anywhere. Now it runs here, outside any screen's lifetime; a
 * record is on the phone before the request starts and goes when the upload
 * ends; a failure nobody is looking at becomes a notification; and whatever
 * record is still there at the next start is reported then.
 *
 * Unlike a text, the file itself is not kept: it can be hundreds of megabytes
 * and belongs to another app. So there is no "Send again" here, only the truth
 * about what happened.
 */
object Uploads {
    private const val KEY_UPLOADS = "uploads"

    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    private var held: List<PendingUpload> = emptyList()
    private var lastId = 0L

    /** Not a screen's scope: an upload outlives the chat, the ViewModel and the activity it was started from. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val count = MutableStateFlow(0)

    /** How many uploads are in flight or waiting their turn, for the composer's spinner. */
    val inFlight: StateFlow<Int> = count.asStateFlow()

    private val ended = MutableSharedFlow<UploadResult>(extraBufferCapacity = 64)

    /** How each upload ended, for whoever shows the chats (ChatVM); nothing is replayed to a late listener. */
    val results: SharedFlow<UploadResult> = ended.asSharedFlow()

    /**
     * Loads the stored records; a no-op after the first call. Whatever is
     * stored was in flight when the last process ended: it is reported now,
     * once, and forgotten.
     */
    fun init(context: Context) {
        if (prefs != null) return
        val a = context.applicationContext
        // The outbox's file: app-private and excluded from backup and device transfer.
        val p = a.getSharedPreferences(OUTBOX_PREFS_FILE, Context.MODE_PRIVATE)
        app = a
        prefs = p
        // Read defensively: this runs at process start, where a throw would stop the app from opening.
        val left = uploadsDecode(runCatching { p.getString(KEY_UPLOADS, null) }.getOrNull())
        if (left.isEmpty()) return
        store(emptyList(), onDiskNow = false)
        runCatching {
            for (notice in interruptedUploadNotices(left)) {
                Notifs.showUnsent(a, notice.chatGuid, notice.chatTitle, notice.title, notice.text, attachment = true)
            }
        }.onFailure { Log.e("Imsg", "interrupted upload notice failed (${failureLabel(it)})") }
    }

    private fun store(next: List<PendingUpload>, onDiskNow: Boolean) {
        held = next
        val edit = prefs?.edit()?.putString(KEY_UPLOADS, uploadsEncode(next)) ?: return
        if (onDiskNow) edit.commit() else edit.apply()
    }

    /**
     * Uploads [uris] into [chatGuid] one after another. Their records are on
     * the phone before the first request starts. [afterAll] runs when the last
     * one has ended, also when nobody is left to wait for it.
     */
    fun send(context: Context, chatGuid: String, chatTitle: String, uris: List<Uri>, afterAll: suspend () -> Unit = {}) {
        init(context)
        val a = context.applicationContext
        val now = System.currentTimeMillis()
        val entries = uris.map {
            lastId = maxOf(now, lastId + 1, (held.maxOfOrNull { e -> e.id } ?: 0L) + 1)
            PendingUpload(lastId, chatGuid, chatTitle, now).also { e -> held = held + e }
        }
        store(held, onDiskNow = true)
        count.value += uris.size
        scope.launch {
            for ((entry, uri) in entries.zip(uris)) {
                val sent = runCatching { Api.sendAttachment(chatGuid, a.contentResolver, uri) }
                    // Class names only: the throwable's text can name the picked file (its content URI).
                    .onFailure { Log.e("Imsg", sendFailureLogLine("attachment send", it)) }
                store(held.filterNot { it.id == entry.id }, onDiskNow = false)
                count.value -= 1
                val via = sent.getOrNull()
                if (via == null) alert(a, entry, sent.exceptionOrNull())
                ended.tryEmit(UploadResult(chatGuid, via))
            }
            runCatching { afterAll() }
        }
    }

    /** A toast while the owner is looking at that chat, a notification otherwise ([attachmentAlert]). */
    private fun alert(a: Context, entry: PendingUpload, error: Throwable?) {
        runCatching {
            when (val alert = attachmentAlert(entry.chatGuid, entry.chatTitle, error, Notifs.foreground, AppLock.gated, Notifs.openChat)) {
                is UnsentAlert.Toast -> Toast.makeText(
                    a, alert.text,
                    if (alert.text == sendFailureMessage(SendFailure.OTHER)) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                is UnsentAlert.Notification ->
                    Notifs.showUnsent(a, entry.chatGuid, entry.chatTitle, alert.title, alert.text, attachment = true)
            }
        }.onFailure { Log.e("Imsg", "attachment alert failed (${failureLabel(it)})") }
    }
}
