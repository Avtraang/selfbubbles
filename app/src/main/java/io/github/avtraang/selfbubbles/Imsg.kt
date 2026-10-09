package io.github.avtraang.selfbubbles

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

// Secrets and relay addresses come from RelayConfigStore (RelayConfig.kt): the
// values baked in from the untracked secrets.properties (each BuildConfig field
// is "" when its key was absent at build time), overridden per field by what
// the owner saves in Settings. The names below are getters over the live
// snapshot so the call sites read as they always did. A request must not mix
// them: take one `RelayConfigStore.current` and use it for both the origin
// check and the headers (the interceptors below do exactly that).
//
// Must match IMSG_TOKEN on the relay. Every relay request carries it — the relay is
// reachable from the internet through the tunnel, so this is the only thing
// standing between a stranger and the entire message history.
val TOKEN: String get() = RelayConfigStore.current.token

// Cloudflare Access service-token creds — the outer lock on the tunnel. CF
// validates these at the tunnel hostname (RELAY_REMOTE_BASE) and returns 403
// before anything reaches the relay. Attached only to requests for the exact
// relay origin (see isRelayUrl / relayAuthInterceptor below), and only when
// both are present.
val CF_ACCESS_CLIENT_ID: String get() = RelayConfigStore.current.cfClientId
val CF_ACCESS_CLIENT_SECRET: String get() = RelayConfigStore.current.cfClientSecret

// Tunnel only. The app always talks to the relay through the HTTPS tunnel —
// there is no LAN path and no startup probe: a private address reached over
// cleartext would hand the relay token and both Cloudflare Access credentials
// to whatever answers at that address on another network. app/build.gradle.kts
// refuses to configure unless the baked-in values are https:// and wss:// on the
// same host, and RelayConfigValidator refuses to save anything else.
/** Relay base URL (https, no trailing slash); "" when no relay is configured. */
val BASE: String get() = RelayConfigStore.current.base
/** Relay WebSocket URL. Carries no query string: auth rides in headers. */
val WS_URL: String get() = RelayConfigStore.current.wsUrl

/**
 * The one origin that may ever receive the relay token and the Cloudflare
 * Access pair: https + exact host + exact port. Compared on the *parsed* URL
 * (never a string prefix), so look-alike hosts, userinfo tricks, other ports
 * and plain http all fail. HttpUrl has already lower-cased and punycoded the
 * host, which makes the comparison case-insensitive and IDN-safe.
 */
class RelayOrigin(host: String, val port: Int) {
    val host: String = host.lowercase()

    fun matches(url: okhttp3.HttpUrl): Boolean =
        url.scheme == "https" && url.host.equals(host, ignoreCase = true) && url.port == port

    /** False for anything that does not parse as an http(s) URL. */
    fun matches(url: String): Boolean = url.toHttpUrlOrNull()?.let(::matches) ?: false

    companion object {
        /** Parses the relay base URL once; anything but https is refused. */
        fun parse(base: String): RelayOrigin {
            val u = base.toHttpUrlOrNull()
            require(u != null && u.scheme == "https") { "relay base URL must be https://" }
            return RelayOrigin(u.host, u.port)
        }
    }
}

// Each of these takes one snapshot of the live config; with no relay configured
// they are all false.

/** True only for https URLs on the relay's exact host and port. */
fun isRelayUrl(url: okhttp3.HttpUrl): Boolean = RelayConfigStore.current.isRelayUrl(url)

/** String overload; false for unparseable input. */
fun isRelayUrl(url: String): Boolean = RelayConfigStore.current.isRelayUrl(url)

/** True for a relay URL whose path starts with [pathPrefix] (e.g. "/attachment/"). */
fun isRelayPath(url: okhttp3.HttpUrl, pathPrefix: String): Boolean =
    RelayConfigStore.current.isRelayPath(url, pathPrefix)

// coerceInputValues + isLenient so a single unexpected field shape (e.g. a
// preview sent as an object rather than a string) degrades to the default
// instead of failing the whole response parse and blanking the thread list.
val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

private val RELAY_CREDENTIAL_HEADERS =
    listOf("X-Imsg-Token", "CF-Access-Client-Id", "CF-Access-Client-Secret")

/**
 * Relay paths that send a message. The app only ever POSTs to them. A GET for
 * one can only have come from content (an image address inside a received link
 * preview, a page in the map view), so it never gets the credentials.
 */
internal val RELAY_ACTING_PATHS = listOf("/v/", "/assistant/")

/** The relay paths an image, a thumbnail or an icon is loaded from. */
internal val RELAY_MEDIA_PATHS =
    listOf("/attachment/", "/thumbnail/", "/link_image/", "/link_preview_image/", "/chat_icon/", "/bp_asset")

/** Whether [cfg]'s credentials go on this request: the relay origin, and not a GET for a path that sends (pure; RelayOriginTest). */
internal fun attachesCredentials(cfg: RelayConfig, method: String, url: okhttp3.HttpUrl): Boolean =
    cfg.isRelayUrl(url) &&
        !(method.equals("GET", ignoreCase = true) && RELAY_ACTING_PATHS.any { cfg.isRelayPath(url, it) })

/**
 * Whether the image loader may fetch this at all: anything that is not on the
 * relay origin, and on it only a GET for one of [RELAY_MEDIA_PATHS]. The loader
 * is handed addresses that come from message content; the relay's own images
 * all live under those paths (pure; RelayOriginTest).
 */
internal fun loadsAsImage(cfg: RelayConfig, method: String, url: okhttp3.HttpUrl): Boolean =
    !cfg.isRelayUrl(url) ||
        (method.equals("GET", ignoreCase = true) && RELAY_MEDIA_PATHS.any { cfg.isRelayPath(url, it) })

/** For the image loader's client only: refuses, before anything is sent, what [loadsAsImage] refuses. */
fun relayMediaOnlyInterceptorFor(config: () -> RelayConfig) = okhttp3.Interceptor { chain ->
    val req = chain.request()
    if (!loadsAsImage(config(), req.method, req.url)) throw java.io.IOException("not an image path of the relay")
    chain.proceed(req)
}

/**
 * The only code that attaches credentials. Application interceptor (so it also
 * covers the WebSocket upgrade): adds the relay token and the Cloudflare Access
 * pair when — and only when — the request is for the exact relay origin, and
 * drops any copy of those headers from a request headed anywhere else.
 *
 * Takes ONE snapshot from [config] per request and pins it on the request as a
 * tag, so the strip interceptor judges every later hop of the same call by the
 * origin the headers were issued for — a Save mid-flight can never hand the old
 * headers to the new host. An empty config matches nothing and attaches nothing.
 */
fun relayAuthInterceptorFor(config: () -> RelayConfig) = okhttp3.Interceptor { chain ->
    val req = chain.request()
    val cfg = config()
    val b = req.newBuilder().tag(RelayConfig::class.java, cfg)
    RELAY_CREDENTIAL_HEADERS.forEach { b.removeHeader(it) }
    if (attachesCredentials(cfg, req.method, req.url)) {
        try {
            if (cfg.token.isNotEmpty()) b.header("X-Imsg-Token", cfg.token)
            if (cfg.hasCfAccess) {
                b.header("CF-Access-Client-Id", cfg.cfClientId)
                b.header("CF-Access-Client-Secret", cfg.cfClientSecret)
            }
        } catch (e: IllegalArgumentException) {
            // A value OkHttp cannot put in a header (anything outside printable
            // ASCII). Its own exception quotes the value, and an
            // IllegalArgumentException escaping an interceptor of an enqueued
            // call is rethrown on the dispatcher thread (RealCall.AsyncCall.run),
            // which kills the process with the credential in the crash log. An
            // IOException is delivered to onFailure instead — as a plain network
            // failure, without the value. RelayConfigValidator refuses such a
            // value before Save; this covers one stored before that rule existed.
            throw IOException("relay credential is not header-safe")
        }
    }
    chain.proceed(b.build())
}

/**
 * Network interceptor: runs on every hop, including ones reached by following a
 * redirect. An application interceptor runs once per call and OkHttp keeps
 * custom headers across a cross-host redirect (it only drops Authorization), so
 * without this a relay response redirecting elsewhere would forward all three
 * credentials. Strips them on any hop that is not the relay origin — the origin
 * of the snapshot the request was started under (its tag), or the live one for
 * a request that never passed through [relayAuthInterceptorFor].
 */
fun relayAuthStripInterceptorFor(config: () -> RelayConfig) = okhttp3.Interceptor { chain ->
    val req = chain.request()
    val cfg = req.tag(RelayConfig::class.java) ?: config()
    if (cfg.isRelayUrl(req.url)) chain.proceed(req)
    else {
        val b = req.newBuilder()
        RELAY_CREDENTIAL_HEADERS.forEach { b.removeHeader(it) }
        chain.proceed(b.build())
    }
}

val relayAuthInterceptor = relayAuthInterceptorFor { RelayConfigStore.current }
val relayAuthStripInterceptor = relayAuthStripInterceptorFor { RelayConfigStore.current }
val relayMediaOnlyInterceptor = relayMediaOnlyInterceptorFor { RelayConfigStore.current }

/**
 * A relay client bound to [config]: the app's timeouts (OkHttp's defaults) and
 * both credential interceptors over that supplier, so headers go only to the
 * origin *it* names. The shared [http] is this over the live store; the Settings
 * "Test connection" builds a throwaway one over the typed, not-yet-saved values
 * (RelayProbe.kt) — the same factory, so the two cannot drift. A probe must not
 * follow redirects: a 3xx is a verdict there (Cloudflare Access), not a hop.
 */
fun relayHttpClientFor(followRedirects: Boolean = true, config: () -> RelayConfig): OkHttpClient =
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)  // keeps the WS alive through NAT/emulator idling
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .addInterceptor(relayAuthInterceptorFor(config))
        .addNetworkInterceptor(relayAuthStripInterceptorFor(config))
        .build()

val http: OkHttpClient = relayHttpClientFor { RelayConfigStore.current }

// WebSocket upgrades: OkHttp does not run network interceptors for them, so the
// per-hop strip above cannot protect a redirected upgrade. Never follow one.
private val httpWs: OkHttpClient = http.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * The longest one load of the thread list may take from start to finish:
 * resolving the relay's name, connecting, TLS, the request, the wait for the
 * answer and reading its body. The shared client bounds some steps on their
 * own (OkHttp's 10 s to connect, 10 s between two reads) but not their sum,
 * which has no upper limit: every address the name resolves to gets its own
 * 10 s, an answer that trickles in never trips the read timeout, and the name
 * lookup is not OkHttp's to bound at all. The list screen has no verdict to
 * show while its first load is in flight, so a load without an end was a list
 * screen that stayed blank.
 *
 * Twice the per-step timeouts, so a single connect or read that times out
 * still reports as itself. Set on the list request only ([loadThreads]): every
 * other route, the WebSocket, sends and uploads keep the shared client's
 * behaviour.
 */
const val THREADS_LOAD_TIMEOUT_MILLIS: Long = 20_000

/**
 * How much longer than [THREADS_LOAD_TIMEOUT_MILLIS] the caller of a list load
 * waits for it to end by itself. OkHttp's call timeout ends everything it can
 * interrupt, which is everything on a socket. A name lookup is not on one: it
 * is a blocking call into the platform resolver, and a call that times out in
 * the middle of it is only marked cancelled until the resolver returns. Past
 * this margin the caller stops waiting and reports the same timeout.
 */
const val THREADS_LOAD_GIVE_UP_MILLIS: Long = 1_000

/**
 * Where the list requests run. Not the caller's scope: a coroutine waits for
 * its children, and one of these may sit in a lookup nothing can interrupt
 * ([loadThreads]). A supervisor, so a load that fails ends alone.
 */
private val threadsLoads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** The thread list in a reply to `GET /threads`, or a [LoadFailedException] saying what answered in its place. */
private fun threadsFrom(r: Response): List<Thread> {
    val body = r.body!!.string()
    // Whatever decoded as a list before still does. A reply that is not one
    // (a 401, Cloudflare's page, another service) is classified for the list's
    // error panel (ThreadsLoad.kt) by its status and shape; the body goes no further.
    return runCatching { json.decodeFromString<ThreadsResp>(body).threads }.getOrElse {
        throw LoadFailedException(
            loadFailureFor(r.code, looksLikeHtml(r.header("Content-Type"), body)),
            "threads HTTP ${r.code}",
        )
    }
}

/**
 * One load of the thread list from the relay at [base] through [client], which
 * ends within [timeoutMillis] + [giveUpMillis] whatever the network does: with
 * the list, or with an exception [loadFailureFor] classifies (Api.threads is
 * this over the shared client, the live address and the two constants above).
 *
 * Two bounds, because one cannot do it alone. The request carries OkHttp's
 * overall call timeout ([timeoutMillis], on this call only): a connect that
 * stalls, a handshake or an answer that never comes and a body that trickles
 * all end there with an InterruptedIOException. A blocked name lookup does not,
 * so the request runs outside the caller's scope and the caller waits for it at
 * most [giveUpMillis] longer, then cancels it and throws the same
 * InterruptedIOException; the abandoned lookup ends when the resolver lets go,
 * finds its call cancelled and connects to nothing. A caller that is cancelled
 * itself (its screen is gone) cancels the request too.
 *
 * Everything, the building of the request included, happens after the hand-off
 * to the IO dispatcher, as on every other route of [Api]: [base] is "" in a
 * build with no relay, which no request can be built from, and that failure
 * must not be raised on the caller's thread before the load has suspended once.
 * The caller is the main thread, inside ChatVM's constructor (see its init
 * block). What no suspend function can promise is that the caller is told
 * "suspended" at all: a worker may have finished by then. ChatVM does not
 * depend on it.
 */
internal suspend fun loadThreads(
    client: OkHttpClient,
    base: String,
    timeoutMillis: Long = THREADS_LOAD_TIMEOUT_MILLIS,
    giveUpMillis: Long = THREADS_LOAD_GIVE_UP_MILLIS,
): List<Thread> = withContext(Dispatchers.IO) {
    val call = client.newCall(Request.Builder().url("$base/threads").build())
    call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
    // Whatever the request throws is kept for await(); once nobody waits for it, it is dropped.
    val load = threadsLoads.async { call.execute().use(::threadsFrom) }
    try {
        withTimeoutOrNull(timeoutMillis + giveUpMillis) { load.await() }
            ?: throw InterruptedIOException("timeout")
    } finally {
        // The caller gave up or was cancelled itself: stop what can be stopped. A load
        // that ended by itself is left alone, its connection goes back to the pool.
        if (!load.isCompleted) call.cancel()
    }
}

/**
 * How long the app waits for the relay's answer to one text send. The shared
 * client's read timeout is OkHttp's 10 s, and the relay may take most of a
 * minute over a send it then delivers ([RELAY_SEND_BUDGET_SECONDS]: BlueBubbles
 * not answering, then the AppleScript fallback): a client that gives up first
 * reports a failure for a message the Mac goes on to send. Longer than the
 * relay's own budget, shorter than a Cloudflare Tunnel's (524 after about 100 s).
 */
const val SEND_READ_TIMEOUT_SECONDS: Long = 75

/**
 * Text sends (and the first text of a new conversation), and an edit or an
 * unsend of a sent message, which the relay answers only once the Mac has
 * made the change: the pools and interceptors of `http`, waiting
 * [SEND_READ_TIMEOUT_SECONDS] for the answer. A request made with it carries
 * a [sentOnce] body, so one tap is one POST.
 */
val httpSend: OkHttpClient = http.newBuilder()
    .readTimeout(SEND_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

/**
 * The POST that answers the relay's voice question. The relay empties its
 * pending slot before it sends, so a POST that OkHttp repeated after a lost
 * connection would be told "nothing waiting" for a message that went out:
 * the body is written once ([sentOnce]).
 */
fun voiceConfirmRequest(base: String, answer: String): Request =
    Request.Builder().url("$base/v/confirm")
        .post(FormBody.Builder().add("answer", answer).build().sentOnce())
        .build()

/**
 * [this] as a body OkHttp writes to the wire at most once.
 *
 * OkHttp repeats a request by itself when the connection fails after the
 * request left (RetryAndFollowUpInterceptor: any IOException that is not a
 * timeout, such as a reset when Wi-Fi drops or a missed HTTP/2 ping on a silent
 * link), and after a 408, a 503 with `Retry-After: 0`, a 421 or a 307/308. The
 * relay has no way to tell the second POST from a second message, so a send
 * repeated that way reaches the recipient twice while the app sees one success.
 * A one-shot body turns all of those off and nothing else: a connection that
 * could not be made at all is still tried on the relay's other addresses, since
 * nothing was written yet. The failure then reaches the caller, which reports
 * it ([unsentWhyFor]) instead of sending again behind the owner's back.
 */
fun RequestBody.sentOnce(): RequestBody {
    val body = this
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
    }
}

// Attachment uploads: same pools as `http`, but generous timeouts — a video
// has to travel app -> relay -> BlueBubbles -> Apple before the call returns.
// These are only the app's own ceiling. Every request now goes through the
// Cloudflare tunnel, which enforces two tighter limits of its own: it answers
// 524 when the relay has sent nothing for ~100 s, and 413 for a request body
// over the plan limit (TUNNEL_MAX_BODY_BYTES). See sendFailureFor(). An upload's
// body is [sentOnce] as well: a file repeated by OkHttp would arrive twice.
val httpUpload: OkHttpClient = http.newBuilder()
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(5, TimeUnit.MINUTES)
    .build()

/** Cloudflare's request-body limit on the tunnel (Free/Pro plans: 100 MB). */
const val TUNNEL_MAX_BODY_BYTES: Long = 100L * 1024 * 1024
/** Headroom for the multipart framing around the file itself. */
private const val MULTIPART_OVERHEAD_BYTES: Long = 64L * 1024

/** Why a send (an attachment, or a text) failed, as far as the app can tell. */
enum class SendFailure { TOO_LARGE, TIMED_OUT, OTHER }

/**
 * A failed send with a classified cause (never carries a URL, a header or a
 * body). [httpCode] is the status that answered, when one did: it decides
 * whether the message certainly was not sent ([unsentWhyFor]). [html] marks a
 * 2xx reply that was a web page in the relay's place.
 */
class SendFailedException(
    val reason: SendFailure,
    detail: String,
    val httpCode: Int? = null,
    val html: Boolean = false,
) : IOException(detail)

/** True when a file of [sizeBytes] cannot fit through the tunnel. Unknown sizes (null / <0) pass. */
fun exceedsTunnelLimit(sizeBytes: Long?): Boolean =
    sizeBytes != null && sizeBytes >= 0 && sizeBytes > TUNNEL_MAX_BODY_BYTES - MULTIPART_OVERHEAD_BYTES

/** Classifies a non-2xx status from the tunnel/relay. 413 = body over the edge
 *  limit; 524/522/504/408 = the edge or relay gave up waiting. */
fun sendFailureFor(httpCode: Int): SendFailure = when (httpCode) {
    413 -> SendFailure.TOO_LARGE
    524, 522, 504, 408 -> SendFailure.TIMED_OUT
    else -> SendFailure.OTHER
}

/** Classifies whatever a send threw. */
fun sendFailureFor(t: Throwable?): SendFailure = when (t) {
    is SendFailedException -> t.reason
    is java.net.SocketTimeoutException -> SendFailure.TIMED_OUT
    else -> SendFailure.OTHER
}

/** Owner-facing text for a failed attachment send (a failed text is an unsent row: [unsentLine]). */
fun sendFailureMessage(reason: SendFailure): String = when (reason) {
    SendFailure.TOO_LARGE -> "Too large to send (limit is about 100 MB)"
    SendFailure.TIMED_OUT -> "Attachment timed out \u2014 it may be too large or the Mac too slow"
    SendFailure.OTHER -> "Couldn't send attachment"
}

/**
 * A throwable as a log line may name it: the class names along its cause chain
 * and nothing else. Its own text is never logged: it can quote a host, a
 * content URI, a header value or a piece of a reply (a JSON decoding error
 * prints the input around the offending character, chat identifiers included).
 */
fun failureLabel(t: Throwable): String {
    val names = ArrayList<String>()
    var cur: Throwable? = t
    while (cur != null && names.size < 4) {
        val here: Throwable = cur
        names.add(here.javaClass.name.substringAfterLast('.').ifEmpty { "Throwable" })
        val cause = here.cause
        cur = if (cause === here) null else cause
    }
    return names.joinToString(" <- ")
}

/**
 * The logcat line for a failed send of [what] ("text send", "attachment send",
 * "create chat"): the class names ([failureLabel]) and, when a status answered
 * in place of a delivery, that status. Never the text, the chat or the host.
 */
fun sendFailureLogLine(what: String, t: Throwable): String =
    "$what failed (${failureLabel(t)})" + ((t as? SendFailedException)?.httpCode?.let { " http=$it" } ?: "")

/** A WebSocket envelope type as the log may repeat it: a short identifier, never free text from the wire. */
private val WS_FRAME_TYPE = Regex("^[a-z][a-z0-9_-]{0,23}$")

/**
 * The logcat line for one incoming WebSocket frame: its envelope [type] and its
 * [length], never any of its content (a frame carries message text, sender
 * names and chat identifiers, which are phone numbers and e-mail addresses).
 * A frame that did not parse is "unparsed"; a type that is not a plain
 * identifier is "other".
 */
fun wsFrameLogLine(type: String?, length: Int): String {
    val safe = when {
        type == null -> "unparsed"
        WS_FRAME_TYPE.matches(type) -> type
        else -> "other"
    }
    return "frame type=$safe len=$length"
}

/**
 * The `type` of a WebSocket frame whose envelope the app does not model (the
 * relay also broadcasts frames that are not messages, such as a FaceTime
 * event), for [wsFrameLogLine] only. Null when the frame is not a JSON object
 * with a string `type`.
 */
fun wsFrameType(text: String): String? = runCatching {
    ((json.parseToJsonElement(text) as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
}.getOrNull()

/** The logcat line for a WebSocket failure: what kind, and the HTTP status of a refused upgrade when there is one. */
fun wsFailureLogLine(t: Throwable, httpCode: Int?): String =
    "failure: " + failureLabel(t) + (httpCode?.let { " http=$it" } ?: "")

@Serializable
data class Att(
    val guid: String,
    val mime_type: String? = null,
    val name: String? = null,
    val url: String,
)

@Serializable
data class ReplyRef(val text: String = "", val sender: String = "")

@Serializable
data class FtLinkResp(val link: String? = null)

@Serializable
data class LinkPreview(
    val url: String? = null,
    // The publisher article behind an Apple News link, when the relay could
    // resolve it; [url] stays the original link. Null on older relays.
    val resolved_url: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val site: String? = null,
    val image: String? = null,
)

@Serializable
data class Msg(
    val rowid: Long,
    val guid: String,
    val text: String? = null,
    val date: Double? = null,
    val date_read: Double? = null,
    val date_edited: Double? = null,
    val is_from_me: Boolean = false,
    val sender: String? = null,
    val sender_handle: String? = null,
    val chat_guid: String,
    val chat_name: String? = null,
    val is_group: Boolean = false,
    val has_attachments: Boolean = false,
    val assoc_guid: String? = null,
    val assoc_type: Int? = null,
    val attachments: List<Att> = emptyList(),
    val link: LinkPreview? = null,
    val reply_to_guid: String? = null,
    val reply_to: ReplyRef? = null,
    val network: String? = null,
    // chat.db message.service ("iMessage", "SMS", "RCS", "iMessageLite"); null
    // for Google Messages rows and for older relays that don't send it.
    val service: String? = null,
)

@Serializable
data class Thread(
    val chat_guid: String,
    val chat_name: String? = null,
    val is_group: Boolean = false,
    val handles: List<String> = emptyList(),
    val icon_url: String? = null,
    val last_date: Double? = null,
    val last_rowid: Long,
    val pinned: Boolean = false,
    val pin_index: Int = -1,
    val archived: Boolean = false,
    val preview: String? = null,
    val unread: Int = 0,
    val network: String? = null,
    val auto_translate: Boolean = false,
    // How this thread's messages travel. All optional so an older relay that
    // omits them still parses.
    val service: String? = null,       // "iMessage" | "SMS" | "RCS" | null
    val via_label: String? = null,     // header subtitle, e.g. "RCS · Google Messages · from Pixel (…0100)"
    val send_warning: String? = null,  // Mac-side SMS/RCS only; shown above the composer
)

@Serializable
data class MediaAtt(
    val guid: String,
    val mime_type: String? = null,
    val name: String? = null,
    val url: String,
    val date: Double? = null,
)

@Serializable
data class LinkItem(
    val url: String,
    val date: Double? = null,
    val sender: String? = null,
)

@Serializable
data class Media(
    val attachments: List<MediaAtt> = emptyList(),
    val links: List<LinkItem> = emptyList(),
)

@Serializable
data class SearchHit(
    val chat_guid: String,
    val chat_name: String? = null,
    val rowid: Long,
    val date: Double? = null,
    val snippet: String,
    // Google Messages (bp:) hits carry rowid 0 and identify the message by guid;
    // iMessage hits carry a rowid and may omit this.
    val guid: String? = null,
)

@Serializable
data class ContactHit(
    val name: String,
    val address: String,
)

@Serializable private data class ThreadsResp(val threads: List<Thread>)
@Serializable private data class MessagesResp(val messages: List<Msg>)
@Serializable private data class WsEnvelope(val type: String, val data: Msg? = null)
@Serializable private data class SendReq(
    val chat_guid: String, val text: String, val reply_to_guid: String? = null,
)
@Serializable private data class SendResp(val ok: Boolean = false, val via: String? = null)
@Serializable private data class ReactReq(val chat_guid: String, val message_guid: String, val reaction: String)
// part_index has no default on purpose: a default would be left out of the JSON, and the relay's contract names it.
@Serializable private data class UnsendReq(val chat_guid: String, val guid: String, val part_index: Int)
@Serializable private data class EditReq(val chat_guid: String, val guid: String, val text: String, val part_index: Int)
@Serializable private data class PinReq(val chat_guid: String, val pinned: Boolean)
@Serializable private data class ReadReq(val chat_guid: String, val rowid: Long)
@Serializable private data class ContactSearchResp(val results: List<ContactHit>)
@Serializable private data class MessageSearchResp(val results: List<SearchHit>)
@Serializable private data class CreateChatReq(val addresses: List<String>, val text: String)
@Serializable private data class CreateChatResp(val ok: Boolean = false, val chat_guid: String? = null)
@Serializable private data class MatchChatReq(val addresses: List<String>)
@Serializable private data class PinOrderReq(val order: List<String>)
@Serializable private data class ArchiveReq(val chat_guid: String, val archived: Boolean)
@Serializable private data class MatchChatResp(
    val found: Boolean = false,
    val chat_guid: String? = null,
    val chat_name: String? = null,
    val last_rowid: Long = 0,
    // Same optional thread fields /threads carries (older relays omit them).
    val service: String? = null,
    val via_label: String? = null,
    val send_warning: String? = null,
)
@Serializable private data class PushReq(val token: String)

/**
 * The POST for one text send to the relay at [base]. Its body is written at
 * most once ([sentOnce]): a send is not idempotent, and the relay cannot tell a
 * repeated POST from a second message.
 */
fun textSendRequest(base: String, guid: String, text: String, replyTo: String?): Request {
    val body = json.encodeToString(SendReq(guid, text, replyTo))
        .toRequestBody("application/json".toMediaType())
        .sentOnce()
    return Request.Builder().url("$base/send").post(body).build()
}

/** The part of a message an edit or an unsend names. The app offers both for plain text messages only, which are one part. */
private const val CHANGE_PART_INDEX = 0

/** How much of a reply to an edit or an unsend is read: the relay's is a few dozen bytes; a page in its place may be anything. */
private const val CHANGE_REPLY_MAX_BYTES = 64L * 1024

private fun changeRequest(url: String, payload: String): Request =
    Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType()).sentOnce()).build()

/**
 * The POST that unsends the message [guid] of [chatGuid] on the relay at
 * [base]. Written at most once ([sentOnce]), like a send: OkHttp must never
 * repeat a change behind the owner's back.
 */
fun unsendRequest(base: String, chatGuid: String, guid: String): Request =
    changeRequest("$base/unsend", json.encodeToString(UnsendReq(chatGuid, guid, CHANGE_PART_INDEX)))

/** The POST that replaces the text of the message [guid] of [chatGuid] with [text]; written at most once. */
fun editRequest(base: String, chatGuid: String, guid: String, text: String): Request =
    changeRequest("$base/edit", json.encodeToString(EditReq(chatGuid, guid, text, CHANGE_PART_INDEX)))

/**
 * One edit or unsend through [client]: the request [build] makes, and how it
 * ended ([changeOutcomeFor], EditUnsend.kt). It never throws for a failure:
 * a request that could not be built or sent, a refusal and an answer that is
 * not the relay's are all outcomes. A bounded piece of the reply is read, for
 * its JSON keys only.
 */
internal fun postChange(client: OkHttpClient, build: () -> Request): ChangeOutcome =
    try {
        client.newCall(build()).execute().use { r ->
            changeOutcomeFor(r.code, r.header("Content-Type"), r.peekBody(CHANGE_REPLY_MAX_BYTES).string())
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        changeOutcomeFor(e)
    }

object Api {
    /**
     * The thread list. Ends within about [THREADS_LOAD_TIMEOUT_MILLIS] whatever
     * the network does ([loadThreads]); a failure throws what the list's error
     * panel classifies (loadFailureFor, ThreadsLoad.kt).
     */
    suspend fun threads(): List<Thread> = loadThreads(http, BASE)

    /** The latest [limit] messages, or with [before] the [limit] messages with rowid below it (older history). */
    suspend fun messages(guid: String, limit: Int = 50, before: Long? = null): List<Msg> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("thread").addPathSegment(guid).addPathSegment("messages")
            .addQueryParameter("limit", limit.toString())
            .apply { if (before != null) addQueryParameter("before", before.toString()) }
            .build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            json.decodeFromString<MessagesResp>(r.body!!.string()).messages
        }
    }

    /**
     * The delivery path the reply to a send names ("bb" when a 2xx names none).
     * Anything else throws [SendFailedException] with the status: a non-2xx, or
     * a 2xx that is not the relay's own reply (a web page answered in its
     * place: [isRelaySendReply]), which is a failed send and not a delivery. A
     * 2xx whose body cannot be read throws what the read threw: it used to
     * count as delivered, but nothing shows it was the relay that answered (a
     * login page cut off mid-way looks the same), and counting it as delivered
     * would drop the text without a word. The caller reports it as a send that
     * ended without an answer. [what] names the call in the exception
     * ("send HTTP 502").
     */
    private fun sendReplyVia(r: Response, what: String): String {
        if (!r.isSuccessful) throw SendFailedException(sendFailureFor(r.code), "$what HTTP ${r.code}", r.code)
        val reply = r.body!!.string()
        val type = r.header("Content-Type")
        if (!isRelaySendReply(type, reply)) {
            throw SendFailedException(
                SendFailure.OTHER, "$what HTTP ${r.code}, not the relay's reply", r.code, html = looksLikeHtml(type, reply),
            )
        }
        return runCatching { json.decodeFromString<SendResp>(reply) }.getOrNull()?.via ?: "bb"
    }

    /**
     * Sends one text; returns the delivery path ("bb", "applescript" or
     * "gmessages"). Every failure throws: [SendFailedException] with the status
     * when something answered that was not a delivery, or whatever the
     * connection threw. Waits [SEND_READ_TIMEOUT_SECONDS] for the answer (the
     * [httpSend] client) and is never repeated by OkHttp ([textSendRequest]).
     * Texts typed by the owner go through [Outbox], which keeps them until this
     * returns; only a caller that reports the failure itself calls this directly.
     */
    suspend fun send(guid: String, text: String, replyTo: String? = null): String = withContext(Dispatchers.IO) {
        httpSend.newCall(textSendRequest(BASE, guid, text, replyTo)).execute().use { r -> sendReplyVia(r, "send") }
    }

    /** Streams a picked photo/video to the relay; returns the delivery path.
     *  Every failure throws: [SendFailedException] when something answered that
     *  was not a delivery (too large for the tunnel, a timeout, any other
     *  status), so the caller can say why. The body is written once ([sentOnce]). */
    suspend fun sendAttachment(guid: String, resolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var size: Long? = null
            val displayName: String? = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni) else null
                } else null
            }
            // Don't push a body the tunnel is certain to reject with 413.
            if (exceedsTunnelLimit(size)) {
                throw SendFailedException(SendFailure.TOO_LARGE, "attachment is $size bytes")
            }
            val name = if (!displayName.isNullOrBlank()) displayName else {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                "attachment-${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
            }
            val fileBody = object : RequestBody() {
                override fun contentType() = mime.toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    // FileNotFoundException, as the resolver itself throws: nothing was sent (unsentWhyFor).
                    val input = resolver.openInputStream(uri)
                        ?: throw java.io.FileNotFoundException("cannot open the picked file")
                    input.use { sink.writeAll(it.source()) }
                }
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_guid", guid)
                .addFormDataPart("file", name, fileBody)
                .build()
                .sentOnce()
            val req = Request.Builder().url("$BASE/send_attachment").post(body).build()
            httpUpload.newCall(req).execute().use { r -> sendReplyVia(r, "send_attachment") }
        }

    suspend fun react(chatGuid: String, messageGuid: String, reaction: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(ReactReq(chatGuid, messageGuid, reaction))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE/react").post(body).build()
            http.newCall(req).execute().use { it.isSuccessful }
        }

    /**
     * Unsends ("Undo Send") the owner's message [guid] in [chatGuid]. One
     * request, never repeated ([unsendRequest]), on the send client: the relay
     * answers only once the Mac's message database shows the change, which may
     * take a while. How it ended is the result; nothing is thrown for a failure.
     */
    suspend fun unsend(chatGuid: String, guid: String): ChangeOutcome = withContext(Dispatchers.IO) {
        postChange(httpSend) { unsendRequest(BASE, chatGuid, guid) }
    }

    /** Replaces the text of the owner's message [guid] in [chatGuid] with [text]; otherwise as [unsend]. */
    suspend fun edit(chatGuid: String, guid: String, text: String): ChangeOutcome = withContext(Dispatchers.IO) {
        postChange(httpSend) { editRequest(BASE, chatGuid, guid, text) }
    }

    suspend fun media(guid: String): Media = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("thread").addPathSegment(guid).addPathSegment("media").build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("media HTTP ${r.code}")
            json.decodeFromString<Media>(r.body!!.string())
        }
    }

    suspend fun pin(chatGuid: String, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PinReq(chatGuid, pinned))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/pin").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun read(chatGuid: String, rowid: Long): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ReadReq(chatGuid, rowid))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/read").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    /** Answers an incoming FaceTime call on the Mac; returns the web join
     *  link. Legitimately slow (5-40s) — rides the long-timeout client. */
    suspend fun ftAnswer(uuid: String): String? = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegment("ft_answer")
            .addQueryParameter("uuid", uuid).build()
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        httpUpload.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else json.decodeFromString<FtLinkResp>(r.body!!.string()).link
        }
    }

    suspend fun ftDecline(uuid: String): Boolean = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegment("ft_decline")
            .addQueryParameter("uuid", uuid).build()
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    /** Mints a fresh outbound FaceTime link (opening it rings every Apple
     *  device on the account). Whoever opens it waits in the call's web lobby
     *  until a participant admits them; the Mac does that by itself only when
     *  the relay's optional, display-specific auto-admit rig is enabled there
     *  (docs/facetime-bridge.md in the relay repository). */
    suspend fun ftNewLink(): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/ft_link")
            .post(ByteArray(0).toRequestBody()).build()
        httpUpload.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else json.decodeFromString<FtLinkResp>(r.body!!.string()).link
        }
    }

    suspend fun searchMessages(q: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegment("search")
            .addQueryParameter("q", q).build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            json.decodeFromString<MessageSearchResp>(r.body!!.string()).results
        }
    }

    /** Message hits in one chat (`GET /search?q=&chat=`); the builder URL-encodes the guid's ';' and '+'. */
    suspend fun searchInChat(chatGuid: String, q: String, limit: Int = 30): List<SearchHit> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder().addPathSegment("search")
            .addQueryParameter("q", q)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("chat", chatGuid)
            .build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            json.decodeFromString<MessageSearchResp>(r.body!!.string()).results
        }
    }

    suspend fun searchContacts(q: String): List<ContactHit> = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("contacts").addPathSegment("search")
            .addQueryParameter("q", q).build()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            json.decodeFromString<ContactSearchResp>(r.body!!.string()).results
        }
    }

    /**
     * Starts a conversation with its first text; returns the chat_guid to open
     * (null when a 2xx reply names none). The relay delivers that text through
     * the same engines as a send and may take as long over it, so this waits
     * like one ([httpSend]) and is written once ([sentOnce]); a failure throws
     * as [send] does.
     */
    suspend fun createChat(addresses: List<String>, text: String): String? =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(CreateChatReq(addresses, text))
                .toRequestBody("application/json".toMediaType())
                .sentOnce()
            val req = Request.Builder().url("$BASE/create_chat").post(body).build()
            httpSend.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw SendFailedException(sendFailureFor(r.code), "create_chat HTTP ${r.code}", r.code)
                val reply = r.body!!.string()
                val type = r.header("Content-Type")
                if (!isRelaySendReply(type, reply)) {
                    throw SendFailedException(
                        SendFailure.OTHER, "create_chat HTTP ${r.code}, not the relay's reply", r.code,
                        html = looksLikeHtml(type, reply),
                    )
                }
                json.decodeFromString<CreateChatResp>(reply).chat_guid
            }
        }

    suspend fun pinOrder(order: List<String>): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PinOrderReq(order))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/pin_order").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    /** Voice: send the spoken sentence, get back a plain-English line to speak. */
    suspend fun voicePrepare(query: String): String? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("query", query).build()
        val req = Request.Builder().url("$BASE/v/prepare").post(body).build()
        http.newCall(req).execute().use { r -> voiceReply(r.code, if (r.isSuccessful) r.body?.string() else null) }
    }

    /**
     * Voice: answer the relay's question ("yes" / "no" / a contact name). A
     * "yes" makes the relay send before it answers, so this waits as long as
     * a text send does ([httpSend]) and is posted once ([voiceConfirmRequest]).
     */
    suspend fun voiceConfirm(answer: String): String? = withContext(Dispatchers.IO) {
        httpSend.newCall(voiceConfirmRequest(BASE, answer)).execute().use { r ->
            voiceReply(r.code, if (r.isSuccessful) r.body?.string() else null)
        }
    }

    suspend fun archive(chatGuid: String, archived: Boolean): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ArchiveReq(chatGuid, archived))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/archive").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun markUnread(chatGuid: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = org.json.JSONObject().put("chat_guid", chatGuid).toString()
                .toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("$BASE/unread").post(body).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Ask the relay to re-pull the contact map from BlueBubbles. */
    suspend fun refreshContacts(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/contacts/refresh")
            .post("".toRequestBody(null)).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    /** The existing chat exactly matching this recipient set, if any. */
    suspend fun matchChat(addresses: List<String>): Thread? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(MatchChatReq(addresses))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/match_chat").post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val m = json.decodeFromString<MatchChatResp>(r.body!!.string())
            if (m.found && m.chat_guid != null) {
                Thread(chat_guid = m.chat_guid, chat_name = m.chat_name, last_rowid = m.last_rowid,
                       service = m.service, via_label = m.via_label, send_warning = m.send_warning)
            } else null
        }
    }

    suspend fun registerPush(token: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PushReq(token))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$BASE/register_push").post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }
}

/**
 * The WebSocket upgrade request for one config snapshot, or null when there is
 * nothing to connect to: no relay configured, no WebSocket URL, a URL that does
 * not parse (OkHttp throws IllegalArgumentException for "" — that killed the
 * process on a stranger's first launch), or one that is not on the relay
 * origin. Carries no credentials in the URL: relayAuthInterceptor puts
 * X-Imsg-Token (and the Cloudflare Access pair) on the headers.
 */
fun relayWsRequest(cfg: RelayConfig): Request? {
    if (!cfg.isConfigured || cfg.wsUrl.isEmpty()) return null
    // Request.Builder maps wss:// to https:// for the upgrade.
    val req = runCatching { Request.Builder().url(cfg.wsUrl).build() }.getOrNull() ?: return null
    return req.takeIf { cfg.isRelayUrl(it.url) }
}

/**
 * WebSocket with auto-reconnect. Any relay restart or dropped connection
 * schedules a reconnect; onOpen fires on every (re)connect so the caller can
 * refresh whatever was missed while the socket was down. With no relay
 * configured it stays idle (no socket, no reconnect loop) until the config
 * changes and the owner of this manager calls [reconnect].
 *
 * One socket at a time: every connect attempt gets a generation number, and a
 * listener (or a pending reconnect) whose generation is no longer the current
 * one is ignored, so cancelling the old socket on a config change can never
 * leave two sockets up or an orphaned reconnect timer behind.
 */
class WsManager(
    private val onOpen: () -> Unit,
    private val onMsg: (Msg) -> Unit,
    private val onUpdate: (Msg) -> Unit,
) {
    @Volatile private var stopped = false
    private var ws: WebSocket? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    /** This manager's sockets in the process-wide state; it can end only its own (RelaySocket.kt). */
    private val sockets = relaySocket.owner()

    fun start() = connect()

    /**
     * The relay config changed (Settings saved or reset): drop the socket this
     * manager has, if any, and connect again against the new snapshot. Also the
     * way an unconfigured manager that stayed idle at [start] comes alive once a
     * first config is saved.
     */
    fun reconnect() = connect()

    @Synchronized
    private fun connect() {
        if (stopped) return
        // Whatever socket is up is replaced, never joined: the generation moves on
        // first, so the old listener — and the onFailure its cancel triggers — is
        // already orphaned, and any reconnect it had pending finds itself stale.
        val gen = generation.incrementAndGet()
        val attempt = sockets.attempt()
        ws?.cancel()
        ws = null
        // One snapshot per attempt (see RelayConfig.kt); nothing to do while unconfigured.
        val req = relayWsRequest(RelayConfigStore.current)
        if (req == null) {
            Log.d("ImsgWS", "no relay configured; not connecting")
            return
        }
        ws = httpWs.newWebSocket(req, object : WebSocketListener() {
            private fun live() = !stopped && gen == generation.get()

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!live()) return
                sockets.opened(attempt)
                Log.d("ImsgWS", "connected")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!live()) return
                val env = runCatching { json.decodeFromString<WsEnvelope>(text) }.getOrNull()
                // Type and length only: a frame is message text, names and chat identifiers.
                Log.d("ImsgWS", wsFrameLogLine(env?.type ?: wsFrameType(text), text.length))
                if (env == null) return
                when (env.type) {
                    "message" -> env.data?.let(onMsg)
                    "update" -> env.data?.let(onUpdate)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The relay asked to close: it is restarting, or it gave up on a connection that had
                // stopped taking data. OkHttp reports onClosed only once this side has closed as well,
                // and the default does nothing, so without this answer nothing here reconnected until
                // a ping went unanswered (audit R3-F4).
                sockets.ended(attempt)
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.ended(attempt)
                if (!live()) return
                // The throwable's own text can name the relay's host or repeat a status line: class and code only.
                Log.d("ImsgWS", wsFailureLogLine(t, response?.code))
                scheduleReconnect(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.ended(attempt)
                if (!live()) return
                // The close reason is free text from the wire; the code says enough.
                Log.d("ImsgWS", "closed: $code")
                scheduleReconnect(gen)
            }
        })
    }

    /** Reconnects in 3 s unless the socket of [gen] was replaced meanwhile ([reconnect]) or the manager stopped. */
    private fun scheduleReconnect(gen: Int) {
        if (stopped) return
        // NB: fully qualified — our own `Thread` data class shadows java.lang.Thread here.
        java.lang.Thread {
            java.lang.Thread.sleep(3000)
            if (!stopped && gen == generation.get()) {
                Log.d("ImsgWS", "reconnecting…")
                connect()
            }
        }.start()
    }

    /**
     * Same monitor as [connect], and the generation moves on: a connect that
     * was waiting on the lock finds `stopped` set, and one that had just opened a
     * socket has it cancelled here rather than left live with a deaf listener.
     */
    @Synchronized
    fun stop() {
        stopped = true
        generation.incrementAndGet()
        sockets.stop()
        ws?.cancel()
        ws = null
    }
}
