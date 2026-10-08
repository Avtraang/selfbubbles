package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the family map screen writes to logcat and shows while loading
 * (MapDiagnostics.kt): a URL keeps its scheme, host and path and loses its
 * query string, fragment and user info, and a line the map page printed loses
 * every query string and every value that is named like a credential.
 */
class MapDiagnosticsTest {

    private val secret = "s3cr3tValue0123456789"

    // ---- one URL ----

    @Test fun aUrl_keepsSchemeHostAndPath_only() {
        assertEquals("https://map.example.test/people", safeUrl("https://map.example.test/people?key=$secret#scheme=dark"))
        assertEquals("https://map.example.test/people", safeUrl("https://map.example.test/people#access_token=$secret"))
        assertEquals("wss://map.example.test/live", safeUrl("wss://map.example.test/live?access_token=$secret"))
        assertEquals("https://map.example.test:8443/a/b.js", safeUrl("https://map.example.test:8443/a/b.js"))
    }

    @Test fun userInfo_isDropped() {
        assertEquals("https://map.example.test/map", safeUrl("https://user:$secret@map.example.test/map"))
        assertEquals("https://map.example.test/map", safeUrl("https://$secret@map.example.test/map?x=1"))
        // An @ further along the path is part of the path.
        assertEquals("https://map.example.test/img/pin@2x.png", safeUrl("https://map.example.test/img/pin@2x.png"))
    }

    @Test fun noUrl_isSaidSo_andALongOneIsCapped() {
        assertEquals("(unknown)", safeUrl(null))
        assertEquals("(unknown)", safeUrl("  "))
        assertEquals(240, safeUrl("https://map.example.test/" + "a".repeat(600)).length)
    }

    // ---- a line the page printed ----

    @Test fun whatUsedToPassThrough_noLongerDoes() {
        val lines = listOf(
            // Chromium's own line for a socket that failed.
            "WebSocket connection to 'wss://map.example.test/live?access_token=$secret' failed" to
                "WebSocket connection to 'wss://map.example.test/live' failed",
            "GET ws://map.example.test/live?key=$secret 1006" to "GET ws://map.example.test/live 1006",
            // A relative URL.
            "Failed to load /api/people?key=$secret (403)" to "Failed to load /api/people (403)",
            "fetch(\"/locations?token=$secret&since=5\") rejected" to "fetch(\"/locations\") rejected",
            // A bare pair.
            "auth failed: token=$secret" to "auth failed: token=[redacted]",
            "retrying with access_token=$secret&v=2" to "retrying with access_token=[redacted]&v=2",
            "mapkit init apiKey=$secret" to "mapkit init apiKey=[redacted]",
            "X-Amz-Signature=$secret expired" to "X-Amz-Signature=[redacted] expired",
            // User info in front of the host.
            "Loading https://user:$secret@map.example.test/map?rev=1" to "Loading https://map.example.test/map",
        )
        for ((raw, expected) in lines) {
            val safe = sanitizeDiagnostic(raw)
            assertEquals(raw, expected, safe)
            assertFalse(raw, safe.contains(secret))
        }
    }

    @Test fun whatWasMaskedBefore_stillIs() {
        assertEquals("Authorization: Bearer [redacted] sent", sanitizeDiagnostic("Authorization: Bearer abc.DEF-123_~ sent"))
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJleGFtcGxlIn0.c2lnbmF0dXJlLXBhcnQ"
        assertEquals("token [JWT redacted] rejected", sanitizeDiagnostic("token $jwt rejected"))
        assertEquals(
            "PAGE HTTP 403 — https://map.example.test/map",
            sanitizeDiagnostic("PAGE HTTP 403 — https://map.example.test/map?app_map_rev=20260716-1#scheme=dark"),
        )
    }

    @Test fun ordinaryLines_areLeftAsTheyAre() {
        for (line in listOf(
            "[family-map] 4 people loaded",
            "Probe: ready · mapkit=object · dom=complete · title=Family map · map=390x700 · surface=ok · tiles drawn",
            "Page loaded: https://map.example.test/map · waiting for MapKit",
            "WebView 140.0.0.1 · loading map",
            "RESOURCE ERR -2: net::ERR_NAME_NOT_RESOLVED — https://cdn.apple-mapkit.com/mk/5.x.x/mapkit.js",
        )) assertEquals(line, sanitizeDiagnostic(line))
    }

    @Test fun aLine_isCapped() {
        assertEquals(500, sanitizeDiagnostic("x".repeat(2000)).length)
        assertTrue(sanitizeDiagnostic("").isEmpty())
    }
}
