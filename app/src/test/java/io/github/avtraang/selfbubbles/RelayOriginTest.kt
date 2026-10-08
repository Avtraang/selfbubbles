package io.github.avtraang.selfbubbles

import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The relay token and the Cloudflare Access pair may only ever go to the exact
 * relay origin. These tests use an injected, made-up origin for the comparison
 * logic, and the real interceptors with a fake chain for the header behaviour
 * (asserting presence/absence only — never a credential value).
 */
class RelayOriginTest {
    private val origin = RelayOrigin.parse("https://relay.example.com")

    @Test fun exactOrigin_isRelay() {
        assertTrue(origin.matches("https://relay.example.com"))
        assertTrue(origin.matches("https://relay.example.com/"))
        assertTrue(origin.matches("https://relay.example.com/attachment/abc?f=jpg"))
        assertTrue(origin.matches("https://relay.example.com:443/threads"))
        assertTrue(origin.matches("https://relay.example.com/threads".toHttpUrl()))
    }

    @Test fun mixedCaseHost_isRelay() {
        assertTrue(origin.matches("https://Relay.Example.COM/threads"))
        assertTrue(origin.matches("HTTPS://RELAY.EXAMPLE.COM/threads"))
        assertTrue(RelayOrigin("RELAY.example.com", 443).matches("https://relay.example.com/"))
    }

    @Test fun lookAlikeSuffixHost_isNotRelay() {
        assertFalse(origin.matches("https://relay.example.com.evil.example/threads"))
        assertFalse(origin.matches("https://relay.example.com.evil.example"))
    }

    @Test fun lookAlikePrefixHost_isNotRelay() {
        assertFalse(origin.matches("https://evilrelay.example.com/threads"))
        assertFalse(origin.matches("https://evil.relay.example.com/threads"))
        assertFalse(origin.matches("https://relay.example.community/threads"))
        assertFalse(origin.matches("https://relay.example.co/threads"))
        assertFalse(origin.matches("https://example.com/threads"))
    }

    @Test fun userinfoTrick_isNotRelay() {
        assertFalse(origin.matches("https://relay.example.com@evil.example/"))
        assertFalse(origin.matches("https://relay.example.com:443@evil.example/"))
        assertFalse(origin.matches("https://relay.example.com%2F@evil.example/"))
    }

    @Test fun backslashTrick_followsWhereOkHttpWouldActuallyConnect() {
        // OkHttp (like browsers) reads '\\' as '/', so this URL really is a
        // request to relay.example.com with path "/@evil.example/". The verdict
        // and the connection come from the same parser, so they cannot disagree.
        val u = "https://relay.example.com\\@evil.example/".toHttpUrl()
        assertEquals("relay.example.com", u.host)
        assertTrue(origin.matches(u))
        assertFalse(origin.matches("https://evil.example\\@relay.example.com/"))
    }

    @Test fun differentPort_isNotRelay() {
        assertFalse(origin.matches("https://relay.example.com:8443/threads"))
        assertFalse(origin.matches("https://relay.example.com:80/threads"))
        val custom = RelayOrigin.parse("https://relay.example.com:8443")
        assertTrue(custom.matches("https://relay.example.com:8443/threads"))
        assertFalse(custom.matches("https://relay.example.com/threads"))
    }

    @Test fun httpScheme_isNotRelay() {
        assertFalse(origin.matches("http://relay.example.com/threads"))
        assertFalse(origin.matches("http://relay.example.com:443/threads"))
    }

    @Test fun trailingDotHost_isNotRelay() {
        assertFalse(origin.matches("https://relay.example.com./threads"))
    }

    @Test fun idnLookAlike_isNotRelay() {
        // Cyrillic 'е' (U+0435) in place of the Latin 'e' in "relay".
        assertFalse(origin.matches("https://rеlay.example.com/threads"))
        // Its punycode form, and a fullwidth-dot variant of another host.
        assertFalse(origin.matches("https://xn--rlay-8ve.example.com/threads"))
        assertFalse(origin.matches("https://relay.example.com。evil.example/threads"))
    }

    @Test fun unparseable_isNotRelay() {
        for (s in listOf(
            "", " ", "relay.example.com", "//relay.example.com/x", "/attachment/abc",
            "https://", "https:///threads", "wss://relay.example.com/ws",
            "ftp://relay.example.com/", "file:///relay.example.com", "content://relay.example.com/x",
            "javascript:alert(1)", "https://relay.example.com:99999/", "not a url",
        )) {
            assertFalse("should not match: $s", origin.matches(s))
        }
    }

    @Test fun relayHostOnlyInPathQueryOrFragment_isNotRelay() {
        assertFalse(origin.matches("https://evil.example/relay.example.com/threads"))
        assertFalse(origin.matches("https://evil.example/https://relay.example.com/threads"))
        assertFalse(origin.matches("https://evil.example/?u=https://relay.example.com/"))
        assertFalse(origin.matches("https://evil.example/#https://relay.example.com/"))
        assertFalse(origin.matches("https://evil.example#@relay.example.com/"))
    }

    @Test fun parse_refusesNonHttpsBase() {
        for (bad in listOf("http://relay.example.com", "ws://relay.example.com", "relay.example.com", "")) {
            val threw = runCatching { RelayOrigin.parse(bad) }.isFailure
            assertTrue("parse should refuse: $bad", threw)
        }
    }

    // ---- the real interceptors, driven through a fake chain ----

    private val credentialHeaders = CREDENTIAL_HEADERS

    // Made-up relays for the interceptor tests, so they run the same whether the
    // build carries an owner's values or none at all (a stranger's build).
    private val originA = RelayConfig("https://a.example.com", "wss://a.example.com/ws", "token-a", "id-a", "secret-a")
    private val originB = RelayConfig("https://b.example.com", "wss://b.example.com/ws", "token-b", "id-b", "secret-b")
    private val authA = relayAuthInterceptorFor { originA }
    private val stripA = relayAuthStripInterceptorFor { originA }

    private val foreignUrls: List<String>
        get() {
            val host = originA.origin!!.host
            return listOf(
                "https://upload.wikimedia.org/x.png",
                "https://$host.evil.example/attachment/x",
                "https://$host@evil.example/attachment/x",
                "http://$host/attachment/x",
                "https://$host:8443/attachment/x",
                "http://192.168.0.10:8000/health",
            )
        }

    @Test fun authInterceptor_addsCredentialsOnlyForRelayOrigin() {
        val relay = forwarded(authA, Request.Builder().url("${originA.base}/threads").build())
        assertEquals(3, credentialCount(relay))
        for (u in foreignUrls) {
            val r = forwarded(authA, Request.Builder().url(u).build())
            assertEquals(0, credentialCount(r))
        }
    }

    @Test fun authInterceptor_dropsCallerSuppliedCredentialsForForeignHost() {
        val b = Request.Builder().url("https://evil.example/x")
        credentialHeaders.forEach { b.header(it, "dummy") }
        assertEquals(0, credentialCount(forwarded(authA, b.build())))
    }

    @Test fun stripInterceptor_removesCredentialsOnForeignHop_keepsThemOnRelayHop() {
        // What a redirect hop looks like: the headers added for the relay are
        // still on the follow-up request to another host.
        for (u in foreignUrls) {
            val b = Request.Builder().url(u).header("User-Agent", "ua")
            credentialHeaders.forEach { b.header(it, "dummy") }
            val r = forwarded(stripA, b.build())
            assertEquals(0, credentialCount(r))
            assertEquals("ua", r.header("User-Agent"))
        }
        val b = Request.Builder().url("${originA.base}/attachment/x")
        credentialHeaders.forEach { b.header(it, "dummy") }
        assertEquals(3, credentialCount(forwarded(stripA, b.build())))
    }

    @Test fun stringOverload_rejectsGarbage() {
        assertFalse(isRelayUrl("/attachment/x"))
        assertFalse(isRelayUrl("not a url"))
        assertFalse(originA.isRelayUrl("/attachment/x"))
        assertTrue(originA.isRelayUrl("${originA.base}/attachment/x"))
    }

    // ---- the real, process-wide interceptors over the build's own values ----
    // (skipped in a build with no relay baked in; the owner's build runs them)

    private fun assumeBuildHasARelay() = assumeTrue(
        "no relay values in this build; skipped", RelayConfigStore.current.isConfigured)

    @Test fun realInterceptors_areWiredToTheLiveConfig() {
        assumeBuildHasARelay()
        val relay = forwarded(relayAuthInterceptor, Request.Builder().url("$BASE/threads").build())
        assertEquals(if (RelayConfigStore.current.hasCfAccess) 3 else 1, credentialCount(relay))
        assertEquals(0, credentialCount(forwarded(relayAuthInterceptor, Request.Builder().url("https://evil.example/x").build())))
        val b = Request.Builder().url("https://evil.example/x")
        credentialHeaders.forEach { b.header(it, "dummy") }
        assertEquals(0, credentialCount(forwarded(relayAuthStripInterceptor, b.build())))
        assertTrue(isRelayUrl("$BASE/attachment/x"))
    }

    @Test fun webSocketUrl_isOnRelayOrigin_andCarriesNoQuery() {
        assumeBuildHasARelay()
        // OkHttp maps wss:// to https:// for the upgrade request.
        val url = Request.Builder().url(WS_URL).build().url
        assertTrue(isRelayUrl(url))
        assertTrue(url.encodedQuery == null)
        assertTrue(url.username.isEmpty() && url.password.isEmpty())
    }

    // ---- the config changes while a request is in flight ----


    @Test fun originSwapMidFlight_aRequestStartedUnderA_neverSendsHeadersToB() {
        var live = originA
        val auth = relayAuthInterceptorFor { live }
        val strip = relayAuthStripInterceptorFor { live }

        // The call starts under A: headers for A, and A's snapshot pinned on the request.
        val started = forwarded(auth, Request.Builder().url("https://a.example.com/threads").build())
        assertEquals(3, credentialCount(started))
        assertEquals("token-a", started.header(credentialHeaders[0]))

        // Save happens while the call is in flight.
        live = originB

        // A answers with a redirect to B: OkHttp rebuilds the request from `started`,
        // so the hop carries A's headers and A's snapshot. B is not A -> stripped.
        val hopToB = forwarded(strip, started.newBuilder().url("https://b.example.com/threads").build())
        assertEquals(0, credentialCount(hopToB))

        // A hop that stays on A (the origin those headers were issued for) keeps them,
        // even though A is no longer the live origin.
        val hopOnA = forwarded(strip, started.newBuilder().url("https://a.example.com/threads?page=2").build())
        assertEquals(3, credentialCount(hopOnA))

        // A new request under B gets B's headers, never A's.
        val fresh = forwarded(auth, Request.Builder().url("https://b.example.com/threads").build())
        assertEquals(3, credentialCount(fresh))
        assertEquals("token-b", fresh.header(credentialHeaders[0]))
        assertEquals("id-b", fresh.header(credentialHeaders[1]))

        // ...and a new request to A, now a foreign host, gets nothing.
        assertEquals(0, credentialCount(forwarded(auth, Request.Builder().url("https://a.example.com/threads").build())))
    }

    @Test fun originSwapMidFlight_hopToTheNewOriginWithOldHeaders_isStripped() {
        // The worst case: the old relay redirects straight to whatever the new origin
        // is. Without the pinned snapshot, the live config would call B "the relay"
        // and let A's token and Cloudflare pair through.
        var live = originA
        val auth = relayAuthInterceptorFor { live }
        val strip = relayAuthStripInterceptorFor { live }
        val started = forwarded(auth, Request.Builder().url("https://a.example.com/attachment/x").build())
        live = originB
        val b = started.newBuilder().url("https://b.example.com/attachment/x")
        val hop = forwarded(strip, b.build())
        assertEquals(0, credentialCount(hop))
        assertTrue(live.isRelayUrl(hop.url))   // the live config alone would have said yes
    }

    @Test fun stripWithoutASnapshot_fallsBackToTheLiveConfig() {
        // A request that never went through the auth interceptor (no tag) is judged
        // by the live origin, exactly as before.
        val strip = relayAuthStripInterceptorFor { originA }
        val onA = Request.Builder().url("https://a.example.com/x")
        credentialHeaders.forEach { onA.header(it, "dummy") }
        assertEquals(3, credentialCount(forwarded(strip, onA.build())))
        val onB = Request.Builder().url("https://b.example.com/x")
        credentialHeaders.forEach { onB.header(it, "dummy") }
        assertEquals(0, credentialCount(forwarded(strip, onB.build())))
    }

    @Test fun authInterceptor_usesOneSnapshotForOriginCheckAndHeaders() {
        // The supplier is consulted exactly once per request, so the origin check and
        // the header values can never come from two different configs.
        var calls = 0
        val auth = relayAuthInterceptorFor { calls++; originA }
        forwarded(auth, Request.Builder().url("https://a.example.com/threads").build())
        assertEquals(1, calls)
        forwarded(auth, Request.Builder().url("https://elsewhere.example/x").build())
        assertEquals(2, calls)
    }
}

/** The three header names that may only ever travel to the relay origin. */
internal val CREDENTIAL_HEADERS = listOf("X-Imsg-Token", "CF-Access-Client-Id", "CF-Access-Client-Secret")

/** Runs [interceptor] over [request] and returns the request it forwarded. */
internal fun forwarded(interceptor: Interceptor, request: Request): Request {
    var seen: Request? = null
    interceptor.intercept(object : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            seen = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    })
    return seen!!
}

internal fun credentialCount(r: Request): Int = CREDENTIAL_HEADERS.count { r.header(it) != null }
