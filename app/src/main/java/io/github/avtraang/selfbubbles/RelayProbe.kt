package io.github.avtraang.selfbubbles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The `/health` protocol version this app was written against. A relay that
 * reports a lower one (or none: older relays send no `protocol`) still works,
 * but Settings shows an "Update the relay" line after a Test connection.
 */
const val RELAY_PROTOCOL_EXPECTED = 1

/** How many engine or feature names a Connected message lists before "…": a relay cannot paint a whole screen. */
private const val MAX_LISTED_NAMES = 8

/** How much of a `/health` body is read: a real one is under a kilobyte; a mistyped host may serve anything. */
private const val MAX_PROBE_BODY_BYTES = 64L * 1024

/** What one `GET /health` against the typed relay settings told us. Messages never carry a credential. */
sealed class ProbeOutcome {
    abstract val message: String

    /** The relay answered as a relay: the token was accepted (the body carried `cursor`). */
    data class Connected(
        val protocol: Int,
        val engines: List<String>,
        val features: Map<String, Boolean>,
        /**
         * What the relay says it can do to a sent message ("edit", "unsend"), as
         * its `capabilities` list; null for a relay that sends none. Not shown:
         * it only tells the long-press panel what to offer ([ChangeSupport]).
         */
        val capabilities: List<String>? = null,
    ) : ProbeOutcome() {
        /** True for a relay older than this app expects ([RELAY_PROTOCOL_EXPECTED]). */
        val outdated: Boolean get() = protocol < RELAY_PROTOCOL_EXPECTED

        /**
         * "Connected · relay protocol N · engines: a, b · features: x, y", without
         * the parts an older relay does not send; a list longer than
         * [MAX_LISTED_NAMES] ends in "…" (the data itself is complete).
         */
        override val message: String
            get() = buildList {
                add("Connected")
                if (protocol > 0) add("relay protocol $protocol")
                if (engines.isNotEmpty()) add("engines: " + listed(engines))
                val on = features.filterValues { it }.keys
                if (on.isNotEmpty()) add("features: " + listed(on))
            }.joinToString(" · ")

        private fun listed(names: Collection<String>): String =
            names.take(MAX_LISTED_NAMES).joinToString(", ") + if (names.size > MAX_LISTED_NAMES) ", …" else ""
    }

    /** A relay answered, but as it does to anyone: 200 JSON without `cursor` (`/health` never 401s). */
    object TokenRejected : ProbeOutcome() {
        override val message = "The relay rejected the token"
    }

    /** Cloudflare Access answered instead of the relay: HTML, a 403, or a redirect to its login. */
    object AccessRejected : ProbeOutcome() {
        override val message = "Cloudflare Access rejected the request — check the service-token id and secret"
    }

    /** TLS failed: a self-signed or private-CA certificate, or no TLS at all on that port. */
    object NoTrustedCert : ProbeOutcome() {
        override val message = "No trusted certificate at this address; the app only speaks HTTPS"
    }

    /** DNS, connect or timeout failure. [host] is the typed one (parsed, so never userinfo). */
    data class Unreachable(val host: String) : ProbeOutcome() {
        override val message get() = "Can't reach $host"
    }

    /** Anything else, in one short line. */
    data class Other(override val message: String) : ProbeOutcome()
}

/**
 * "Test connection" for the relay form: one `GET /health` with the typed, not
 * yet saved config, classified without a model of the relay beyond its
 * `/health` contract. `/health` is middleware-exempt and never returns 401:
 * without a valid `X-Imsg-Token` the body is exactly `{"ok":true}`; with one it
 * carries `cursor` (and, on newer relays, `engines`, `features`, `protocol`).
 * Behind Cloudflare Access a wrong service-token pair yields an HTML 403 or a
 * 302 to the Access login, which is why the probe client never follows
 * redirects. The two `classify` functions are pure and unit-tested.
 */
object RelayProbe {

    /** Engine and feature names as the UI may echo them: identifiers only, never an arbitrary string from the wire. */
    private val SAFE_NAME = Regex("^[A-Za-z0-9_-]{1,24}$")

    /** Strict JSON is enough for `/health`; a lenient parser would call junk a document. */
    private val parser = Json { ignoreUnknownKeys = true }

    /**
     * Classifies a response. The body is read for its JSON keys only and is
     * never echoed: a server (or whatever sits in front of it) may repeat the
     * request headers back, and those carry the token.
     */
    fun classify(code: Int, contentType: String?, body: String): ProbeOutcome {
        if (code in 300..399) return ProbeOutcome.AccessRejected
        val html = contentType?.contains("text/html", ignoreCase = true) == true ||
            body.trimStart().startsWith("<")
        if (html || code == 403) return ProbeOutcome.AccessRejected
        // Only a 2xx can be the relay's own answer; a JSON 404 or 500 is something else's.
        if (code !in 200..299) return ProbeOutcome.Other("HTTP $code from that address")
        val obj = runCatching { parser.parseToJsonElement(body).jsonObject }.getOrNull()
        if (obj != null) {
            if ("cursor" !in obj) return ProbeOutcome.TokenRejected
            val protocol = obj["protocol"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
            val engines = obj["engines"]?.let { e ->
                runCatching { e.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            }.orEmpty().filter { SAFE_NAME.matches(it) }
            val features = obj["features"]?.let { f ->
                runCatching {
                    f.jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.booleanOrNull?.let { k to it } }.toMap()
                }.getOrNull()
            }.orEmpty().filterKeys { SAFE_NAME.matches(it) }
            // Absent, or not a list of strings: the relay said nothing, which is not the same as an empty list.
            val capabilities = obj["capabilities"]?.let { c ->
                runCatching { c.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            }?.filter { SAFE_NAME.matches(it) }
            return ProbeOutcome.Connected(protocol, engines, features, capabilities)
        }
        return ProbeOutcome.Other("Not a relay: unexpected reply")
    }

    /**
     * Classifies a failure to get a response at all. [host] is the typed relay
     * host (parsed, so never userinfo). A throwable's own text is never
     * repeated: OkHttp's header-validation error, for one, quotes the offending
     * value, which would be the token.
     */
    fun classify(t: Throwable, host: String): ProbeOutcome = when (t) {
        // SSLHandshakeException (untrusted certificate), SSLPeerUnverifiedException
        // (hostname mismatch) and SSLProtocolException (no TLS at all on that port)
        // all extend it; none of them is a reachability problem.
        is SSLException -> ProbeOutcome.NoTrustedCert
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
            ProbeOutcome.Unreachable(host)
        else -> ProbeOutcome.Other("Connection failed (${t.javaClass.simpleName})")
    }

    /**
     * Runs the probe against [typed] (normalized here, as Save would). A
     * throwaway client is built for it, bound to the typed config, so the token
     * and the Cloudflare pair go to the typed origin and nowhere else; the live
     * [http] is never used because its interceptors read the saved store.
     * Cancelling the calling coroutine cancels the HTTP call.
     */
    suspend fun probe(typed: RelayConfig): ProbeOutcome = withContext(Dispatchers.IO) {
        val cfg = RelayConfigValidator.normalized(typed)
        val host = cfg.origin?.host
            ?: return@withContext ProbeOutcome.Other("Relay URL must be an https:// URL with a host")
        // A credential OkHttp cannot put in a header never reaches the client (the
        // interceptor would refuse it, but with a less useful verdict).
        if (RelayConfigValidator.credentialProblems(cfg).isNotEmpty()) {
            return@withContext ProbeOutcome.Other("The token or the Cloudflare values contain characters a header cannot carry")
        }
        val client = relayHttpClientFor(followRedirects = false) { cfg }
        try {
            val req = Request.Builder().url("${cfg.base}/health").build()
            client.newCall(req).await().use { r ->
                // Bounded: whatever answers at a mistyped address is not read whole into memory.
                classify(r.code, r.header("Content-Type"), r.peekBody(MAX_PROBE_BODY_BYTES).string())
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            classify(t, host)
        } finally {
            // Nothing else will use this client: let its threads and sockets go now.
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            // A response landing after a cancel is closed, not leaked.
            override fun onResponse(call: Call, response: Response) { cont.resume(response) { response.close() } }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
