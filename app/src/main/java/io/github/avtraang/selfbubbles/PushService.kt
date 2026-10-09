package io.github.avtraang.selfbubbles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import okhttp3.Request
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CHANNEL_ID = "imsg-messages"
const val KEY_REPLY = "imsg_reply"
const val ACTION_REPLY = "io.github.avtraang.selfbubbles.REPLY"
const val ACTION_MARK_READ = "io.github.avtraang.selfbubbles.MARK_READ"
const val EXTRA_CHAT_GUID = "chat_guid"
const val EXTRA_ROWID = "rowid"

/** The channel for a text that was not sent, or may not have been (Notifs.showUnsent). */
private const val UNSENT_CHANNEL_ID = "imsg-unsent"
/** Keeps a chat's "not sent" notification apart from its conversation notification, which has the same id. */
private const val UNSENT_TAG = "unsent"
private const val UNSENT_FILE_TAG = "unsent-file"
/** The action of the intent behind a "not sent" notification; it only tells that intent from the conversation notification's. */
const val ACTION_OPEN_UNSENT = "io.github.avtraang.selfbubbles.OPEN_UNSENT"

/**
 * How long a reply typed in a notification is waited for inside its broadcast.
 * The system gives a receiver about 10 s (a foreground broadcast) before it
 * calls the app unresponsive, and kills it when it has nothing on screen, while
 * a send may take most of a minute on the Mac ([SEND_READ_TIMEOUT_SECONDS]). An
 * ordinary send answers well within this; a slow one goes on in the outbox
 * after the broadcast is finished ([inlineReplyWaitIsSafe]).
 */
const val INLINE_REPLY_WAIT_MILLIS = 8_000L

/** True when [waitMillis] leaves the receiver time to finish inside the system's limit for a foreground broadcast. */
fun inlineReplyWaitIsSafe(waitMillis: Long): Boolean = waitMillis in 1..9_000L

/**
 * Notifications use MessagingStyle — Android's messaging-specific API. Unlike a
 * plain notification it expands to the full text, shows the sender's photo,
 * stacks consecutive messages from a conversation, and supports inline reply
 * straight from the shade.
 */
object Notifs {

    private data class Line(
        val sender: String, val text: String, val time: Long,
        val img: Uri? = null, val mime: String = "image/jpeg",
        val guid: String? = null,
    )

    /**
     * Recent messages per chat, so the shade shows the thread, not just the last line.
     * A chat's lines are written by the push handler only; [clear] drops a chat from
     * another thread, also while the handler is fetching that chat's picture.
     */
    private val history = java.util.concurrent.ConcurrentHashMap<String, MutableList<Line>>()

    /** Set by MainActivity: a push for the chat that is on screen in the foreground
     *  is noise (the WebSocket already showed it), so it is not notified. */
    @Volatile var foreground = false
    @Volatile var openChat: String? = null

    fun notifId(chatGuid: String) = chatGuid.hashCode()

    /** Creates the channel and returns its id. If res/raw/imsg_receive exists,
     *  a v3 channel with that sound is used (channel sounds are frozen at
     *  creation, so the custom-sound variant needs its own id). */
    fun ensureChannel(ctx: Context): String {
        val soundRes = ctx.resources.getIdentifier("imsg_receive", "raw", ctx.packageName)
        val id = if (soundRes != 0) "imsg-messages-v3" else CHANNEL_ID
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(id) != null) return id
        mgr.createNotificationChannel(
            NotificationChannel(id, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming iMessages and texts"
                enableVibration(true)
                if (soundRes != 0) {
                    setSound(
                        Uri.parse("android.resource://${ctx.packageName}/$soundRes"),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            }
        )
        // Retire the older variants ("imsg-messages", "imsg-messages-v2") so Settings
        // doesn't show several "Messages" rows and existing installs pick up the new
        // sound (a channel's sound is frozen at creation). The active id is kept.
        for (old in listOf(CHANNEL_ID, "imsg-messages-v2", "imsg-messages-v3")) {
            if (old != id) runCatching { mgr.deleteNotificationChannel(old) }
        }
        return id
    }

    fun clear(ctx: Context, chatGuid: String) {
        history.remove(chatGuid)
        NotificationManagerCompat.from(ctx).cancel(notifId(chatGuid))
    }

    /**
     * A text for [chatGuid] was not sent, or may not have been, while nobody was
     * looking at the app (Outbox.alert; [title] and [text] from unsentAlert):
     * a notification of its own that opens the chat, where the text waits above
     * the composer with "Send again" and "Discard". No message text in it. One
     * per chat; a later failure there replaces it and alerts again.
     */
    fun showUnsent(ctx: Context, chatGuid: String, chatName: String, title: String, text: String, attachment: Boolean = false) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(UNSENT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(UNSENT_CHANNEL_ID, "Unsent messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A message of yours that was not sent, or may not have been"
                }
            )
        }
        val id = notifId(chatGuid)
        val open = Intent(ctx, MainActivity::class.java).apply {
            // An action of its own: without one this intent equals the conversation notification's
            // (show(): extras do not count), and the two would be ONE PendingIntent whenever their
            // request codes met, each opening the chat the other was last posted for. Ids are hash
            // codes, and those of two numbers that differ in their last digit are that far apart.
            // MainActivity reads the extras only.
            action = ACTION_OPEN_UNSENT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            if (chatName.isNotBlank()) putExtra(EXTRA_CHAT_NAME, chatName)
        }
        val openPending = PendingIntent.getActivity(
            ctx, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, UNSENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        // An attachment's notice has a tag of its own: it is not replaced by a text's, and a text
        // that is delivered later does not take it away (Outbox.clearNotificationWhenSettled).
        runCatching { NotificationManagerCompat.from(ctx).notify(if (attachment) UNSENT_FILE_TAG else UNSENT_TAG, id, n) }
    }

    /**
     * The chat was opened ([attachments] too: the owner is looking at it), or
     * none of its texts waits for the owner any more (texts only).
     */
    fun clearUnsent(ctx: Context, chatGuid: String, attachments: Boolean = true) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(UNSENT_TAG, notifId(chatGuid)) }
        if (attachments) runCatching { NotificationManagerCompat.from(ctx).cancel(UNSENT_FILE_TAG, notifId(chatGuid)) }
    }

    private fun person(ctx: Context, name: String): Person {
        val b = Person.Builder().setName(name.ifBlank { "Unknown" }).setKey(name)
        ContactPhotos.load(ctx)
        ContactPhotos.photoByName(name)?.let { uri: Uri ->
            runCatching { b.setIcon(IconCompat.createWithContentUri(uri)) }
        }
        return b.build()
    }

    /**
     * The shared client with a limit on the whole call: a picture for a
     * notification is fetched inside the push handler, where a slow but steady
     * download would otherwise hold up every notification behind it. Same
     * connection pool and the same token rule as [http].
     */
    private val notifImageHttp by lazy {
        http.newBuilder().callTimeout(NOTIF_IMAGE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Downloads a relay-served image so the notification can display it.
     *  The system UI reads it via FileProvider; the relay URL needs our auth
     *  token, which the shared client adds for relay-origin URLs only.
     *  Null when it is not there within [NOTIF_IMAGE_TIMEOUT_SECONDS] or is
     *  larger than [NOTIF_IMAGE_MAX_BYTES]: the notification stays without it. */
    private fun fetchImage(ctx: Context, url: String, givenUpOn: () -> Boolean): Uri? = runCatching {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 86_400_000) it.delete()
        }
        val f = File(dir, "notif-${System.currentTimeMillis()}.img")
        val ok = runCatching {
            notifImageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.isSuccessful && f.outputStream().use { o -> copyCapped(r.body!!.byteStream(), o, NOTIF_IMAGE_MAX_BYTES) }
            }
        }.getOrDefault(false)
        if (!ok || givenUpOn()) {
            f.delete()                      // nothing half-fetched, or fetched too late to be shown, stays in the cache
            return@runCatching null
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.grantUriPermission(
            "com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        uri
    }.getOrNull()

    fun show(
        ctx: Context,
        chatGuid: String,
        chatName: String,
        sender: String,
        text: String,
        rowid: Long,
        imageUrl: String? = null,
        imageMime: String = "image/jpeg",
        guid: String? = null,
        group: String? = null,
    ) {
        // The chat is open on screen: the WebSocket already showed this message.
        // Not while the app lock's screen is in front: the VM behind it still names
        // the chat as open, but the owner cannot see it (pushIsRedundant, AppLock.kt).
        if (pushIsRedundant(foreground, AppLock.gated, openChat, chatGuid, relaySocket.live)) return
        val lines = history.getOrPut(chatGuid) { mutableListOf() }
        // Same message pushed twice (relay replay, duplicate delivery): don't re-alert.
        if (!guid.isNullOrBlank() && lines.any { it.guid == guid }) return

        val channelId = ensureChannel(ctx)
        val id = notifId(chatGuid)
        val isGroup = pushIsGroup(group, chatName, sender)
        val title = pushChatTitle(isGroup, chatName, sender)

        // The line goes into the history before its picture is fetched: a second push for
        // the same message is still recognised, and the notification does not wait for it.
        val line = Line(sender.ifBlank { chatName }, text, System.currentTimeMillis(), null, imageMime, guid)
        lines.add(line)
        while (lines.size > 8) lines.removeAt(0)

        // Tapping the notification opens the app on that conversation.
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_GUID, chatGuid)
        }
        val openPending = PendingIntent.getActivity(
            ctx, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Inline reply. MUTABLE is required — the system writes the typed text
        // into the intent before firing it.
        val replyIntent = Intent(ctx, NotifActions::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            // The chat's name, for the notice about a reply that could not be sent.
            putExtra(EXTRA_CHAT_NAME, title)
            putExtra(EXTRA_ROWID, rowid)
        }
        val replyPending = PendingIntent.getBroadcast(
            ctx, id, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyPending,
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()

        val readIntent = Intent(ctx, NotifActions::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            putExtra(EXTRA_ROWID, rowid)
        }
        val readPending = PendingIntent.getBroadcast(
            ctx, id + 1, readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val readAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark as read", readPending,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // 2FA codes get a one-tap copy action, Google Messages style.
        val copyAction = Otp.find(text)?.let { code ->
            val copyIntent = Intent(ctx, CopyCodeActivity::class.java).apply {
                putExtra("code", code)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_save, "Copy $code",
                PendingIntent.getActivity(
                    ctx, id + 2, copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).setShowsUserInterface(false).build()
        }

        // The conversation shortcut for this chat (published now if it is not yet),
        // so the notification sits in the conversation space with its own settings.
        val shortcutId = ConversationShortcuts.ensureForNotification(
            ctx, chatGuid, title, isGroup,
        )

        // Puts the chat's notification up from [held]. [alert]: a new message alerts; the
        // same message getting its picture does not.
        fun post(alert: Boolean, held: List<Line>) {
            val me = Person.Builder().setName("You").setKey("me").build()
            val style = NotificationCompat.MessagingStyle(me)
            style.isGroupConversation = isGroup
            if (isGroup) style.conversationTitle = title
            for (l in held) {
                val m = NotificationCompat.MessagingStyle.Message(l.text, l.time, person(ctx, l.sender))
                if (l.img != null) m.setData(l.mime, l.img)
                style.addMessage(m)
            }
            val n = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setStyle(style)
                .setShortcutId(shortcutId)
                .setLocusId(LocusIdCompat(shortcutId))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .apply { copyAction?.let { addAction(it) } }
                .addAction(readAction)
                .addAction(replyAction)
                .setOnlyAlertOnce(!alert)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }

        // The text first; the picture when it is there (postThenDecorate, PushDelivery.kt).
        postThenDecorate(
            hasPicture = !imageUrl.isNullOrBlank(),
            post = { picture: Uri?, alert ->
                if (picture == null) {
                    // The message itself is always shown, from the lines this push started with,
                    // as it always was: also when the chat was cleared a moment ago.
                    post(alert, lines)
                } else {
                    val held = history[chatGuid]
                    val at = held?.indexOfFirst { it === line } ?: -1
                    if (held != null && at >= 0) {
                        held[at] = line.copy(img = picture)
                        post(alert, held)
                    }
                }
            },
            // On a thread of its own, with a limit on the whole wait: the call's own limit does
            // not cover a name lookup that stalls (withinDeadline, PushDelivery.kt).
            fetch = {
                withinDeadline((NOTIF_IMAGE_TIMEOUT_SECONDS + 1) * 1000) { givenUpOn -> fetchImage(ctx, BASE + imageUrl, givenUpOn) }
            },
            // Read, opened or swiped away while the picture was on its way: it is not put back.
            stillShown = { history[chatGuid]?.any { it === line } == true && isShown(ctx, id) },
        )
    }

    /**
     * Whether the notification [id] is in the shade now. One that was posted a
     * moment ago may not be listed yet (a picture on a fast link is there within
     * milliseconds), so a notification that is not listed is asked about a few
     * more times before it counts as gone. Where the system does not say, it is
     * taken to be shown. Called on the push handler's thread only.
     */
    private fun isShown(ctx: Context, id: Int): Boolean {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        repeat(NOTIF_LISTED_CHECKS) { attempt ->
            val listed = runCatching { mgr.activeNotifications.any { it.id == id && it.tag == null } }.getOrElse { return true }
            if (listed) return true
            if (attempt < NOTIF_LISTED_CHECKS - 1) runCatching { java.lang.Thread.sleep(NOTIF_LISTED_PAUSE_MILLIS) }   // the app has a Thread of its own: a conversation
        }
        return false
    }
}

/** Handles Reply, Mark-as-read, and FaceTime-decline taps from the shade. */
class NotifActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_FT_DECLINE) {
            val uuid = intent.getStringExtra(EXTRA_FT_UUID) ?: return
            val ftPending = goAsync()
            FaceTimeNotifs.cancel(ctx, uuid)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { Api.ftDecline(uuid) }
                ftPending.finish()
            }
            return
        }
        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID) ?: return
        val rowid = intent.getLongExtra(EXTRA_ROWID, 0L)
        val pending = goAsync()

        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (reply.isNullOrBlank()) {
                    pending.finish()
                    return
                }
                // The reply goes through the outbox like a text typed in the app: it is kept
                // on the phone before anything is tried, and when it fails (or ends without
                // an answer) the outbox says so with a notification that opens the chat,
                // where the text waits with "Send again". It used to vanish with its
                // notification. The broadcast itself is held only for a bounded wait
                // (INLINE_REPLY_WAIT_MILLIS): a send the Mac is slow over goes on after it.
                val app = ctx.applicationContext
                val sending = Outbox.send(app, chatGuid, intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty(), reply, null)
                CoroutineScope(Dispatchers.IO).launch {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val read = launch { runCatching { Api.read(chatGuid, rowid) } }
                    withTimeoutOrNull(INLINE_REPLY_WAIT_MILLIS) { sending.join() }
                    // Sent, failed or still on its way: the conversation notification goes (its
                    // reply field would spin for ever otherwise). A failure has its own notification.
                    Notifs.clear(app, chatGuid)
                    // The read receipt gets what is left of the same wait, and goes on after it too.
                    val left = INLINE_REPLY_WAIT_MILLIS - (android.os.SystemClock.elapsedRealtime() - started)
                    withTimeoutOrNull(left) { read.join() }
                    pending.finish()
                }
            }
            ACTION_MARK_READ -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { Api.read(chatGuid, rowid) }
                Notifs.clear(ctx, chatGuid)
                pending.finish()
            }
            else -> pending.finish()
        }
    }
}

/**
 * Which handler a push's `kind` goes to, with the FaceTime switch (Features.kt)
 * applied: while that feature is off a FaceTime ring is dropped outright — no
 * notification — but a call's `ended` event still cancels the local ring it
 * may have posted before the switch was flipped ([Route.FACETIME_CANCEL]: the
 * notification is removed, nothing else runs). Pure, so PushGateTest pins the table.
 */
object PushGate {
    enum class Route { MESSAGE, FACETIME, FACETIME_CANCEL, DROPPED }

    private const val FT_ENDED = "ended"

    fun route(kind: String?, ftEvent: String?, faceTimeOn: Boolean): Route = when {
        kind != "facetime" -> Route.MESSAGE
        faceTimeOn -> Route.FACETIME
        ftEvent == FT_ENDED -> Route.FACETIME_CANCEL
        else -> Route.DROPPED
    }
}

/**
 * Whether this build carries Firebase push at all. Firebase initialises itself
 * at process start from resources that only the google-services plugin
 * generates, and app/build.gradle.kts applies that plugin only when the
 * untracked app/google-services.json exists (BuildConfig.PUSH_BUILT_IN records
 * that). A build made without the file has no FirebaseApp,
 * FirebaseMessaging.getInstance() throws, and nothing ever starts PushService
 * — so the app lives on its WebSocket while it is open.
 */
object Push {
    private const val TAG = "Push"

    /** True when the google-services plugin ran for this build, i.e. google-services.json was present at build time. */
    val builtIn: Boolean get() = BuildConfig.PUSH_BUILT_IN

    /** True when Firebase initialised in this process (FirebaseInitProvider, from the resources the plugin generated). */
    fun available(ctx: Context): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    /**
     * The line under Settings > Push notifications; a plain choice so it is a unit
     * test (PushNoteTest). [builtIn] is the build-time fact, [initialised] the
     * runtime one: a build without the file says so, and a build with the file
     * whose Firebase still did not start says that rather than blaming the file.
     */
    fun settingsNote(builtIn: Boolean, initialised: Boolean): String = when {
        !builtIn -> "Push: not built in — this build has no google-services.json, so new-message alerts arrive only while the app is open (WebSocket). Add the file and rebuild to enable push."
        initialised -> "Push notifications: Firebase (this build includes google-services.json)."
        else -> "Push: not active — this build includes google-services.json, but Firebase did not initialise, so new-message alerts arrive only while the app is open (WebSocket). Check that the file is for this app's package name and rebuild."
    }

    /** Registers this device's FCM token with the relay; a no-op (one log line, nothing from the token) when Firebase is not running. */
    fun registerWithRelay(ctx: Context, scope: CoroutineScope) {
        if (!available(ctx)) {
            if (builtIn) Log.w(TAG, "built with google-services.json but Firebase did not initialise; push is off, relying on the WebSocket")
            else Log.d(TAG, "not built in (no google-services.json); relying on the WebSocket")
            return
        }
        val messaging = runCatching { FirebaseMessaging.getInstance() }
            .onFailure { Log.w(TAG, "Firebase initialised but FirebaseMessaging is unavailable; push is off", it) }
            .getOrNull() ?: return
        messaging.token
            .addOnSuccessListener { tok -> scope.launch { handToRelay(tok) } }
            // The class names only: a Firebase failure can quote the project it was asked about.
            .addOnFailureListener { Log.w(TAG, "no push token from Firebase (${failureLabel(it)}); push stays as it was") }
    }

    /**
     * One attempt to register [token] with the relay. A refusal or a failure
     * is logged (pushRegistrationLogLine: a status or class names, never the
     * token); the next connection to the relay tries again (ChatVM.socketOpened).
     */
    suspend fun handToRelay(token: String) {
        pushRegistrationLogLine(runCatching { Api.registerPush(token) })?.let { Log.w(TAG, it) }
    }
}

class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch { Push.handToRelay(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        // FaceTime ring/cancel — distinct shape, no chat_guid. Dropped while the
        // FaceTime feature is off (PushGate), except that an ended call still
        // takes down a ring posted before the switch was flipped.
        when (PushGate.route(d["kind"], d["ft_event"], Features.faceTime)) {
            PushGate.Route.DROPPED -> return
            PushGate.Route.FACETIME -> { FaceTimeNotifs.handle(this, d); return }
            PushGate.Route.FACETIME_CANCEL -> {
                d["uuid"]?.takeIf { it.isNotBlank() }?.let { FaceTimeNotifs.cancel(this, it) }
                return
            }
            PushGate.Route.MESSAGE -> {}
        }
        val chatGuid = d["chat_guid"]?.takeIf { it.isNotBlank() } ?: return
        Notifs.show(
            ctx = this,
            chatGuid = chatGuid,
            chatName = d["chat_name"].orEmpty(),
            sender = d["sender"].orEmpty(),
            text = d["text"].orEmpty().ifBlank { "Attachment" },
            rowid = d["rowid"]?.toLongOrNull() ?: 0L,
            imageUrl = d["image_url"],
            imageMime = d["image_mime"] ?: "image/jpeg",
            guid = d["guid"],
            group = d["is_group"],
        )
    }
}

/**
 * Whether a push is for a group. The relay's own word ("1" / "0" in is_group)
 * decides when it sends one; a relay from before that field is judged by the
 * old rule, a chat name that differs from the sender (pure; PushGroupTest).
 */
internal fun pushIsGroup(isGroupField: String?, chatName: String, sender: String): Boolean = when (isGroupField) {
    "1" -> true
    "0" -> false
    else -> chatName.isNotBlank() && chatName != sender
}

/** The name a notification, its reply notice and its shortcut carry. A group is never named after the sender. */
internal fun pushChatTitle(isGroup: Boolean, chatName: String, sender: String): String =
    if (isGroup) chatName.ifBlank { "Group chat" } else chatName.ifBlank { sender }

