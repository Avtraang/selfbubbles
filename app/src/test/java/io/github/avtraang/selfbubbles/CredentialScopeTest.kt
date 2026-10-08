package io.github.avtraang.selfbubbles

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which requests carry the relay credentials, and what the image loader may
 * fetch (hardening audit 2026-10-08): an address inside a received link preview
 * that names the relay's own message-sending routes must neither be loaded nor
 * be given the token. Made-up hosts only.
 */
class CredentialScopeTest {

    private val relay = RelayConfig(base = "https://relay.example.test", token = "test-token")
    private val prefixed = RelayConfig(base = "https://host.example.test/relay", token = "test-token")

    private fun attaches(url: String, method: String = "GET", cfg: RelayConfig = relay) =
        attachesCredentials(cfg, method, url.toHttpUrl())

    private fun loads(url: String, method: String = "GET", cfg: RelayConfig = relay) =
        loadsAsImage(cfg, method, url.toHttpUrl())

    @Test fun aGetForARouteThatSends_getsNoCredentials() {
        assertFalse(attaches("https://relay.example.test/v/prepare?q=text%205550100%20hi"))
        assertFalse(attaches("https://relay.example.test/v/confirm?a=yes"))
        assertFalse(attaches("https://relay.example.test/assistant/prepare?query=x"))
        assertFalse(attaches("https://relay.example.test/v/confirm", method = "get"))
        assertFalse(attaches("https://host.example.test/relay/v/confirm?a=yes", cfg = prefixed))
    }

    @Test fun theAppsOwnCalls_keepTheirCredentials() {
        assertTrue(attaches("https://relay.example.test/v/prepare", method = "POST"))
        assertTrue(attaches("https://relay.example.test/v/confirm", method = "POST"))
        assertTrue(attaches("https://relay.example.test/threads"))
        assertTrue(attaches("https://relay.example.test/attachment/ABC"))
        assertTrue(attaches("https://relay.example.test/send", method = "POST"))
        assertTrue(attaches("https://relay.example.test/ws"))
        assertTrue(attaches("https://host.example.test/relay/threads", cfg = prefixed))
    }

    @Test fun anotherHost_neverGetsThem() {
        assertFalse(attaches("https://relay.example.test.evil.example/threads"))
        assertFalse(attaches("https://cdn.example.org/pic.jpg"))
        assertFalse(attaches("https://relay.example.test/threads", cfg = RelayConfig()))
    }

    @Test fun theImageLoader_fetchesOnlyMediaPathsOfTheRelay() {
        for (path in listOf("/attachment/ABC", "/thumbnail/ABC", "/link_image/42", "/link_preview_image/k",
                            "/chat_icon/chat1", "/bp_asset?src=x")) {
            assertTrue(path, loads("https://relay.example.test$path"))
        }
        for (path in listOf("/v/prepare?q=x", "/v/confirm?a=yes", "/assistant/confirm", "/threads", "/send",
                            "/health", "/", "/attachmentX")) {
            assertFalse(path, loads("https://relay.example.test$path"))
        }
        assertFalse(loads("https://relay.example.test/attachment/ABC", method = "POST"))
        assertTrue(loads("https://host.example.test/relay/thumbnail/ABC", cfg = prefixed))
        assertFalse(loads("https://host.example.test/relay/v/confirm?a=yes", cfg = prefixed))
    }

    @Test fun imagesElsewhere_areLeftToTheLoader() {
        assertTrue(loads("https://cdn.example.org/pic.jpg"))
        assertTrue(loads("https://relay.example.test.evil.example/v/confirm?a=yes"))   // not the relay: no credentials go there
    }
}
