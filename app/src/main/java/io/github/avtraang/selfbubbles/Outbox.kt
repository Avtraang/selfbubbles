package io.github.avtraang.selfbubbles

// The outbox: every text the owner sends, from the tap on Send until the relay has it.
// It is what makes a text impossible to lose without a trace: the text is written to
// the phone before the request starts, the request runs outside any screen's lifetime,
// and the text is removed only when the relay answers "delivered" or the owner
// discards it. The rules (what a failure means, what is shown) are plain functions in
// SendRecovery.kt; this file is the Android side: the stored copy, the coroutine that
// sends, the toast and the notification.

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One per process. ImsgApp loads it before any activity, service or receiver
 * runs. Every function here is called on the main thread (ChatVM, a Compose
 * click, NotifActions.onReceive), and the sends it starts report back on it.
 *
 * Stored in SharedPreferences [OUTBOX_PREFS_FILE] (app-private, excluded from backup
 * and device transfer like the other two files): the texts not yet delivered, each
 * with its chat's identifier and title. A delivered text is removed at once, so
 * in ordinary use the file holds a text for the second or two its send takes.
 *
 * A text never leaves the outbox by being matched against the chat's bubbles:
 * a send that ended without an answer may or may not have gone out, an older or
 * later message can carry the same words, and only the owner looking at the
 * chat can tell. "Send again" and "Discard" are his.
 */
object Outbox {
    private const val KEY_TEXTS = "texts"

    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    /** What the file holds, so an unchanged outbox is not written again. */
    private var stored: String = ""
    private var lastId = 0L

    /** Not a screen's scope: a send outlives the chat, the ViewModel and the activity it was started from. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val held = MutableStateFlow<List<UnsentText>>(emptyList())

    /** Every text not yet delivered, oldest first: in flight, failed, or unconfirmed. */
    val texts: StateFlow<List<UnsentText>> = held.asStateFlow()

    private val ended = MutableSharedFlow<TextSendResult>(extraBufferCapacity = 64)

    /** How each send ended, for whoever shows the chats (ChatVM); nothing is replayed to a late listener. */
    val results: SharedFlow<TextSendResult> = ended.asSharedFlow()

    /**
     * Loads the stored outbox; a no-op after the first call. A text the last
     * process left in flight is unconfirmed now ([outboxAfterRestart]): nothing
     * in this process is sending it.
     */
    fun init(context: Context) {
        if (prefs != null) return
        val a = context.applicationContext
        val p = a.getSharedPreferences(OUTBOX_PREFS_FILE, Context.MODE_PRIVATE)
        app = a
        prefs = p
        // Read defensively: this runs at process start, where a throw would stop the app from opening.
        val before = runCatching { p.getString(KEY_TEXTS, null) }.getOrNull()
        stored = before ?: outboxEncode(emptyList())
        change { outboxAfterRestart(outboxDecode(before)) }
    }

    /**
     * [onDiskNow]: the file is written before this returns (commit), which is
     * what a text needs before a request for it starts (a new one, or "Send
     * again"). Every other change is queued (apply) and written a moment
     * later, and before the activity or the broadcast it happened in is
     * reported stopped or finished.
     */
    private fun change(onDiskNow: Boolean = false, f: (List<UnsentText>) -> List<UnsentText>) {
        val next = f(held.value)
        held.value = next
        val encoded = outboxEncode(next)
        if (encoded != stored) {
            stored = encoded
            val edit = prefs?.edit()?.putString(KEY_TEXTS, encoded) ?: return
            if (onDiskNow) edit.commit() else edit.apply()
        }
    }

    /**
     * Sends [text] into [chatGuid]. It is in the outbox, and in the file,
     * before its request starts; the returned job ends when the send has, well
     * or not. [chatTitle] is the chat's name for a toast or notification about
     * it, [replyToGuid] the message it replies to.
     */
    fun send(context: Context, chatGuid: String, chatTitle: String, text: String, replyToGuid: String?): Job {
        init(context)
        val now = System.currentTimeMillis()
        lastId = nextOutboxId(held.value, lastId, now)
        val entry = UnsentText(lastId, chatGuid, chatTitle, text, replyToGuid, createdAtMillis = now)
        // Written before the request starts: a process that dies right after the tap still finds the text.
        change(onDiskNow = true) { outboxAdd(it, entry) }
        return deliver(entry.id)
    }

    /** "Send again" on the text [id]; nothing happens while it is already in flight. */
    fun sendAgain(context: Context, id: Long) {
        init(context)
        val entry = held.value.firstOrNull { it.id == id } ?: return
        if (entry.sending) return
        // Written first, like a new text: the file must not still call it "not sent" once this send is out.
        change(onDiskNow = true) { outboxRetry(it, id) }
        deliver(id)
    }

    /** "Discard" on the text [id]; a text in flight stays. */
    fun discard(context: Context, id: Long) {
        init(context)
        val entry = held.value.firstOrNull { it.id == id } ?: return
        change { outboxDiscard(it, id) }
        clearNotificationWhenSettled(entry.chatGuid)
    }

    private fun deliver(id: Long): Job = scope.launch {
        val entry = held.value.firstOrNull { it.id == id } ?: return@launch
        val startedAt = System.currentTimeMillis()
        // An ordinary send answers before this fires; one that waits on the Mac gets a "Sending…" row.
        val slow = launch { delay(OUTBOX_SLOW_MILLIS); change { outboxMarkSlow(it, id) } }
        val sent = runCatching { Api.send(entry.chatGuid, entry.text, entry.replyToGuid) }
        slow.cancel()
        val via = sent.getOrNull()
        val why = if (via != null) null else unsentWhyFor(sent.exceptionOrNull())
        change { outboxSettle(it, id, why) }
        if (why == null) {
            clearNotificationWhenSettled(entry.chatGuid)
        } else {
            // Class names and the status only: no text, no chat identifier, no host.
            sent.exceptionOrNull()?.let { Log.e("Imsg", sendFailureLogLine("text send", it)) }
            alert(entry, why)
        }
        ended.tryEmit(TextSendResult(entry.chatGuid, entry.text, via, why, startedAt))
    }

    /**
     * Tells the owner now: a toast while he is looking at the app, a notification
     * otherwise ([unsentAlert]). The text is already kept and shown by then, so a
     * system service that refuses (it should not) costs the alert and nothing else.
     */
    private fun alert(entry: UnsentText, why: UnsentWhy) {
        val a = app ?: return
        runCatching {
            when (val alert = unsentAlert(entry, why, Notifs.foreground, AppLock.gated, Notifs.openChat)) {
                is UnsentAlert.Toast -> Toast.makeText(a, alert.text, Toast.LENGTH_LONG).show()
                is UnsentAlert.Notification -> Notifs.showUnsent(a, entry.chatGuid, entry.chatTitle, alert.title, alert.text)
            }
        }.onFailure { Log.e("Imsg", "unsent alert failed (${failureLabel(it)})") }
    }

    /** The "not sent" notification of a chat goes once none of its texts is waiting for the owner any more. */
    private fun clearNotificationWhenSettled(chatGuid: String) {
        val a = app ?: return
        if (held.value.none { it.chatGuid == chatGuid && !it.sending }) Notifs.clearUnsent(a, chatGuid)
    }
}
