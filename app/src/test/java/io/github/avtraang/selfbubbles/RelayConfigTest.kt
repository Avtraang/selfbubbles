package io.github.avtraang.selfbubbles

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RelayConfig / RelayConfigValidator / RelayConfigStore (RelayConfig.kt). All
 * values here are made up; the store is driven through an in-memory backing,
 * never SharedPreferences. Assertions on headers check presence or a made-up
 * value, never anything from the build.
 */
class RelayConfigTest {

    /** What an owner's secrets.properties bakes in (stand-in values). */
    private val built = RelayConfig(
        base = "https://relay.example.com",
        wsUrl = "wss://relay.example.com/ws",
        token = "built-token",
        cfClientId = "built-id.access",
        cfClientSecret = "built-secret",
        mapUrl = "https://maps.example.com/family?view=map#start",
    )

    /** A stranger's build: every key absent. */
    private val none = RelayConfig()

    private fun store(defaults: RelayConfig, stored: Map<String, String> = emptyMap()) =
        RelayConfigStore(defaults, RelayConfigStore.InMemoryBacking(stored))

    // ---- defaults ----

    @Test fun noSavedSettings_effectiveEqualsBuildValues() {
        val s = store(built)
        assertEquals(built, s.current)
        assertTrue(s.current.isConfigured)
        assertTrue(s.hasBuildDefaults)
        assertEquals("relay.example.com", s.current.origin?.host)
        assertEquals(443, s.current.origin?.port)
    }

    @Test fun processWideStore_withNothingStored_readsBuildConfig() {
        // No init(context) ran in this JVM, so the global store is the build's values.
        assertEquals(RelayConfig.fromBuildConfig(), RelayConfigStore.current)
        assertEquals(RelayConfig.fromBuildConfig().base, BASE)
        assertEquals(RelayConfig.fromBuildConfig().wsUrl, WS_URL)
        assertEquals(RelayConfig.fromBuildConfig().mapUrl, FAMILY_MAP_URL)
    }

    @Test fun strangerBuild_isUnconfigured_andEmpty() {
        val s = store(none)
        assertEquals(RelayConfig.EMPTY, s.current)
        assertFalse(s.current.isConfigured)
        assertFalse(s.hasBuildDefaults)
        assertNull(s.current.origin)
    }

    // ---- empty config: matches nothing, attaches nothing ----

    @Test fun emptyConfig_matchesNoUrl() {
        val e = RelayConfig.EMPTY
        for (u in listOf(
            "https://relay.example.com/threads", "https://example.com/", "http://192.168.0.10:8000/health",
            "https://", "", "not a url",
        )) {
            assertFalse("should not match: $u", e.isRelayUrl(u))
        }
        assertFalse(e.isRelayUrl("https://relay.example.com/attachment/x".toHttpUrl()))
        assertFalse(e.isRelayPath("https://relay.example.com/attachment/x".toHttpUrl(), "/attachment/"))
        assertFalse(e.hasCfAccess)
    }

    @Test fun emptyConfig_interceptorsAttachNothing_andStripEverything() {
        val auth = relayAuthInterceptorFor { RelayConfig.EMPTY }
        val strip = relayAuthStripInterceptorFor { RelayConfig.EMPTY }
        for (u in listOf("https://relay.example.com/threads", "https://evil.example/x")) {
            assertEquals(0, credentialCount(forwarded(auth, okhttp3.Request.Builder().url(u).build())))
            val b = okhttp3.Request.Builder().url(u)
            CREDENTIAL_HEADERS.forEach { b.header(it, "dummy") }
            assertEquals(0, credentialCount(forwarded(auth, b.build())))
            assertEquals(0, credentialCount(forwarded(strip, b.build())))
        }
    }

    @Test fun tokenOnlyConfig_attachesTheTokenButNoCfPair() {
        val c = RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", "t")
        val r = forwarded(relayAuthInterceptorFor { c }, okhttp3.Request.Builder().url("https://relay.example.com/threads").build())
        // CREDENTIAL_HEADERS (RelayOriginTest.kt) lists token, CF id, CF secret in that order.
        assertEquals(1, credentialCount(r))
        assertNotNull(r.header(CREDENTIAL_HEADERS[0]))
        assertNull(r.header(CREDENTIAL_HEADERS[1]))
        assertNull(r.header(CREDENTIAL_HEADERS[2]))
    }

    // ---- value normalisation ----

    @Test fun base_isTrimmed_andLosesTrailingSlashes() {
        val c = RelayConfig(base = "  https://relay.example.com/  ", token = " t ")
        assertEquals("https://relay.example.com", c.base)
        assertEquals("t", c.token)
        assertEquals("", c.basePath)
        val p = RelayConfig(base = "https://relay.example.com/prefix/")
        assertEquals("https://relay.example.com/prefix", p.base)
        assertEquals("/prefix", p.basePath)
        assertTrue(p.isRelayPath("https://relay.example.com/prefix/attachment/x".toHttpUrl(), "/attachment/"))
        assertFalse(p.isRelayPath("https://relay.example.com/attachment/x".toHttpUrl(), "/attachment/"))
    }

    @Test fun origin_isNullForAnythingButHttps() {
        for (bad in listOf("http://relay.example.com", "ws://relay.example.com", "relay.example.com", "", "https://")) {
            assertNull("origin should be null for: $bad", RelayConfig(base = bad).origin)
        }
        assertNotNull(RelayConfig(base = "https://relay.example.com:8443").origin)
        assertEquals(8443, RelayConfig(base = "https://relay.example.com:8443").origin?.port)
    }

    @Test fun toString_neverShowsACredential() {
        val s = built.toString()
        assertFalse(s.contains("built-token"))
        assertFalse(s.contains("built-secret"))
        assertFalse(s.contains("built-id"))
        assertTrue(s.contains("relay.example.com"))
    }

    // ---- validation table (mirrors the guard in app/build.gradle.kts) ----

    private fun problemsOf(
        base: String = "https://relay.example.com",
        ws: String = "wss://relay.example.com/ws",
        token: String = "t",
        cfId: String = "",
        cfSecret: String = "",
        map: String = "",
    ) = RelayConfigValidator.problems(RelayConfig(base, ws, token, cfId, cfSecret, map))

    @Test fun validation_acceptsTheGoodCases() {
        assertEquals(emptyList<String>(), problemsOf())
        assertEquals(emptyList<String>(), RelayConfigValidator.problems(built))
        assertEquals(emptyList<String>(), problemsOf(base = "https://relay.example.com:8443", ws = "wss://relay.example.com:8443/ws"))
        assertEquals(emptyList<String>(), problemsOf(base = "https://Relay.Example.COM", ws = "wss://relay.example.com/ws"))
        assertEquals(emptyList<String>(), problemsOf(base = "https://relay.example.com/prefix", ws = "wss://relay.example.com/prefix/ws"))
        assertEquals(emptyList<String>(), problemsOf(cfId = "id", cfSecret = "secret"))
        assertEquals(emptyList<String>(), problemsOf(map = "https://maps.example.com/f?x=1#frag"))
        // Explicit :443 and the default are the same port.
        assertEquals(emptyList<String>(), problemsOf(base = "https://relay.example.com:443", ws = "wss://relay.example.com/ws"))
    }

    @Test fun validation_table_rejectsEachBrokenRule() {
        val table = listOf(
            // base URL
            Triple("base http", problemsOf(base = "http://relay.example.com"), "Relay URL must be a https://"),
            Triple("base no host", problemsOf(base = "https://"), "Relay URL must be a https://"),
            Triple("base bare host", problemsOf(base = "relay.example.com"), "Relay URL must be a https://"),
            Triple("base empty", problemsOf(base = ""), "Relay URL is required"),
            Triple("base garbage", problemsOf(base = "not a url"), "Relay URL must be a https://"),
            Triple("base userinfo", problemsOf(base = "https://user@relay.example.com"), "user info or a #fragment"),
            Triple("base fragment", problemsOf(base = "https://relay.example.com/#x"), "user info or a #fragment"),
            Triple("base query", problemsOf(base = "https://relay.example.com/?token=x"), "query string"),
            Triple("base bad port", problemsOf(base = "https://relay.example.com:99999"), "invalid port"),
            // WebSocket URL
            Triple("ws plain", problemsOf(ws = "ws://relay.example.com/ws"), "WebSocket URL must be a wss://"),
            Triple("ws https", problemsOf(ws = "https://relay.example.com/ws"), "WebSocket URL must be a wss://"),
            Triple("ws other host", problemsOf(ws = "wss://other.example.com/ws"), "same host and port"),
            Triple("ws look-alike", problemsOf(ws = "wss://relay.example.com.evil.example/ws"), "same host and port"),
            Triple("ws other port", problemsOf(ws = "wss://relay.example.com:8443/ws"), "same host and port"),
            Triple("ws query", problemsOf(ws = "wss://relay.example.com/ws?token=x"), "query string"),
            Triple("ws userinfo", problemsOf(ws = "wss://u@relay.example.com/ws"), "user info or a #fragment"),
            // token and CF pair
            Triple("token blank", problemsOf(token = "   "), "Token is required"),
            Triple("cf id only", problemsOf(cfId = "id"), "both the client id and the client secret"),
            Triple("cf secret only", problemsOf(cfSecret = "s"), "both the client id and the client secret"),
            // header safety: OkHttp refuses anything outside printable ASCII, quoting the value
            Triple("token accent", problemsOf(token = "abcé"), "Token must be plain ASCII"),
            Triple("token zero-width space", problemsOf(token = "abc​def"), "Token must be plain ASCII"),
            Triple("token emoji", problemsOf(token = "tok🔑"), "Token must be plain ASCII"),
            Triple("token tab", problemsOf(token = "a\tb"), "Token must be plain ASCII"),
            Triple("cf id accent", problemsOf(cfId = "idé", cfSecret = "s"), "Cloudflare Access client id must be plain ASCII"),
            Triple("cf secret en-dash", problemsOf(cfId = "id", cfSecret = "s–t"), "Cloudflare Access client secret must be plain ASCII"),
            // map URL
            Triple("map http", problemsOf(map = "http://maps.example.com/f"), "Map URL must be empty or an https://"),
            Triple("map garbage", problemsOf(map = "not a url"), "Map URL must be empty or an https://"),
        )
        for ((name, problems, expected) in table) {
            assertTrue("$name: expected a problem mentioning '$expected', got $problems",
                problems.any { it.contains(expected) })
        }
    }

    @Test fun validation_reportsEveryProblemAtOnce() {
        val p = problemsOf(base = "http://relay.example.com", ws = "ws://x.example.com/ws", token = "", cfId = "id")
        assertEquals(4, p.size)
    }

    @Test fun validation_problemsNameFieldsNeverValues() {
        val p = problemsOf(base = "http://leak.example.com", token = "", cfId = "leak-id")
        assertFalse(p.joinToString().contains("leak"))
        // The header-safety messages too: the value they are about is exactly what must not be shown.
        val q = problemsOf(token = "leak-tokén", cfId = "leak-idé", cfSecret = "leak-sécret")
        assertEquals(3, q.size)
        assertFalse(q.joinToString().contains("leak"))
    }

    @Test fun headerSafety_isPrintableAscii_andTheRuleIsPartOfProblems() {
        assertTrue(RelayConfigValidator.isHeaderSafe("abc-123_~!@#"))
        assertTrue(RelayConfigValidator.isHeaderSafe("with space"))
        assertTrue(RelayConfigValidator.isHeaderSafe(""))
        for (bad in listOf("é", "a\tb", "a\nb", "​", "∅", "\u0000")) {
            assertFalse("should be unsafe: ${bad.map { it.code }}", RelayConfigValidator.isHeaderSafe(bad))
        }
        val c = RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", "tokén", "id", "sécret")
        assertEquals(2, RelayConfigValidator.credentialProblems(c).size)
        assertTrue(RelayConfigValidator.problems(c).containsAll(RelayConfigValidator.credentialProblems(c)))
        assertTrue(runCatching { RelayConfigValidator.validate(c) }.isFailure)
        assertEquals(emptyList<String>(), RelayConfigValidator.credentialProblems(built))
    }

    @Test fun interceptor_refusesAnUnsafeCredential_asAnIOException_withoutTheValue() {
        // OkHttp's own IllegalArgumentException quotes the value and, from an enqueued
        // call's interceptor, is rethrown on the dispatcher thread (a process crash).
        val token = "tok-LEAK-é"
        val secret = "cfs-LEAK-–"
        for (c in listOf(
            RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", token),
            RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", "ok", "id", secret),
        )) {
            val r = runCatching {
                forwarded(relayAuthInterceptorFor { c }, okhttp3.Request.Builder().url("https://relay.example.com/threads").build())
            }
            val e = r.exceptionOrNull()
            assertTrue("should refuse", e is java.io.IOException)
            assertFalse(e is IllegalArgumentException)
            assertNull("no cause: OkHttp's carries the value", e!!.cause)
            assertFalse(e.message.orEmpty().contains("LEAK"))
            assertFalse(e.toString().contains("LEAK"))
            // Off the relay origin nothing is attached, so nothing is refused either.
            assertEquals(0, credentialCount(forwarded(relayAuthInterceptorFor { c }, okhttp3.Request.Builder().url("https://evil.example/x").build())))
        }
    }

    @Test fun normalized_derivesTheWebSocketUrlFromTheBase() {
        fun derived(base: String) = RelayConfigValidator.normalized(RelayConfig(base = base, token = "t")).wsUrl
        assertEquals("wss://relay.example.com/ws", derived("https://relay.example.com"))
        assertEquals("wss://relay.example.com/ws", derived("https://relay.example.com/"))
        assertEquals("wss://relay.example.com:8443/ws", derived("https://relay.example.com:8443"))
        assertEquals("wss://relay.example.com/prefix/ws", derived("https://relay.example.com/prefix/"))
        // An explicit WebSocket URL is kept as typed (the owner's build value stays).
        assertEquals("wss://relay.example.com/socket",
            RelayConfigValidator.normalized(RelayConfig("https://relay.example.com", "wss://relay.example.com/socket", "t")).wsUrl)
        // Nothing to derive from.
        assertEquals("", derived(""))
        assertEquals("", derived("http://relay.example.com"))
    }

    @Test fun validate_returnsTheNormalisedConfig_orThrowsWithEveryProblem() {
        val ok = RelayConfigValidator.validate(RelayConfig(base = "https://relay.example.com", token = "t"))
        assertEquals("wss://relay.example.com/ws", ok.wsUrl)
        assertEquals(emptyList<String>(), RelayConfigValidator.problems(ok))
        val threw = runCatching { RelayConfigValidator.validate(RelayConfig(base = "http://x.example.com")) }
        assertTrue(threw.isFailure)
        assertTrue(threw.exceptionOrNull() is IllegalArgumentException)
        assertTrue(threw.exceptionOrNull()!!.message!!.contains("Token is required"))
    }

    // ---- build values: a missing RELAY_REMOTE_WS is derived, never "" ----

    @Test fun fromBuildValues_withoutAWebSocketUrl_derivesItFromTheBase() {
        val c = RelayConfig.fromBuildValues(
            base = "https://relay.example.com", wsUrl = "", token = "t",
            cfClientId = "", cfClientSecret = "", mapUrl = "",
        )
        assertEquals("wss://relay.example.com/ws", c.wsUrl)
        assertTrue(c.isConfigured)
        assertNotNull(relayWsRequest(c))
        val port = RelayConfig.fromBuildValues("https://relay.example.com:8443/p/", "", "t", "", "", "")
        assertEquals("wss://relay.example.com:8443/p/ws", port.wsUrl)
    }

    @Test fun fromBuildValues_keepsAnExplicitWebSocketUrl_andLeavesAStrangerEmpty() {
        val owner = RelayConfig.fromBuildValues(built.base, built.wsUrl, built.token, built.cfClientId, built.cfClientSecret, built.mapUrl)
        assertEquals(built, owner)
        val explicit = RelayConfig.fromBuildValues("https://relay.example.com", "wss://relay.example.com/socket", "t", "", "", "")
        assertEquals("wss://relay.example.com/socket", explicit.wsUrl)
        val stranger = RelayConfig.fromBuildValues("", "", "", "", "", "")
        assertEquals(RelayConfig.EMPTY, stranger)
        assertEquals("", stranger.wsUrl)
    }

    @Test fun fromBuildConfig_thisBuild_hasAWebSocketUrlWheneverItHasABase() {
        // Holds for the owner's build (explicit or derived) and for a stranger's (both "").
        val c = RelayConfig.fromBuildConfig()
        assertEquals(c.base.isNotEmpty(), c.wsUrl.isNotEmpty())
        assertEquals(c.isConfigured, relayWsRequest(c) != null)
    }

    // ---- the WebSocket upgrade request: null means "stay idle", never a throw ----

    @Test fun wsRequest_emptyConfig_isNull_notAnException() {
        // The stranger's first launch: no secrets.properties, nothing saved yet.
        assertNull(relayWsRequest(RelayConfig.EMPTY))
        assertNull(relayWsRequest(RelayConfig(base = "", wsUrl = "", token = "")))
    }

    @Test fun wsRequest_baseAndTokenButNoWebSocketUrl_isNull() {
        // The owner-with-RELAY_REMOTE_BASE-only shape, before derivation fixed it:
        // "" must never reach Request.Builder.url().
        assertNull(relayWsRequest(RelayConfig(base = "https://relay.example.com", wsUrl = "", token = "t")))
    }

    @Test fun wsRequest_withoutAToken_isNull() {
        assertNull(relayWsRequest(RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", "")))
        assertNull(relayWsRequest(RelayConfig("https://relay.example.com", "wss://relay.example.com/ws", "   ")))
    }

    @Test fun wsRequest_unparseableWebSocketUrl_isNull_notAnException() {
        for (bad in listOf("not a url", "wss://", "wss://relay.example.com:99999/ws", " ", "/ws", "relay.example.com/ws")) {
            val r = runCatching { relayWsRequest(RelayConfig("https://relay.example.com", bad, "t")) }
            assertTrue("should not throw for: $bad", r.isSuccess)
            assertNull("should be null for: $bad", r.getOrNull())
        }
    }

    @Test fun wsRequest_webSocketUrlOffTheRelayOrigin_isNull() {
        val base = "https://relay.example.com"
        for (bad in listOf(
            "wss://other.example.com/ws",
            "wss://relay.example.com.evil.example/ws",
            "wss://relay.example.com:8443/ws",
            "ws://relay.example.com/ws",            // cleartext: not the https origin
            "wss://relay.example.com@evil.example/ws",
        )) {
            assertNull("should refuse: $bad", relayWsRequest(RelayConfig(base, bad, "t")))
        }
    }

    @Test fun wsRequest_configuredRelay_isAGetOnTheRelayOriginWithNoCredentialsInTheUrl() {
        val r = relayWsRequest(built)
        assertNotNull(r)
        r!!
        assertEquals("GET", r.method)
        assertEquals("https", r.url.scheme)   // OkHttp maps wss:// to https:// for the upgrade
        assertEquals("relay.example.com", r.url.host)
        assertEquals(443, r.url.port)
        assertEquals("/ws", r.url.encodedPath)
        assertNull(r.url.encodedQuery)
        assertTrue(r.url.username.isEmpty() && r.url.password.isEmpty())
        assertTrue(built.isRelayUrl(r.url))
        assertEquals(0, credentialCount(r))   // headers come from the interceptor, never the request
        val prefixed = relayWsRequest(RelayConfig("https://relay.example.com:8443/prefix", "wss://relay.example.com:8443/prefix/ws", "t"))
        assertNotNull(prefixed)
        assertEquals(8443, prefixed!!.url.port)
    }

    @Test fun wsRequest_isBuiltFromTheSnapshotItWasGiven_notTheLiveStore() {
        val s = store(built)
        val snapshot = s.current
        s.save(RelayConfig(base = "https://new.example.org", token = "new"))
        assertEquals("relay.example.com", relayWsRequest(snapshot)!!.url.host)
        assertEquals("new.example.org", relayWsRequest(s.current)!!.url.host)
    }

    @Test fun wsRequest_afterSaveOnAStrangerBuild_becomesConnectable() {
        // Stranger: idle before Save, a real request after it; Reset goes back to idle.
        val s = store(none)
        assertNull(relayWsRequest(s.current))
        s.save(RelayConfig(base = "https://my.example.org", token = "mine"))
        val r = relayWsRequest(s.current)
        assertNotNull(r)
        assertEquals("my.example.org", r!!.url.host)
        s.reset()
        assertNull(relayWsRequest(s.current))
    }

    // ---- the store ----

    @Test fun save_storesAndSwaps_strangerSeesExactlyWhatWasSaved() {
        val backing = RelayConfigStore.InMemoryBacking()
        val s = RelayConfigStore(none, backing)
        val typed = RelayConfig(base = "https://my.example.org", token = "mine")
        val effective = s.save(typed)
        assertSame(effective, s.current)
        assertEquals("https://my.example.org", s.current.base)
        assertEquals("wss://my.example.org/ws", s.current.wsUrl)
        assertEquals("mine", s.current.token)
        assertFalse(s.current.hasCfAccess)
        assertEquals("", s.current.mapUrl)
        assertTrue(s.current.isConfigured)
        // What was written is what was typed (plus the derived WebSocket URL).
        assertEquals("wss://my.example.org/ws", backing.read()["ws"])
        // A fresh store over the same backing reads the same effective config.
        assertEquals(s.current, RelayConfigStore(none, backing).current)
    }

    @Test fun save_overridesPerField_blanksOverBuildValuesClearThem() {
        // What the form hands over is the whole config as shown: a field left blank
        // where the build has a value means "none", never "the build's".
        val backing = RelayConfigStore.InMemoryBacking()
        val s = RelayConfigStore(built, backing)
        s.save(RelayConfig(base = "https://new.example.org", token = "new-token"))
        assertEquals("https://new.example.org", s.current.base)
        assertEquals("wss://new.example.org/ws", s.current.wsUrl)   // derived from the typed base, not the build's host
        assertEquals("new-token", s.current.token)
        assertEquals("", s.current.cfClientId)
        assertEquals("", s.current.cfClientSecret)
        assertFalse(s.current.hasCfAccess)
        assertEquals("", s.current.mapUrl)
        assertEquals(RelayConfigStore.CLEARED, backing.read()["cf_id"])
        assertEquals(RelayConfigStore.CLEARED, backing.read()["map"])
        // A fresh store over the same backing reads the same thing.
        assertEquals(s.current, RelayConfigStore(built, backing).current)
    }

    @Test fun save_unchangedOwnerValues_stillWorks() {
        val s = store(built)
        s.save(built)
        assertEquals(built, s.current)
        assertTrue(s.current.isConfigured)
    }

    @Test fun save_invalid_throwsAndWritesNothing_currentUnchanged() {
        val backing = RelayConfigStore.InMemoryBacking()
        val s = RelayConfigStore(built, backing)
        val before = s.current
        for (bad in listOf(
            RelayConfig(base = "http://relay.example.com", token = "t"),
            RelayConfig(base = "https://relay.example.com", wsUrl = "wss://other.example.com/ws", token = "t"),
            RelayConfig(base = "https://relay.example.com", token = "t", mapUrl = "http://maps.example.com/f"),
        )) {
            val r = runCatching { s.save(bad) }
            assertTrue("should refuse: $bad", r.isFailure)
            assertTrue(r.exceptionOrNull() is IllegalArgumentException)
            assertEquals(emptyMap<String, String>(), backing.read())
            assertSame(before, s.current)
        }
    }

    @Test fun save_strangerWithBlankToken_isRefused() {
        val s = store(none)
        val r = runCatching { s.save(RelayConfig(base = "https://my.example.org")) }
        assertTrue(r.isFailure)
        assertFalse(s.current.isConfigured)
    }

    @Test fun save_ownerWithBlankToken_isRefused_notQuietlyTheBuildToken() {
        // A blank token over the build's would clear it, and a config without a
        // token is invalid: refused, nothing written, the build token still in force.
        val backing = RelayConfigStore.InMemoryBacking()
        val s = RelayConfigStore(built, backing)
        val r = runCatching { s.save(RelayConfig(base = built.base, token = "")) }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Token is required"))
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built.token, s.current.token)
    }

    @Test fun save_cfIdOnly_isRefusedForAStrangerAndForAnOwner() {
        val stranger = store(none)
        assertTrue(runCatching { stranger.save(RelayConfig(base = "https://x.example.org", token = "t", cfClientId = "id")) }.isFailure)
        // The owner's blank secret clears the build's, which leaves an id-only pair: refused too,
        // never a new id silently paired with the build's old secret.
        val owner = store(built)
        val r = runCatching { owner.save(RelayConfig(base = built.base, token = "t", cfClientId = "new-id")) }
        assertTrue(r.isFailure)
        assertEquals(built, owner.current)
    }

    @Test fun save_wsOnAnotherHostThanTheTypedBase_isRefused() {
        // Typed base on a new host while the WebSocket URL names the old one.
        val s = store(built)
        val r = runCatching { s.save(RelayConfig(base = "https://new.example.org", wsUrl = built.wsUrl, token = "t")) }
        assertTrue(r.isFailure)
        assertEquals(built, s.current)
    }

    @Test fun reset_returnsToBuildValues_andClearsTheBacking() {
        val backing = RelayConfigStore.InMemoryBacking()
        val s = RelayConfigStore(built, backing)
        s.save(RelayConfig(base = "https://new.example.org", token = "new"))
        assertFalse(backing.read().isEmpty())
        s.reset()
        assertEquals(built, s.current)
        assertTrue(backing.read().isEmpty())
    }

    @Test fun storedBlankOrWhitespace_doesNotOverride() {
        val s = store(built, mapOf("base" to "  ", "token" to "", "map" to "https://other.example.com/m"))
        assertEquals(built.base, s.current.base)
        assertEquals(built.token, s.current.token)
        assertEquals("https://other.example.com/m", s.current.mapUrl)
    }

    // ---- snapshot immutability ----

    @Test fun snapshot_takenBeforeSave_keepsItsValues() {
        val s = store(built)
        val snapshot = s.current
        val snapshotOrigin = snapshot.origin
        s.save(RelayConfig(base = "https://new.example.org", token = "new"))
        assertNotSame(snapshot, s.current)
        assertEquals(built, snapshot)
        assertSame(snapshotOrigin, snapshot.origin)
        assertTrue(snapshot.isRelayUrl("https://relay.example.com/threads"))
        assertFalse(snapshot.isRelayUrl("https://new.example.org/threads"))
        assertTrue(s.current.isRelayUrl("https://new.example.org/threads"))
        assertFalse(s.current.isRelayUrl("https://relay.example.com/threads"))
    }

    @Test fun copy_returnsANewValue_sourceUntouched() {
        val c = built.copy(token = "other")
        assertEquals("built-token", built.token)
        assertEquals("other", c.token)
        assertEquals(built.base, c.base)
        assertFalse(c == built)
    }

    @Test fun equality_isByValue() {
        assertEquals(built, built.copy())
        assertEquals(built.hashCode(), built.copy().hashCode())
        assertFalse(built == built.copy(cfClientSecret = "x"))
    }
}
