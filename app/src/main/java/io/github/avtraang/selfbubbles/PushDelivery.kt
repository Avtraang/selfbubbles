package io.github.avtraang.selfbubbles

// Notifications on a link that is slow or down: the rules for putting one up before its picture is
// there, for how much of a picture is fetched, and for registering this phone with the relay.
// Plain functions (PushDeliveryTest); the Android side is in PushService.kt and ChatVM.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** How long the picture of a notification may take to fetch, the whole call. The text is already up by then. */
const val NOTIF_IMAGE_TIMEOUT_SECONDS = 5L

/** The largest picture a notification fetches; anything larger is left for the chat itself. */
const val NOTIF_IMAGE_MAX_BYTES = 10L * 1024 * 1024

/** How often, and how far apart, a notification that was just posted is looked for before it counts as dismissed. */
const val NOTIF_LISTED_CHECKS = 4
const val NOTIF_LISTED_PAUSE_MILLIS = 100L

/**
 * A notification goes up at once, and its picture is added when it has been
 * fetched. [post] puts the notification up: first without a picture and
 * alerting, then, if [fetch] brought one, once more with it and without a
 * second alert. A picture that cannot be fetched (null, or a failure: too
 * slow, too large, refused) leaves the text notification as it is. One that
 * arrives after the notification was read, opened or dismissed ([stillShown])
 * is dropped: putting it up would bring the notification back.
 *
 * The picture used to be fetched first, inside the push handler, with no limit
 * on time or size: on a weak link a photo delayed its own notification, and
 * every notification waiting behind it.
 */
fun <P> postThenDecorate(
    hasPicture: Boolean,
    post: (picture: P?, alert: Boolean) -> Unit,
    fetch: () -> P?,
    stillShown: () -> Boolean,
) {
    post(null, true)
    if (!hasPicture) return
    val picture = runCatching(fetch).getOrNull() ?: return
    if (!stillShown()) return
    post(picture, false)
}

/**
 * Runs [work] on a thread of its own and waits for it for at most [millis].
 * Null when it is not done by then, or when it failed. Work that was given up
 * on is left to finish; it is told so through its argument, so that it can
 * throw away what it made.
 *
 * A limit on the HTTP call alone is not a limit on the wait: a name lookup
 * that stalls keeps the calling thread until the system gives up, and the
 * calling thread here is the push handler, with every other notification
 * waiting behind it.
 */
fun <T> withinDeadline(millis: Long, work: (givenUpOn: () -> Boolean) -> T?): T? {
    val gaveUp = AtomicBoolean(false)
    val task = FutureTask<T?> { work { gaveUp.get() } }
    // java.lang.Thread by its full name: the app has a Thread of its own, a conversation.
    java.lang.Thread(task, "notification-picture").apply { isDaemon = true }.start()
    return try {
        task.get(millis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        gaveUp.set(true)
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Copies [from] to [to] as long as the total stays within [cap] bytes. False
 * when the source holds more: the copy stops there, and what was written is
 * for the caller to throw away.
 */
fun copyCapped(from: InputStream, to: OutputStream, cap: Long): Boolean {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = from.read(buffer)
        if (n < 0) return true
        if (total + n > cap) return false
        to.write(buffer, 0, n)
        total += n
    }
}

/**
 * What answered a registration of the push token: the HTTP status, and whether
 * the body was the relay's own answer ([isRelayOk]). The shared client follows
 * redirects, so a sign-in page in front of the relay arrives as a 200 that the
 * relay never saw.
 */
data class PushRegistrationAnswer(val code: Int, val fromRelay: Boolean)

/** Whether [body] is the relay's answer to a registration, `{"ok": true, ...}`, and not a page something else served at the address. */
fun isRelayOk(body: String): Boolean = runCatching {
    val ok = Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive
    // The JSON value true itself: the text "true" in quotes reads as a boolean to the library, and is not one.
    ok != null && !ok.isString && ok.booleanOrNull == true
}.getOrDefault(false)

/**
 * The log line for an attempt to register this phone's push token with the
 * relay, or null when the relay accepted it. [attempt] is what answered, or
 * the failure that kept anything from answering. The line holds the status or
 * the class names of the failure ([failureLabel]) and nothing else: never the
 * token, the relay's address or a piece of a reply. An attempt that was
 * cancelled is not a failure and has no line.
 */
fun pushRegistrationLogLine(attempt: Result<PushRegistrationAnswer>): String? = attempt.fold(
    onSuccess = { answer ->
        when {
            answer.code !in 200..299 -> "push registration refused by the relay (HTTP ${answer.code})"
            !answer.fromRelay -> "push registration answered by something that is not the relay (HTTP ${answer.code})"
            else -> null
        }
    },
    // An attempt that was called off (the screen that started it is gone) says nothing about the relay.
    onFailure = { if (it is kotlinx.coroutines.CancellationException) null else "push registration failed (${failureLabel(it)})" },
)
