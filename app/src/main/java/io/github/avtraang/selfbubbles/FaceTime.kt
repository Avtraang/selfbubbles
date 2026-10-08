package io.github.avtraang.selfbubbles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

const val ACTION_FT_DECLINE = "io.github.avtraang.selfbubbles.FT_DECLINE"
const val EXTRA_FT_UUID = "ft_uuid"
const val EXTRA_FT_CALLER = "ft_caller"

/**
 * Incoming FaceTime calls, relayed from BlueBubbles through the relay.
 * The notification rings like a call (ringtone-sound channel, CATEGORY_CALL);
 * Answer opens [FaceTimeAnswerActivity], which asks the relay to answer on the
 * Mac and then hands the returned facetime.apple.com link to the browser. That
 * puts the phone in the call's web lobby, where it waits until a participant
 * admits it. The Mac does that by itself (and then drops out of the call) only
 * when the relay's optional, display-specific auto-admit rig is enabled on it;
 * otherwise someone at the Mac has to admit the phone (docs/facetime-bridge.md
 * in the relay repository).
 */
object FaceTimeNotifs {

    private const val CHANNEL_ID = "imsg-facetime"

    private fun notifId(uuid: String) = uuid.hashCode()

    private fun ensureChannel(ctx: Context): String {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return CHANNEL_ID
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FaceTime", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming FaceTime calls"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        )
        return CHANNEL_ID
    }

    /** Entry point from PushService for kind=facetime data messages. */
    fun handle(ctx: Context, d: Map<String, String>) {
        val uuid = d["uuid"]?.takeIf { it.isNotBlank() } ?: return
        when (d["ft_event"]) {
            "incoming" -> show(ctx, uuid,
                caller = d["caller_name"].orEmpty().ifBlank { d["caller"].orEmpty() },
                video = d["is_video"] != "0")
            "ended" -> cancel(ctx, uuid)
        }
    }

    private fun show(ctx: Context, uuid: String, caller: String, video: Boolean) {
        val channelId = ensureChannel(ctx)
        val id = notifId(uuid)

        val answer = Intent(ctx, FaceTimeAnswerActivity::class.java).apply {
            putExtra(EXTRA_FT_UUID, uuid)
            putExtra(EXTRA_FT_CALLER, caller)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val answerPending = PendingIntent.getActivity(
            ctx, id, answer,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val decline = Intent(ctx, NotifActions::class.java).apply {
            action = ACTION_FT_DECLINE
            putExtra(EXTRA_FT_UUID, uuid)
        }
        val declinePending = PendingIntent.getBroadcast(
            ctx, id + 1, decline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val kind = if (video) "FaceTime Video" else "FaceTime Audio"
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(caller.ifBlank { "Unknown" })
            .setContentText("Incoming $kind")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(answerPending)
            .addAction(android.R.drawable.sym_action_call, "Answer", answerPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePending)
            .setTimeoutAfter(60_000)  // stop ringing if nobody reacts
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun cancel(ctx: Context, uuid: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(uuid))
    }

    /** Takes down every ring on this channel: the FaceTime switch was just turned off. */
    fun cancelAll(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            mgr.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { mgr.cancel(it.id) }
        }
    }
}

/**
 * Shown while the Mac answers the call and generates the web link — that
 * round-trip legitimately takes 5-40 seconds (BlueBubbles waits for the call
 * to connect, sleeps through a Sonoma-crash window, then mints the link), so
 * the user needs visible progress. On success the link opens in the browser
 * and this activity finishes. The browser lands in the call's web lobby:
 * nothing here admits the phone, and the Mac does so by itself only with the
 * relay's optional auto-admit rig enabled (see [FaceTimeNotifs]).
 */
class FaceTimeAnswerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uuid = intent.getStringExtra(EXTRA_FT_UUID)
        // FaceTime turned off in Settings (Features.kt): nothing to answer, even
        // from a stale notification — which is taken down, since the relay's own
        // cancel for it may never arrive. Before the lock-screen flags and any request.
        if (!Features.faceTime) {
            uuid?.let { FaceTimeNotifs.cancel(this, it) }
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        if (uuid == null) { finish(); return }
        val caller = intent.getStringExtra(EXTRA_FT_CALLER).orEmpty()
        FaceTimeNotifs.cancel(this, uuid)

        var status by mutableStateOf("Answering on the Mac…")
        var failed by mutableStateOf(false)

        setContent {
            // The answer screen draws its own dark background, so it always takes the dark palette.
            MessagesTheme(darkTheme = true) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(caller.ifBlank { "FaceTime" }, color = MaterialTheme.colorScheme.onSurface,
                         style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(Spacing.md))
                    if (!failed) CircularProgressIndicator(color = MessagesTheme.colors.bubbleSms)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(Spacing.md))
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                         style = MaterialTheme.typography.bodyMedium)
                    if (failed) {
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(Spacing.sm))
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            status = "Getting your join link…"
            val link = runCatching { Api.ftAnswer(uuid) }.getOrNull()
            if (link.isNullOrBlank()) {
                status = "Couldn't answer — the call may have ended, or link " +
                         "generation failed on the Mac. Try again from the Mac."
                failed = true
                return@launch
            }
            status = "Joining…"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        }
    }
}
