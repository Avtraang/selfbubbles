package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay form's state holder (RelayFormState.kt): pre-fill, dirty tracking,
 * the Cloudflare switch, the per-field problems and what Save would hand to
 * the store. Plain values, no Compose. Every credential here is made up.
 */
class RelayFormStateTest {

    private val owner = RelayConfig(
        base = "https://relay.example.test",
        wsUrl = "wss://relay.example.test/socket",   // explicit, not the derived "/ws"
        token = "built-token",
        cfClientId = "built-id.access",
        cfClientSecret = "built-secret",
        mapUrl = "https://maps.example.test/family#start",
    )

    // ---- pre-fill ----

    @Test fun from_prefillsEveryField_andTheCfSwitchFollowsThePair() {
        val f = RelayFormState.from(owner)
        assertEquals(owner.base, f.base)
        assertEquals(owner.token, f.token)
        assertTrue(f.cfEnabled)
        assertEquals(owner.cfClientId, f.cfClientId)
        assertEquals(owner.cfClientSecret, f.cfClientSecret)
        assertEquals(owner.mapUrl, f.mapUrl)
        assertEquals(owner, f.prefill)
        assertFalse(f.dirty)
        assertTrue(f.canSave)
        assertTrue(f.canTest)
        assertEquals(emptyList<String>(), f.problems)
    }

    @Test fun from_strangerBuild_isAnEmptyForm_thatCannotBeSavedOrTested() {
        val f = RelayFormState.from(RelayConfig.EMPTY)
        assertEquals("", f.base)
        assertEquals("", f.token)
        assertFalse(f.cfEnabled)
        assertFalse(f.dirty)
        assertFalse(f.canSave)
        assertFalse(f.canTest)
        assertTrue(f.problemsFor(RelayField.BASE).isNotEmpty())
        assertTrue(f.problemsFor(RelayField.TOKEN).isNotEmpty())
    }

    @Test fun from_tokenOnlyConfig_leavesTheCfSwitchOff() {
        val f = RelayFormState.from(RelayConfig("https://relay.example.test", "", "t"))
        assertFalse(f.cfEnabled)
        assertEquals("", f.cfClientId)
    }

    // ---- toConfig ----

    @Test fun toConfig_unchangedForm_isTheConfigItWasOpenedWith_explicitWebSocketKept() {
        assertEquals(owner, RelayFormState.from(owner).toConfig())
        assertEquals("wss://relay.example.test/socket", RelayFormState.from(owner).toConfig().wsUrl)
    }

    @Test fun toConfig_normalizes_trimsAndDerivesTheWebSocketUrlForANewBase() {
        val f = RelayFormState(base = "  https://new.example.test:8443/p/  ", token = " t ")
        val c = f.toConfig()
        assertEquals("https://new.example.test:8443/p", c.base)
        assertEquals("wss://new.example.test:8443/p/ws", c.wsUrl)
        assertEquals("t", c.token)
        assertEquals(emptyList<String>(), f.problems)
    }

    @Test fun toConfig_changedBase_dropsTheOldExplicitWebSocketUrl() {
        // The build's "/socket" named the old host; the validator would refuse it on the new one.
        val c = RelayFormState.from(owner).copy(base = "https://new.example.test").toConfig()
        assertEquals("wss://new.example.test/ws", c.wsUrl)
        assertEquals(emptyList<String>(), RelayConfigValidator.problems(c))
    }

    @Test fun toConfig_baseRetypedWithATrailingSlash_stillCountsAsUnchanged() {
        val f = RelayFormState.from(owner).copy(base = owner.base + "/")
        assertEquals(owner.wsUrl, f.toConfig().wsUrl)
        assertFalse(f.dirty)
    }

    // ---- the Cloudflare switch ----

    @Test fun cfSwitchOff_clearsBothFieldsInTheProducedConfig_butKeepsTheTypedText() {
        val f = RelayFormState.from(owner).copy(cfEnabled = false)
        val c = f.toConfig()
        assertEquals("", c.cfClientId)
        assertEquals("", c.cfClientSecret)
        assertFalse(c.hasCfAccess)
        // The text is still in the form, so flipping the switch back on restores it.
        assertEquals(owner.cfClientId, f.cfClientId)
        assertTrue(f.copy(cfEnabled = true).toConfig().hasCfAccess)
        assertTrue(f.dirty)
        assertTrue(f.canSave)
    }

    @Test fun cfSwitchOn_withOneOfThePairBlank_isAProblemOnTheCfField() {
        val f = RelayFormState(base = "https://relay.example.test", token = "t", cfEnabled = true, cfClientId = "id")
        assertFalse(f.canSave)
        assertEquals(1, f.problemsFor(RelayField.CF).size)
        assertTrue(f.problemsFor(RelayField.CF)[0].contains("both the client id and the client secret"))
        assertTrue(f.problemsFor(RelayField.BASE).isEmpty())
        // Switch off: the half pair no longer counts.
        assertTrue(f.copy(cfEnabled = false).canSave)
    }

    // ---- dirty ----

    @Test fun dirty_followsEveryField_andReturnsToCleanWhenRetyped() {
        val f = RelayFormState.from(owner)
        assertFalse(f.dirty)
        assertTrue(f.copy(base = "https://other.example.test").dirty)
        assertTrue(f.copy(token = "other").dirty)
        assertTrue(f.copy(cfClientId = "other").dirty)
        assertTrue(f.copy(cfClientSecret = "other").dirty)
        assertTrue(f.copy(mapUrl = "").dirty)
        assertTrue(f.copy(cfEnabled = false).dirty)
        assertFalse(f.copy(token = "other").copy(token = owner.token).dirty)
        // Whitespace is not a change.
        assertFalse(f.copy(token = "  ${owner.token} ").dirty)
    }

    @Test fun reloaded_isCleanAgainstTheNewConfig() {
        val edited = RelayFormState.from(owner).copy(token = "typed")
        val saved = owner.copy(token = "typed")
        val f = edited.reloaded(saved)
        assertFalse(f.dirty)
        assertEquals("typed", f.token)
        assertEquals(saved, f.prefill)
    }

    // ---- problems mirror the validator, pinned to fields ----

    @Test fun problems_areTheValidators_minusTheDerivedWebSocketNoise() {
        val f = RelayFormState(base = "http://relay.example.test", token = "", cfEnabled = true, cfClientId = "id", mapUrl = "ftp://x")
        val all = RelayConfigValidator.problems(f.toConfig())
        // The validator also says "WebSocket URL is required": no https base, nothing to derive it from.
        assertEquals(5, all.size)
        assertEquals(all.filterNot { it.startsWith("WebSocket URL") }, f.problems)
        assertEquals(4, f.problems.size)
        assertFalse(f.canSave)
        assertFalse(f.canTest)   // not https
    }

    @Test fun problems_withAGoodBase_areExactlyTheValidators() {
        val f = RelayFormState(base = "https://relay.example.test", token = "", mapUrl = "ftp://x")
        assertEquals(RelayConfigValidator.problems(f.toConfig()), f.problems)
        assertEquals(2, f.problems.size)
        // Save follows the validator, never the shown list, so the two can never disagree on it.
        assertEquals(RelayConfigValidator.problems(f.toConfig()).isEmpty(), f.canSave)
    }

    @Test fun problemsFor_pinsEachMessageToItsField_andNothingIsLost() {
        val f = RelayFormState(base = "https://user@relay.example.test", token = " ", cfEnabled = true, cfClientSecret = "s", mapUrl = "not a url")
        val pinned = RelayField.entries.flatMap { f.problemsFor(it) } + f.otherProblems
        assertEquals(f.problems.toSet(), pinned.toSet())
        assertEquals(f.problems.size, pinned.size)
        assertTrue(f.problemsFor(RelayField.BASE).single().startsWith("Relay URL"))
        assertEquals("Token is required.", f.problemsFor(RelayField.TOKEN).single())
        assertTrue(f.problemsFor(RelayField.CF).single().startsWith("Cloudflare Access"))
        assertTrue(f.problemsFor(RelayField.MAP).single().startsWith("Map URL"))
        assertEquals(emptyList<String>(), f.otherProblems)
    }

    @Test fun problems_nameFieldsNeverValues() {
        val f = RelayFormState(base = "http://leak.example.test", token = "", cfEnabled = true, cfClientId = "leak-id")
        assertFalse(f.problems.joinToString().contains("leak"))
    }

    @Test fun canTest_needsAnHttpsBase_andHeaderSafeCredentials() {
        // A missing token is something Test reports ("rejected the token"), not something that blocks it.
        val f = RelayFormState(base = "https://relay.example.test")
        assertTrue(f.canTest)
        assertFalse(f.canSave)
        assertFalse(RelayFormState(base = "relay.example.test").canTest)
        assertFalse(RelayFormState(base = "https://").canTest)
        // A credential a header cannot carry blocks Test as well as Save (OkHttp would throw, quoting it).
        assertFalse(f.copy(token = "tokén").canTest)
        assertFalse(f.copy(token = "t", cfEnabled = true, cfClientId = "id", cfClientSecret = "s​").canTest)
        // ...but only while the switch is on: the pair is not part of the produced config otherwise.
        assertTrue(f.copy(token = "t", cfEnabled = false, cfClientId = "idé", cfClientSecret = "s").canTest)
    }

    // ---- header safety: a credential OkHttp cannot send is a field problem, never a crash ----

    @Test fun nonAsciiToken_cannotBeSavedOrTested_andTheProblemNamesTheFieldNotTheValue() {
        val f = RelayFormState.from(owner).copy(token = "LEAK-tokén")
        assertFalse(f.canSave)
        assertFalse(f.canTest)
        assertEquals(1, f.problemsFor(RelayField.TOKEN).size)
        assertTrue(f.problemsFor(RelayField.TOKEN).single().startsWith("Token must be plain ASCII"))
        assertFalse(f.problems.joinToString().contains("LEAK"))
        assertTrue(f.problemsFor(RelayField.CF).isEmpty())
        assertEquals(emptyList<String>(), f.otherProblems)
    }

    @Test fun nonAsciiCfValues_arePinnedToTheirOwnField() {
        val f = RelayFormState.from(owner).copy(cfClientId = "idé", cfClientSecret = "sec–ret")
        assertFalse(f.canSave)
        assertEquals(2, f.problemsFor(RelayField.CF).size)
        assertTrue(f.problemsForCf(secret = false).single().startsWith("Cloudflare Access client id"))
        assertTrue(f.problemsForCf(secret = true).single().startsWith("Cloudflare Access client secret"))
        // Nothing is shown twice and nothing is lost between the two fields.
        assertEquals(f.problemsFor(RelayField.CF).toSet(), (f.problemsForCf(false) + f.problemsForCf(true)).toSet())
    }

    // ---- the pair rules go under the field that needs input ----

    @Test fun bothOrNeither_isShownUnderTheBlankFieldOfThePair() {
        val idOnly = RelayFormState(base = "https://relay.example.test", token = "t", cfEnabled = true, cfClientId = "id")
        assertEquals(emptyList<String>(), idOnly.problemsForCf(secret = false))
        assertTrue(idOnly.problemsForCf(secret = true).single().contains("both the client id and the client secret"))
        val secretOnly = idOnly.copy(cfClientId = "", cfClientSecret = "s")
        assertTrue(secretOnly.problemsForCf(secret = false).single().contains("both the client id and the client secret"))
        assertEquals(emptyList<String>(), secretOnly.problemsForCf(secret = true))
    }

    @Test fun cfSwitchOn_withBothBlank_isAProblem_untilThePairIsTypedOrTheSwitchOff() {
        val f = RelayFormState(base = "https://relay.example.test", token = "t", cfEnabled = true)
        assertTrue(f.cfPairEmpty)
        assertFalse("saving would silently flip the switch back off", f.canSave)
        assertEquals(listOf(RelayFormState.CF_PAIR_EMPTY), f.problemsFor(RelayField.CF))
        assertEquals(listOf(RelayFormState.CF_PAIR_EMPTY), f.problemsForCf(secret = false))   // under the first field of the pair
        assertEquals(emptyList<String>(), f.problemsForCf(secret = true))
        assertEquals(emptyList<String>(), f.otherProblems)
        // The validator itself is fine with the produced config (no pair at all); the form adds the rule.
        assertEquals(emptyList<String>(), RelayConfigValidator.problems(f.toConfig()))
        assertTrue(f.canTest)   // Test is still useful: it does not depend on the pair
        assertTrue(f.copy(cfEnabled = false).canSave)
        assertTrue(f.copy(cfClientId = "id", cfClientSecret = "s").canSave)
        assertFalse(f.copy(cfClientId = "id").canSave)   // then the both-or-neither rule takes over
        assertFalse(f.copy(cfClientId = "id").cfPairEmpty)
        // Whitespace alone does not fill the pair.
        assertTrue(f.copy(cfClientId = "  ", cfClientSecret = " ").cfPairEmpty)
    }

    // ---- what survives a rotation or process death (RelayFormSaver) ----

    @Test fun saver_rotation_keepsTheTypedTokenAndSecret_fromTheDraft() {
        val draft = RelayDraft()
        val stored = RelayFormState.from(owner)
        val opened = RelayFormSaver.open(stored, draft)
        assertEquals(stored, opened)
        assertTrue(draft.session!!.isNotEmpty())
        // Typing updates the draft (as RelayForm.edit does) and the form.
        val typed = opened.copy(base = "https://new.example.test", token = "typed-token", cfClientSecret = "typed-secret", mapUrl = "")
        draft.token = typed.token
        draft.cfClientSecret = typed.cfClientSecret
        val saved = RelayFormSaver.save(typed, draft)
        assertFalse("the token must never be in the saved state", saved.any { it.toString().contains("typed-token") })
        assertFalse("the secret must never be in the saved state", saved.any { it.toString().contains("typed-secret") })
        assertFalse(saved.any { it.toString().contains(owner.token) || it.toString().contains(owner.cfClientSecret) })
        val restored = RelayFormSaver.restore(saved, draft, stored)
        assertEquals(typed, restored)
    }

    @Test fun saver_processDeath_fallsBackToTheStoreForTheSecrets_keepsTheRest_andAdoptsTheSession() {
        val stored = RelayFormState.from(owner)
        val before = RelayDraft()
        val typed = RelayFormSaver.open(stored, before).copy(base = "https://new.example.test", token = "typed-token", cfEnabled = false)
        before.token = typed.token
        val saved = RelayFormSaver.save(typed, before)
        // The process died: a fresh draft knows nothing.
        val after = RelayDraft()
        val restored = RelayFormSaver.restore(saved, after, stored)
        assertEquals("https://new.example.test", restored.base)
        assertFalse(restored.cfEnabled)
        assertEquals(owner.token, restored.token)                  // the typed token is gone, by design
        assertEquals(owner.cfClientSecret, restored.cfClientSecret)
        assertEquals(saved[0], after.session)                      // adopted
        assertEquals(owner.token, after.token)
        // From now on a plain rotation keeps what is typed again.
        val retyped = restored.copy(token = "second-token")
        after.token = retyped.token
        assertEquals(retyped, RelayFormSaver.restore(RelayFormSaver.save(retyped, after), after, stored))
    }

    @Test fun saver_restoreWithoutASession_startsOne() {
        val stored = RelayFormState.from(owner)
        val draft = RelayDraft()
        val restored = RelayFormSaver.restore(listOf("", "https://x.example.test", false, "", ""), draft, stored)
        assertEquals("https://x.example.test", restored.base)
        assertEquals(owner.token, restored.token)
        assertTrue(draft.session!!.isNotEmpty())
        draft.token = "later"
        assertEquals("later", RelayFormSaver.restore(RelayFormSaver.save(restored, draft), draft, stored).token)
    }

    @Test fun toString_neverShowsACredential() {
        val s = RelayFormState.from(owner).toString()
        assertFalse(s.contains("built-token"))
        assertFalse(s.contains("built-secret"))
        assertFalse(s.contains("built-id"))
        assertTrue(s.contains("relay.example.test"))
    }
}
