package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner-safety rule of RelayConfigStore.save: only what differs from the
 * build's own values is written, so saving the Settings form with the owner's
 * values pre-filled stores nothing and a later rebuild with a changed
 * secrets.properties still takes effect on the phone — and a field the form
 * emptied over a build value is stored as the CLEARED mark, so what the form
 * showed is what is in force. In-memory backing, made-up values
 * (RelayConfigTest.kt covers the rest of the store).
 */
class RelayConfigStoreTest {

    private val built = RelayConfig(
        base = "https://relay.example.test",
        wsUrl = "wss://relay.example.test/ws",
        token = "built-token",
        cfClientId = "built-id.access",
        cfClientSecret = "built-secret",
        mapUrl = "https://maps.example.test/family",
    )

    private fun store(defaults: RelayConfig, backing: RelayConfigStore.InMemoryBacking = RelayConfigStore.InMemoryBacking()) =
        RelayConfigStore(defaults, backing) to backing

    @Test fun saveDefaults_leavesBackingEmpty() {
        val (s, backing) = store(built)
        s.save(built)
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built, s.current)
        assertTrue(s.current.isConfigured)
    }

    @Test fun saveDefaults_viaTheSettingsForm_leavesBackingEmpty() {
        // What the owner's Save of an untouched form hands over: RelayFormState.from(current).toConfig().
        val (s, backing) = store(built)
        s.save(RelayFormState.from(s.current).toConfig())
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built, s.current)
    }

    @Test fun saveDefaultsWithNewToken_storesOnlyTheToken() {
        val (s, backing) = store(built)
        s.save(built.copy(token = "new-token"))
        assertEquals(mapOf("token" to "new-token"), backing.read())
        assertEquals("new-token", s.current.token)
        assertEquals(built.base, s.current.base)
        assertEquals(built.cfClientSecret, s.current.cfClientSecret)
        // A fresh store over the same backing sees the same thing.
        assertEquals(s.current, RelayConfigStore(built, backing).current)
    }

    @Test fun saveNewBase_storesTheBaseAndItsDerivedWebSocketUrl_nothingElse() {
        val (s, backing) = store(built)
        s.save(RelayFormState.from(s.current).copy(base = "https://new.example.test").toConfig())
        assertEquals(mapOf("base" to "https://new.example.test", "ws" to "wss://new.example.test/ws"), backing.read())
        assertEquals("new.example.test", s.current.origin?.host)
        assertEquals(built.token, s.current.token)
    }

    @Test fun saveBackToTheDefaults_removesTheOverrideAgain() {
        val (s, backing) = store(built)
        s.save(built.copy(token = "new-token"))
        assertEquals(setOf("token"), backing.read().keys)
        s.save(built)
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built, s.current)
    }

    @Test fun stranger_everythingTypedIsStored() {
        val (s, backing) = store(RelayConfig.EMPTY)
        s.save(RelayConfig(base = "https://my.example.test", token = "mine", cfClientId = "id", cfClientSecret = "secret"))
        assertEquals(
            mapOf(
                "base" to "https://my.example.test", "ws" to "wss://my.example.test/ws",
                "token" to "mine", "cf_id" to "id", "cf_secret" to "secret",
            ),
            backing.read(),
        )
        assertFalse(backing.read().containsKey("map"))   // blank is never written
        assertTrue(s.current.isConfigured)
        assertTrue(s.current.hasCfAccess)
    }

    @Test fun overridesOf_isTheDifference_andMergesBackToExactlyTheTypedConfig() {
        val typed = built.copy(base = "https://new.example.test", wsUrl = "wss://new.example.test/ws", mapUrl = "")
        val o = RelayConfigStore.overridesOf(built, typed)
        assertEquals(setOf("base", "ws", "map"), o.keys)
        assertEquals(RelayConfigStore.CLEARED, o["map"])   // blank where the build has a value: cleared, not dropped
        assertEquals(typed, RelayConfigStore.merge(built, o))
        assertEquals(emptyMap<String, String>(), RelayConfigStore.overridesOf(built, built))
        // Over no defaults a blank is simply absent (nothing to clear).
        assertEquals(RelayConfigStore.toMap(typed).filterValues { it.isNotEmpty() }, RelayConfigStore.overridesOf(RelayConfig.EMPTY, typed))
        assertEquals(typed, RelayConfigStore.merge(RelayConfig.EMPTY, RelayConfigStore.overridesOf(RelayConfig.EMPTY, typed)))
    }

    // ---- a blank over a build value means "none", and the form's Save honours it ----

    @Test fun saveWithTheCfSwitchOff_overCfDefaults_clearsThePair_andStoresOnlyTheMarks() {
        val (s, backing) = store(built)
        val typed = RelayFormState.from(s.current).copy(cfEnabled = false).toConfig()
        val after = s.save(typed)
        assertFalse(after.hasCfAccess)
        assertEquals("", s.current.cfClientId)
        assertEquals("", s.current.cfClientSecret)
        assertEquals(mapOf("cf_id" to RelayConfigStore.CLEARED, "cf_secret" to RelayConfigStore.CLEARED), backing.read())
        // What the form reloads from the store agrees with what it showed: the switch stays off.
        assertFalse(RelayFormState.from(s.current).cfEnabled)
        assertEquals(typed, s.current)
        // And the pair is not sent anywhere any more.
        val r = forwarded(relayAuthInterceptorFor { s.current }, okhttp3.Request.Builder().url("${built.base}/threads").build())
        assertEquals(1, credentialCount(r))
        assertNotNull(r.header("X-Imsg-Token"))
        // Nor to a new host typed in the same Save.
        s.save(RelayFormState.from(s.current).copy(base = "https://ts.example.test", cfEnabled = false).toConfig())
        val r2 = forwarded(relayAuthInterceptorFor { s.current }, okhttp3.Request.Builder().url("https://ts.example.test/threads").build())
        assertEquals(1, credentialCount(r2))
        assertFalse(s.current.hasCfAccess)
    }

    @Test fun saveBlankMapUrl_overADefault_clearsIt_andResetBringsItBack() {
        val (s, backing) = store(built)
        s.save(RelayFormState.from(s.current).copy(mapUrl = "").toConfig())
        assertEquals("", s.current.mapUrl)
        assertEquals(mapOf("map" to RelayConfigStore.CLEARED), backing.read())
        assertEquals("", RelayConfigStore(built, backing).current.mapUrl)   // survives a restart
        assertEquals("", RelayFormState.from(s.current).mapUrl)
        s.reset()
        assertEquals(built.mapUrl, s.current.mapUrl)
        assertEquals(emptyMap<String, String>(), backing.read())
    }

    @Test fun typingTheBuildValueBack_removesTheClearMark() {
        val (s, backing) = store(built)
        s.save(RelayFormState.from(s.current).copy(mapUrl = "").toConfig())
        assertEquals(setOf("map"), backing.read().keys)
        s.save(RelayFormState.from(s.current).copy(mapUrl = built.mapUrl).toConfig())
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built, s.current)
    }

    @Test fun clearedMark_canNeverBeTypedAsAValue() {
        val m = RelayConfigStore.CLEARED
        assertFalse(RelayConfigValidator.isHeaderSafe(m))
        for (bad in listOf(
            RelayConfig(base = m, token = "t"),
            RelayConfig(base = built.base, wsUrl = m, token = "t"),
            RelayConfig(base = built.base, token = m),
            RelayConfig(base = built.base, token = "t", cfClientId = m, cfClientSecret = "s"),
            RelayConfig(base = built.base, token = "t", cfClientId = "i", cfClientSecret = m),
            RelayConfig(base = built.base, token = "t", mapUrl = m),
        )) {
            assertTrue("should refuse $bad", RelayConfigValidator.problems(bad).isNotEmpty())
        }
        // A stray mark in the file is read as "", like any other cleared field.
        val s = RelayConfigStore(built, RelayConfigStore.InMemoryBacking(mapOf("map" to m, "cf_id" to m, "cf_secret" to m)))
        assertEquals("", s.current.mapUrl)
        assertFalse(s.current.hasCfAccess)
    }

    @Test fun invalidSave_writesNothing_evenWhenOnlyOneFieldChanged() {
        val (s, backing) = store(built)
        val r = runCatching { s.save(built.copy(mapUrl = "http://maps.example.test/family")) }
        assertTrue(r.isFailure)
        assertEquals(emptyMap<String, String>(), backing.read())
        assertEquals(built, s.current)
    }
}
