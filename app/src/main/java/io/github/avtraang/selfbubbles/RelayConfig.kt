package io.github.avtraang.selfbubbles

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Where the relay is and what every request to it carries. One immutable value:
 * `RelayConfigStore.current` is replaced atomically on Save, and each request
 * takes exactly one snapshot and uses it for both the origin check and the
 * headers, so a new token can never pair with an old host (see Imsg.kt).
 *
 * Nothing here touches the network or Android; it is plain Kotlin so the
 * validation table and the store run in JVM unit tests.
 */
class RelayConfig(
    base: String = "",
    wsUrl: String = "",
    token: String = "",
    cfClientId: String = "",
    cfClientSecret: String = "",
    mapUrl: String = "",
) {
    /** Relay base URL: https, no trailing slash. "" when unconfigured. */
    val base: String = base.trim().trimEnd('/')
    /** Relay WebSocket URL (wss on the base origin). No query string: auth rides in headers. */
    val wsUrl: String = wsUrl.trim()
    /** Must match IMSG_TOKEN on the relay; travels as a header, never in a URL. */
    val token: String = token.trim()
    /** Cloudflare Access service-token pair; sent only when both are present. */
    val cfClientId: String = cfClientId.trim()
    val cfClientSecret: String = cfClientSecret.trim()
    /** Page loaded in the Map tab (query string and #fragment allowed). */
    val mapUrl: String = mapUrl.trim()

    /** The one origin that may receive credentials; null unless [base] is an https URL with a host. */
    val origin: RelayOrigin? = this.base.toHttpUrlOrNull()
        ?.takeIf { it.scheme == "https" }
        ?.let { RelayOrigin(it.host, it.port) }

    /** Encoded path prefix of [base] ("" for a bare host); `isRelayPath` prepends it. */
    val basePath: String = this.base.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()

    val hasCfAccess: Boolean = this.cfClientId.isNotEmpty() && this.cfClientSecret.isNotEmpty()

    /** Enough to talk to a relay at all: an https origin and a token. */
    val isConfigured: Boolean = origin != null && this.token.isNotEmpty()

    /** True only for https URLs on the relay's exact host and port; always false when unconfigured. */
    fun isRelayUrl(url: HttpUrl): Boolean = origin?.matches(url) ?: false

    /** String overload; false for unparseable input or an empty config. */
    fun isRelayUrl(url: String): Boolean = origin?.matches(url) ?: false

    /** True for a relay URL whose path starts with [pathPrefix] (e.g. "/attachment/"). */
    fun isRelayPath(url: HttpUrl, pathPrefix: String): Boolean =
        isRelayUrl(url) && url.encodedPath.startsWith(basePath + pathPrefix)

    fun copy(
        base: String = this.base,
        wsUrl: String = this.wsUrl,
        token: String = this.token,
        cfClientId: String = this.cfClientId,
        cfClientSecret: String = this.cfClientSecret,
        mapUrl: String = this.mapUrl,
    ): RelayConfig = RelayConfig(base, wsUrl, token, cfClientId, cfClientSecret, mapUrl)

    override fun equals(other: Any?): Boolean = other is RelayConfig &&
        base == other.base && wsUrl == other.wsUrl && token == other.token &&
        cfClientId == other.cfClientId && cfClientSecret == other.cfClientSecret && mapUrl == other.mapUrl

    override fun hashCode(): Int =
        listOf(base, wsUrl, token, cfClientId, cfClientSecret, mapUrl).hashCode()

    /** Never prints a credential: this ends up in logs and crash reports. */
    override fun toString(): String =
        "RelayConfig(base=$base, ws=$wsUrl, token=${if (token.isEmpty()) "unset" else "set"}, " +
            "cfAccess=${if (hasCfAccess) "set" else "unset"}, map=${if (mapUrl.isEmpty()) "unset" else "set"})"

    companion object {
        /** No relay at all: nothing matches, nothing is attached. */
        val EMPTY = RelayConfig()

        /** The values baked in from secrets.properties (each "" when that key was absent at build time). */
        fun fromBuildConfig(): RelayConfig = fromBuildValues(
            base = BuildConfig.RELAY_REMOTE_BASE,
            wsUrl = BuildConfig.RELAY_REMOTE_WS,
            token = BuildConfig.IMSG_TOKEN,
            cfClientId = BuildConfig.CF_ACCESS_CLIENT_ID,
            cfClientSecret = BuildConfig.CF_ACCESS_CLIENT_SECRET,
            mapUrl = BuildConfig.FAMILY_MAP_URL,
        )

        /**
         * [fromBuildConfig] on explicit values (so it is unit-testable). Same
         * fill-in as a Save: a build with RELAY_REMOTE_BASE but no
         * RELAY_REMOTE_WS gets `wss://host[:port]<path>/ws` derived from the
         * base, so its WebSocket URL is never "" (which OkHttp refuses with an
         * exception); an explicit WS value is kept exactly as written.
         */
        fun fromBuildValues(
            base: String,
            wsUrl: String,
            token: String,
            cfClientId: String,
            cfClientSecret: String,
            mapUrl: String,
        ): RelayConfig = RelayConfigValidator.normalized(
            RelayConfig(base, wsUrl, token, cfClientId, cfClientSecret, mapUrl)
        )
    }
}

/**
 * The rules a config must pass before it can become `current`. They mirror the
 * guard in app/build.gradle.kts (which runs on secrets.properties at build time
 * and says so in a comment); keep the two in step. Messages name a field, never
 * a value.
 */
object RelayConfigValidator {
    /** Fills in what can be derived: a blank WebSocket URL becomes `wss://host[:port]<path>/ws`. */
    fun normalized(c: RelayConfig): RelayConfig {
        if (c.wsUrl.isNotEmpty() || c.base.isEmpty()) return c
        val u = runCatching { URI(c.base) }.getOrNull() ?: return c
        if (!"https".equals(u.scheme, ignoreCase = true) || u.host.isNullOrEmpty()) return c
        val port = if (u.port == -1) "" else ":${u.port}"
        val path = u.rawPath.orEmpty().trimEnd('/')
        return c.copy(wsUrl = "wss://${u.host}$port$path/ws")
    }

    /** Every rule [c] breaks, in a fixed order; empty means it may be saved. */
    fun problems(c: RelayConfig): List<String> {
        val out = mutableListOf<String>()
        val base = checkUrl(c.base, "Relay URL", "https", out)
        val ws = checkUrl(c.wsUrl, "WebSocket URL", "wss", out)
        if (base != null && ws != null &&
            (!base.host.equals(ws.host, ignoreCase = true) || portOf(base) != portOf(ws))
        ) {
            out += "WebSocket URL must be on the same host and port as the relay URL " +
                "(credentials are only sent to that one origin)."
        }
        if (c.token.isEmpty()) out += "Token is required."
        if (c.cfClientId.isEmpty() != c.cfClientSecret.isEmpty()) {
            out += "Cloudflare Access needs both the client id and the client secret, or neither."
        }
        out += credentialProblems(c)
        if (c.mapUrl.isNotEmpty()) {
            val m = runCatching { URI(c.mapUrl) }.getOrNull()
            if (m == null || !"https".equals(m.scheme, ignoreCase = true) || m.host.isNullOrEmpty()) {
                out += "Map URL must be empty or an https:// URL with a host."
            }
        }
        return out
    }

    /**
     * True when [v] can travel as an HTTP header value: printable ASCII only.
     * OkHttp refuses anything else with an IllegalArgumentException that quotes
     * the whole value — and, raised inside an interceptor of an enqueued call,
     * that exception is rethrown on the dispatcher thread and kills the process
     * with the credential in the crash log. So it is a rule here, before Save,
     * and a guard in relayAuthInterceptorFor (Imsg.kt) for anything stored
     * before the rule existed.
     */
    fun isHeaderSafe(v: String): Boolean = v.all { it in ' '..'~' }

    /**
     * The header-safety rules [c]'s credentials break (part of [problems]).
     * Non-empty means no request may carry them: Save is refused and Test
     * connection does not run. Messages name the field, never the value.
     */
    fun credentialProblems(c: RelayConfig): List<String> = buildList {
        if (!isHeaderSafe(c.token)) add("Token must be plain ASCII text (no accents, emoji or invisible characters).")
        if (!isHeaderSafe(c.cfClientId)) {
            add("Cloudflare Access client id must be plain ASCII text (no accents, emoji or invisible characters).")
        }
        if (!isHeaderSafe(c.cfClientSecret)) {
            add("Cloudflare Access client secret must be plain ASCII text (no accents, emoji or invisible characters).")
        }
    }

    /** [normalized] + [problems]; the config to store, or an [IllegalArgumentException] listing every problem. */
    fun validate(c: RelayConfig): RelayConfig {
        val n = normalized(c)
        val p = problems(n)
        require(p.isEmpty()) { p.joinToString("\n") }
        return n
    }

    private fun portOf(u: URI): Int = if (u.port == -1) 443 else u.port

    /** Appends to [out] whatever is wrong with [raw] as a [scheme] URL; returns it parsed when it is fine. */
    private fun checkUrl(raw: String, label: String, scheme: String, out: MutableList<String>): URI? {
        if (raw.isEmpty()) {
            out += "$label is required."
            return null
        }
        val u = runCatching { URI(raw) }.getOrNull()
        if (u == null || !scheme.equals(u.scheme, ignoreCase = true) || u.host.isNullOrEmpty()) {
            out += "$label must be a $scheme:// URL with a host (the app only speaks TLS; cleartext is not allowed)."
            return null
        }
        if (u.port != -1 && u.port !in 1..65535) {
            out += "$label has an invalid port."
            return null
        }
        if (u.rawUserInfo != null || u.rawFragment != null) {
            out += "$label must not contain user info or a #fragment."
            return null
        }
        if (u.rawQuery != null) {
            out += "$label must not carry a query string (credentials travel in headers, never in a URL)."
            return null
        }
        return u
    }
}

/**
 * Holds the live [RelayConfig]. Layers: the build's own values are the
 * permanent fallback; the SharedPreferences file "relay_config" overrides
 * them per field, only when non-blank. A field the user emptied although the
 * build has a value for it (the Cloudflare switch turned off, the map URL
 * cleared) is stored as the [CLEARED] mark, so "none" can be saved too and a
 * Save never silently restores a value the form had removed. Nothing is
 * written until [save], so an owner whose values come from secrets.properties
 * keeps reading the APK's values and a rebuild with a changed file takes
 * effect with no phone action. Both prefs files are excluded from Auto Backup
 * and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml).
 */
class RelayConfigStore(private val defaults: RelayConfig, private val backing: Backing) {

    /** Where the overrides live. Values are strings keyed by [KEYS]. */
    interface Backing {
        fun read(): Map<String, String>
        fun write(values: Map<String, String>)
        fun clear()
    }

    /** For tests and for the window before [init] runs. */
    class InMemoryBacking(initial: Map<String, String> = emptyMap()) : Backing {
        private var values: Map<String, String> = initial.toMap()
        override fun read(): Map<String, String> = values
        override fun write(values: Map<String, String>) { this.values = values.toMap() }
        override fun clear() { values = emptyMap() }
    }

    private class PrefsBacking(private val prefs: SharedPreferences) : Backing {
        override fun read(): Map<String, String> =
            KEYS.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }.toMap()

        /** A key absent from [values] (or blank) is removed, so the file holds only real overrides. */
        override fun write(values: Map<String, String>) {
            prefs.edit().also { e ->
                KEYS.forEach { k ->
                    val v = values[k]
                    if (v.isNullOrEmpty()) e.remove(k) else e.putString(k, v)
                }
            }.commit()
        }

        /** Removes this store's [KEYS] only: the feature switches (Features.kt) share the file and stay. */
        override fun clear() { prefs.edit().also { e -> KEYS.forEach { e.remove(it) } }.commit() }
    }

    @Volatile
    var current: RelayConfig = merge(defaults, backing.read())
        private set

    /** True when the build itself carries a usable relay (drives "Reset to build defaults"). */
    val hasBuildDefaults: Boolean get() = defaults.isConfigured

    /**
     * Validates, stores, and swaps [current] atomically. What is validated is
     * the effective config (the typed values over the build defaults), so an
     * invalid config never reaches the interceptors. Throws
     * [IllegalArgumentException] with every problem; then nothing was written.
     *
     * Only what differs from the build is written ([overridesOf]): the owner
     * saving the Settings form with their own values pre-filled stores nothing,
     * so a later rebuild with a changed secrets.properties still takes effect on
     * the phone with no action there. A field that merely equals the build
     * default would otherwise shadow it forever. A field emptied over a build
     * value is written as the [CLEARED] mark and takes effect as "", so what
     * the form showed is what is in force — which also means a blank token or
     * relay URL over the build's is refused by the validator, never quietly
     * replaced by the build's value.
     */
    @Synchronized
    fun save(config: RelayConfig): RelayConfig {
        val typed = RelayConfigValidator.normalized(config)
        val overrides = overridesOf(defaults, typed)
        val effective = RelayConfigValidator.validate(merge(defaults, overrides))
        backing.write(overrides)
        current = effective
        return effective
    }

    /** Back to the build's own values; the stored overrides are deleted (only this store's keys). */
    @Synchronized
    fun reset() {
        backing.clear()
        current = defaults
    }

    companion object {
        const val PREFS_FILE = "relay_config"
        private const val KEY_BASE = "base"
        private const val KEY_WS = "ws"
        private const val KEY_TOKEN = "token"
        private const val KEY_CF_ID = "cf_id"
        private const val KEY_CF_SECRET = "cf_secret"
        private const val KEY_MAP = "map"
        val KEYS = listOf(KEY_BASE, KEY_WS, KEY_TOKEN, KEY_CF_ID, KEY_CF_SECRET, KEY_MAP)

        /**
         * Stored in place of a value the user emptied while the build has one
         * ("∅"); [merge] reads it as "". It can never collide with a typed
         * value: it is not printable ASCII, so the credential rules refuse it,
         * and it is not a URL, so the URL rules do.
         */
        const val CLEARED = "∅"

        @Volatile
        private var global: RelayConfigStore =
            RelayConfigStore(RelayConfig.fromBuildConfig(), InMemoryBacking())

        @Volatile
        private var initialized = false

        /** The process-wide store (its [current] is what Imsg.kt reads). */
        val instance: RelayConfigStore get() = global

        /** The live snapshot. Read it once per request and keep using that value. */
        val current: RelayConfig get() = global.current

        /** Loads the stored overrides. Called once per process by [ImsgApp]; later calls are no-ops. */
        fun init(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                global = RelayConfigStore(RelayConfig.fromBuildConfig(), PrefsBacking(prefs))
                initialized = true
            }
        }

        private fun pick(stored: Map<String, String>, key: String, fallback: String): String {
            val v = stored[key]?.trim().orEmpty()
            return when {
                v == CLEARED -> ""
                v.isNotEmpty() -> v
                else -> fallback
            }
        }

        /** Per-field: a stored non-blank value wins ([CLEARED] meaning ""), otherwise the build default. */
        fun merge(defaults: RelayConfig, stored: Map<String, String>): RelayConfig = RelayConfig(
            base = pick(stored, KEY_BASE, defaults.base),
            wsUrl = pick(stored, KEY_WS, defaults.wsUrl),
            token = pick(stored, KEY_TOKEN, defaults.token),
            cfClientId = pick(stored, KEY_CF_ID, defaults.cfClientId),
            cfClientSecret = pick(stored, KEY_CF_SECRET, defaults.cfClientSecret),
            mapUrl = pick(stored, KEY_MAP, defaults.mapUrl),
        )

        fun toMap(c: RelayConfig): Map<String, String> = mapOf(
            KEY_BASE to c.base, KEY_WS to c.wsUrl, KEY_TOKEN to c.token,
            KEY_CF_ID to c.cfClientId, KEY_CF_SECRET to c.cfClientSecret, KEY_MAP to c.mapUrl,
        )

        /**
         * What [save] writes: [typed]'s fields minus those equal to [defaults]',
         * with a field blank in [typed] but set in [defaults] written as
         * [CLEARED]. The backing holds only genuine differences, and
         * `merge(defaults, overridesOf(defaults, typed)) == typed`.
         */
        fun overridesOf(defaults: RelayConfig, typed: RelayConfig): Map<String, String> {
            val d = toMap(defaults)
            return toMap(typed).mapNotNull { (k, v) ->
                when {
                    v == d[k] -> null
                    v.isEmpty() -> k to CLEARED
                    else -> k to v
                }
            }.toMap()
        }
    }
}
