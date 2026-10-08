package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.runBlocking

/**
 * The "Test connection" verdicts (RelayProbe.kt), from the `/health` contract:
 * middleware-exempt, never 401; `{"ok":true}` without a valid token; `cursor`
 * (plus `engines`/`features`/`protocol` on newer relays) with one; HTML 403 or
 * a 302 from Cloudflare Access in front of it. Pure classification only — no
 * network. Credentials in here are made up.
 */
class RelayProbeTest {

    private val host = "relay.example.test"

    private fun verdict(code: Int, body: String, contentType: String? = "application/json") =
        RelayProbe.classify(code, contentType, body)

    // ---- responses ----

    @Test fun fullHealth_isConnectedWithProtocolEnginesAndFeatures() {
        val o = verdict(
            200,
            """{"ok":true,"cursor":12345,"contacts":42,"self":"+15550100","bb_reachable":true,
               "engines":["bluebubbles","applescript"],"features":{"facetime":true,"map":false,"translate":true},
               "protocol":1}""",
        )
        assertTrue(o is ProbeOutcome.Connected)
        o as ProbeOutcome.Connected
        assertEquals(1, o.protocol)
        assertEquals(listOf("bluebubbles", "applescript"), o.engines)
        assertEquals(mapOf("facetime" to true, "map" to false, "translate" to true), o.features)
        assertFalse(o.outdated)
        assertEquals("Connected · relay protocol 1 · engines: bluebubbles, applescript · features: facetime, translate", o.message)
    }

    @Test fun olderRelay_cursorOnly_isConnectedAtProtocolZero_andOutdated() {
        val o = verdict(200, """{"ok":true,"cursor":7,"contacts":1,"self":"me","bb_reachable":false}""")
        assertTrue(o is ProbeOutcome.Connected)
        o as ProbeOutcome.Connected
        assertEquals(0, o.protocol)
        assertEquals(emptyList<String>(), o.engines)
        assertEquals(emptyMap<String, Boolean>(), o.features)
        assertTrue("an older relay must get the Update line", o.outdated)
        assertEquals("Connected", o.message)   // no protocol / engines / features parts
    }

    @Test fun protocolBelowExpected_isOutdated_andAtOrAboveIsNot() {
        assertTrue((verdict(200, """{"cursor":1,"protocol":0}""") as ProbeOutcome.Connected).outdated)
        assertFalse((verdict(200, """{"cursor":1,"protocol":$RELAY_PROTOCOL_EXPECTED}""") as ProbeOutcome.Connected).outdated)
        assertFalse((verdict(200, """{"cursor":1,"protocol":${RELAY_PROTOCOL_EXPECTED + 5}}""") as ProbeOutcome.Connected).outdated)
    }

    @Test fun okTrueWithoutCursor_isTokenRejected() {
        assertEquals(ProbeOutcome.TokenRejected, verdict(200, """{"ok":true}"""))
        assertEquals(ProbeOutcome.TokenRejected, verdict(200, """{"ok": true}""", "application/json; charset=utf-8"))
        assertEquals("The relay rejected the token", ProbeOutcome.TokenRejected.message)
    }

    @Test fun html403_isAccessRejected() {
        val o = verdict(403, "<!DOCTYPE html><html><body>Forbidden</body></html>", "text/html; charset=utf-8")
        assertEquals(ProbeOutcome.AccessRejected, o)
        assertTrue(o.message.startsWith("Cloudflare Access rejected the request"))
    }

    @Test fun plain403_andHtml200_areAccessRejected() {
        assertEquals(ProbeOutcome.AccessRejected, verdict(403, "forbidden", "text/plain"))
        assertEquals(ProbeOutcome.AccessRejected, verdict(200, "<html><body>Sign in</body></html>", "text/html"))
        // HTML without a content type still reads as HTML.
        assertEquals(ProbeOutcome.AccessRejected, verdict(200, "  <html>", null))
    }

    @Test fun redirect_isAccessRejected() {
        assertEquals(ProbeOutcome.AccessRejected, verdict(302, "", "text/html"))
        assertEquals(ProbeOutcome.AccessRejected, verdict(301, "", null))
        assertEquals(ProbeOutcome.AccessRejected, verdict(307, """{"cursor":1}""", "application/json"))
    }

    @Test fun junkBody_isOther() {
        val o = verdict(200, "not json at all")
        assertTrue(o is ProbeOutcome.Other)
        assertTrue(verdict(200, "") is ProbeOutcome.Other)
        assertTrue(verdict(200, "[1,2,3]") is ProbeOutcome.Other)      // JSON, but not an object
        assertTrue(verdict(500, "boom", "text/plain") is ProbeOutcome.Other)
        assertTrue(verdict(404, "nope", "text/plain").message.contains("404"))
    }

    @Test fun nonSuccessJson_isOther_notATokenVerdict() {
        // A wrong path on a FastAPI relay answers 404 {"detail":"Not Found"}: not "token rejected",
        // and a JSON 500 that happens to carry "cursor" is not "connected" either.
        assertTrue(verdict(404, """{"detail":"Not Found"}""") is ProbeOutcome.Other)
        assertTrue(verdict(500, """{"cursor":1}""") is ProbeOutcome.Other)
        assertTrue(verdict(204, """{"cursor":1}""") is ProbeOutcome.Connected)
    }

    @Test fun malformedOptionalFields_stillConnect() {
        val o = verdict(200, """{"cursor":1,"protocol":"one","engines":"bb","features":[1,2]}""")
        assertTrue(o is ProbeOutcome.Connected)
        o as ProbeOutcome.Connected
        assertEquals(0, o.protocol)
        assertEquals(emptyList<String>(), o.engines)
        assertEquals(emptyMap<String, Boolean>(), o.features)
    }

    // ---- throwables ----

    @Test fun sslFailures_areNoTrustedCert() {
        assertEquals(ProbeOutcome.NoTrustedCert, RelayProbe.classify(SSLHandshakeException("PKIX path building failed"), host))
        assertEquals(ProbeOutcome.NoTrustedCert, RelayProbe.classify(SSLPeerUnverifiedException("Hostname not verified"), host))
        // https:// against a plaintext port: Conscrypt reports it as one of these, not as unreachable.
        assertEquals(ProbeOutcome.NoTrustedCert, RelayProbe.classify(SSLProtocolException("Unable to parse TLS packet header"), host))
        assertEquals(ProbeOutcome.NoTrustedCert, RelayProbe.classify(SSLException("Unable to parse TLS packet header"), host))
        assertEquals("No trusted certificate at this address; the app only speaks HTTPS", ProbeOutcome.NoTrustedCert.message)
    }

    @Test fun connectedMessage_listsAtMostEightNames_thenAnEllipsis() {
        val engines = (1..10).map { "engine$it" }
        val features = (1..10).associate { "feature$it" to true } + mapOf("off" to false)
        val body = """{"cursor":1,"protocol":1,"engines":${engines.joinToString(",", "[", "]") { "\"$it\"" }},
                      "features":{${features.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
        val o = verdict(200, body) as ProbeOutcome.Connected
        assertEquals(engines, o.engines)   // the data is complete
        assertEquals(11, o.features.size)
        val m = o.message
        assertTrue(m.contains("engines: engine1, engine2, engine3, engine4, engine5, engine6, engine7, engine8, …"))
        assertTrue(m.contains("features: feature1, feature2, feature3, feature4, feature5, feature6, feature7, feature8, …"))
        assertFalse(m.contains("engine9"))
        assertFalse(m.contains("feature10"))
        assertTrue(m.length < 260)   // bounded whatever the relay sends; the uncapped line would grow with it
        // Eight exactly: no ellipsis.
        val eight = verdict(200, """{"cursor":1,"engines":${(1..8).map { "e$it" }.joinToString(",", "[", "]") { "\"$it\"" }}}""")
        assertFalse(eight.message.contains("…"))
    }

    // ---- the pre-check: a credential a header cannot carry never reaches the network ----

    @Test fun probe_refusesANonAsciiCredential_beforeBuildingAClient() {
        // No network is involved: the verdict comes back at once, with a fixed message.
        val bad = listOf(
            RelayConfig("https://$host", "", "tok-LEAK-é"),
            RelayConfig("https://$host", "", "ok", "id-LEAK-​", "s"),
            RelayConfig("https://$host", "", "ok", "id", "s-LEAK-–"),
        )
        for (cfg in bad) {
            val o = runBlocking { RelayProbe.probe(cfg) }
            assertTrue("for $cfg", o is ProbeOutcome.Other)
            assertEquals("The token or the Cloudflare values contain characters a header cannot carry", o.message)
            assertFalse(o.message.contains("LEAK"))
        }
        // And a relay URL that is not https is refused the same way (no host to probe).
        val o = runBlocking { RelayProbe.probe(RelayConfig("http://$host", "", "t")) }
        assertTrue(o is ProbeOutcome.Other)
        assertTrue(o.message.startsWith("Relay URL must be"))
    }

    @Test fun networkFailures_areUnreachableWithTheHost() {
        for (t in listOf(UnknownHostException(host), ConnectException("Failed to connect"), SocketTimeoutException("timeout"))) {
            val o = RelayProbe.classify(t, host)
            assertEquals("for ${t.javaClass.simpleName}", ProbeOutcome.Unreachable(host), o)
            assertEquals("Can't reach $host", o.message)
        }
    }

    @Test fun anythingElse_isOther_namedByClassOnly() {
        assertTrue(RelayProbe.classify(IOException("unexpected end of stream"), host) is ProbeOutcome.Other)
        assertTrue(RelayProbe.classify(IllegalStateException("x"), host) is ProbeOutcome.Other)
        assertEquals("Connection failed (IOException)", RelayProbe.classify(IOException(), host).message)
        assertEquals("Connection failed (IOException)", RelayProbe.classify(IOException("unexpected end of stream"), host).message)
        assertEquals(ProbeOutcome.Unreachable(host), RelayProbe.classify(NoRouteToHostException("no route"), host))
    }

    // ---- no message ever carries a credential ----

    @Test fun noOutcomeMessage_containsTheTokenOrTheCfSecret() {
        val token = "tok-SECRET-0123456789abcdef0123456789abcdef"
        val secret = "cfs-SECRET-fedcba9876543210fedcba9876543210"
        val id = "cfid-SECRET.access"
        val cfg = RelayConfig("https://$host", "", token, id, secret)
        val leaks = listOf(token, secret, id)
        // What a hostile or echoing server might answer: the request headers back in the body,
        // in engine and feature names, in an HTML page, in a status line.
        val echoBody = """{"ok":true,"cursor":1,"engines":["$token","bluebubbles"],"features":{"$secret":true,"map":true},
                          "headers":{"X-Imsg-Token":"$token","CF-Access-Client-Secret":"$secret"}}"""
        val outcomes = listOf(
            RelayProbe.classify(200, "application/json", echoBody),
            RelayProbe.classify(200, "application/json", """{"ok":true,"token":"$token"}"""),
            RelayProbe.classify(403, "text/html", "<html>$token $secret</html>"),
            RelayProbe.classify(302, null, token),
            RelayProbe.classify(200, "text/plain", "echo: X-Imsg-Token: $token"),
            RelayProbe.classify(500, "text/plain", token),
            RelayProbe.classify(IOException("header value: $token"), host),
            RelayProbe.classify(IllegalArgumentException("Unexpected char in X-Imsg-Token value: $token"), host),
            RelayProbe.classify(UnknownHostException(token), host),
            RelayProbe.classify(RuntimeException(secret), host),
            // A token the validator refuses for a header: the probe's own pre-check, no network.
            runBlocking { RelayProbe.probe(cfg.copy(token = "$token-é")) },
            runBlocking { RelayProbe.probe(cfg.copy(cfClientSecret = "$secret​")) },
        )
        for (o in outcomes) {
            for (leak in leaks) {
                assertFalse("${o.javaClass.simpleName} leaked a credential", o.message.contains(leak))
                assertFalse("${o.javaClass.simpleName}.toString leaked a credential", o.toString().contains(leak))
            }
        }
        // ...while a genuine engine list still comes through next to the filtered junk.
        val c = outcomes[0] as ProbeOutcome.Connected
        assertEquals(listOf("bluebubbles"), c.engines)
        assertEquals(mapOf("map" to true), c.features)
        assertFalse(cfg.toString().contains(token))
    }

    @Test fun throwableText_isNeverRepeated() {
        val long = "x".repeat(500)
        val o = RelayProbe.classify(IOException(long), host)
        assertFalse(o.message.contains("xxxx"))
        assertTrue(o.message.length < 80)
    }
}
