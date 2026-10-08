package io.github.avtraang.selfbubbles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map WebView's header injection (RelayWebAssets, MapScreen.kt) may proxy a
 * request through the credentialed OkHttp client only when it is a GET for the
 * exact relay origin. The owner's map lives on another host, so for them the
 * decision is false for every request the page makes — zero behaviour change.
 * Made-up hosts and tokens only.
 */
class RelayWebAssetsTest {

    private val relay = RelayConfig(
        base = "https://relay.example.test",
        token = "test-token",
        mapUrl = "https://home.example.test/local/family-map.html?view=map#start",
    )

    private fun proxy(url: String, method: String = "GET", config: RelayConfig = relay) =
        RelayWebAssets.shouldProxy(url, method, config)

    @Test fun getOnTheRelayOrigin_isProxied() {
        assertTrue(proxy("https://relay.example.test/map"))
        assertTrue(proxy("https://relay.example.test/map?scheme=dark"))
        assertTrue(proxy("https://relay.example.test/locations"))
        assertTrue(proxy("https://relay.example.test:443/locations"))
        assertTrue(proxy("https://RELAY.example.test/map"))
    }

    /** The page and its data, nothing else: least of all a path that sends a message. */
    @Test fun otherRelayPaths_areNotProxied() {
        assertFalse(proxy("https://relay.example.test/v/prepare?q=text%205550100%20hi"))
        assertFalse(proxy("https://relay.example.test/v/confirm?a=yes"))
        assertFalse(proxy("https://relay.example.test/assistant/confirm"))
        assertFalse(proxy("https://relay.example.test/threads"))
        assertFalse(proxy("https://relay.example.test/static/map.js"))
        assertFalse(proxy("https://relay.example.test/"))
    }

    @Test fun theOwnersMapOnAnotherHost_isNotProxied() {
        assertFalse(proxy(relay.mapUrl))
        assertFalse(proxy("https://home.example.test/local/family-map.html"))
        assertFalse(proxy("https://home.example.test/api/locations"))
    }

    @Test fun theRelayHostOnAnotherPort_isNotProxied() {
        assertFalse(proxy("https://relay.example.test:8443/map"))
        assertFalse(proxy("https://relay.example.test:444/locations"))
    }

    @Test fun aRelayOnAPort_matchesThatPortOnly() {
        val onPort = RelayConfig(base = "https://relay.example.test:8443", token = "test-token")
        assertTrue(proxy("https://relay.example.test:8443/map", config = onPort))
        assertFalse(proxy("https://relay.example.test/map", config = onPort))
        assertFalse(proxy("https://relay.example.test:443/map", config = onPort))
    }

    @Test fun lookAlikeHosts_areNotProxied() {
        assertFalse(proxy("https://relay.example.test.evil.example/map"))
        assertFalse(proxy("https://evilrelay.example.test/map"))
        assertFalse(proxy("https://relay.example.testing/map"))
        assertFalse(proxy("https://relay.example.test@evil.example/map"))
    }

    @Test fun cdns_areNotProxied() {
        // The map page's script, tile and font hosts: anything that is not the relay origin.
        assertFalse(proxy("https://cdn.mapkit.example.test/mk/5.x.x/mapkit.js"))
        assertFalse(proxy("https://cdn.mapkit.example.test/ti/tile?x=1&y=2&z=3"))
        assertFalse(proxy("https://tiles.maps.example.test/tile.vf"))
        assertFalse(proxy("https://fonts.example.test/font.woff2"))
    }

    @Test fun requestHeaders_conditionalAndEncodingAreNotForwarded() {
        // Neither a 304 nor an encoded body can be handed to the WebView in a WebResourceResponse.
        assertFalse(RelayWebAssets.forwardsRequestHeader("Accept-Encoding"))
        assertFalse(RelayWebAssets.forwardsRequestHeader("accept-encoding"))
        assertFalse(RelayWebAssets.forwardsRequestHeader("If-None-Match"))
        assertFalse(RelayWebAssets.forwardsRequestHeader("if-modified-since"))
        assertTrue(RelayWebAssets.forwardsRequestHeader("Accept"))
        assertTrue(RelayWebAssets.forwardsRequestHeader("User-Agent"))
        assertTrue(RelayWebAssets.forwardsRequestHeader("Accept-Language"))
    }

    @Test fun nonGet_isNeverProxied() {
        assertFalse(proxy("https://relay.example.test/locations", method = "POST"))
        assertFalse(proxy("https://relay.example.test/locations", method = "HEAD"))
        assertFalse(proxy("https://relay.example.test/locations", method = "OPTIONS"))
        assertFalse(proxy("https://relay.example.test/locations", method = "get"))
        assertFalse(proxy("https://relay.example.test/locations", method = ""))
    }

    @Test fun otherSchemes_areNotProxied() {
        assertFalse(proxy("http://relay.example.test/map"))
        assertFalse(proxy("wss://relay.example.test/ws"))
        assertFalse(proxy("file:///android_asset/map.html"))
        assertFalse(proxy("not a url"))
    }

    @Test fun emptyConfig_proxiesNothing() {
        assertFalse(proxy("https://relay.example.test/map", config = RelayConfig.EMPTY))
        assertFalse(proxy("https://home.example.test/local/family-map.html", config = RelayConfig.EMPTY))
    }
}
