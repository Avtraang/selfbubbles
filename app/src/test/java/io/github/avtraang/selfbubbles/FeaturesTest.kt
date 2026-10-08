package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feature switches (Features.kt), on the plain-Kotlin parts: the build
 * default table (FEATURES present / empty / absent with and without a relay),
 * the FEATURES and `/health` name parsing, the once-only relay seed and who
 * never gets it, the Settings annotation decision, and a prefs round trip
 * through the in-memory backing. No Android, no network, made-up values.
 */
class FeaturesTest {

    private val all = Feature.entries.toSet()

    private fun build(present: Boolean, raw: String = "", relay: Boolean) = BuildFeatureDefault(present, raw, relay)

    private fun on(d: BuildFeatureDefault): Set<Feature> = d.switches.filterValues { it }.keys

    // ---- FEATURES parsing ----

    @Test fun parse_namesBecomeFeatures_blanksSkipped() {
        val p = parseFeatureList(" facetime, map ,,translate")
        assertEquals(setOf(Feature.FACE_TIME, Feature.FAMILY_MAP, Feature.TRANSLATION), p.on)
        assertTrue(p.unknown.isEmpty())
    }

    @Test fun parse_unknownNamesAreReportedNotThrown() {
        val p = parseFeatureList("map,bogus,voice,sms")
        assertEquals(setOf(Feature.FAMILY_MAP, Feature.VOICE_ASSISTANT), p.on)
        assertEquals(listOf("bogus", "sms"), p.unknown)
    }

    @Test fun parse_caseInsensitive_emptyIsNothing() {
        assertEquals(setOf(Feature.FACE_TIME), parseFeatureList("FaceTime").on)
        assertEquals(ParsedFeatures(emptySet(), emptyList()), parseFeatureList(""))
        assertEquals(ParsedFeatures(emptySet(), emptyList()), parseFeatureList(" , "))
    }

    @Test fun relayNames_areTheVocabulary() {
        assertEquals(Feature.FAMILY_MAP, Feature.byRelayName("map"))
        assertEquals(Feature.FACE_TIME, Feature.byRelayName("facetime"))
        assertEquals(Feature.TRANSLATION, Feature.byRelayName("translate"))
        assertEquals(Feature.VOICE_ASSISTANT, Feature.byRelayName("voice"))
        assertNull(Feature.byRelayName("maps"))
    }

    // ---- the build default table ----

    @Test fun featuresPresentWithNames_exactlyThoseOn() {
        val d = build(present = true, raw = "map,translate", relay = true)
        assertEquals(setOf(Feature.FAMILY_MAP, Feature.TRANSLATION), on(d))
        assertFalse(d.seedable)
    }

    @Test fun featuresPresentWithNames_authoritativeEvenWithoutRelay() {
        val d = build(present = true, raw = "voice", relay = false)
        assertEquals(setOf(Feature.VOICE_ASSISTANT), on(d))
        assertFalse(d.seedable)
    }

    @Test fun featuresPresentEmpty_allOff_notSeedable() {
        val d = build(present = true, raw = "", relay = true)
        assertEquals(emptySet<Feature>(), on(d))
        assertFalse(d.seedable)
    }

    @Test fun featuresAbsentWithRelay_theOwnersBuild_allOn_neverSeedable() {
        val d = build(present = false, raw = "", relay = true)
        assertEquals(all, on(d))
        assertFalse(d.seedable)
    }

    @Test fun featuresAbsentWithoutRelay_allOff_seedable() {
        val d = build(present = false, raw = "", relay = false)
        assertEquals(emptySet<Feature>(), on(d))
        assertTrue(d.seedable)
    }

    @Test fun featuresPresent_unknownNamesIgnored() {
        val d = build(present = true, raw = "map,bogus", relay = true)
        assertEquals(setOf(Feature.FAMILY_MAP), on(d))
        assertEquals(listOf("bogus"), d.parsed.unknown)
    }

    // ---- /health names ----

    @Test fun fromRelay_knownNamesOnly() {
        val m = FeatureFlags.fromRelay(mapOf("facetime" to true, "map" to false, "sms" to true, "voice" to true))
        assertEquals(mapOf(Feature.FACE_TIME to true, Feature.FAMILY_MAP to false, Feature.VOICE_ASSISTANT to true), m)
    }

    // ---- seeding ----

    private fun strangerFlags(backing: FeatureFlags.InMemoryBacking = FeatureFlags.InMemoryBacking()) =
        FeatureFlags(build(present = false, relay = false), backing)

    private val report = mapOf("facetime" to true, "map" to false, "translate" to true, "voice" to false)

    @Test fun stranger_firstNonEmptyReport_seedsOnce() {
        val f = strangerFlags()
        assertFalse(f.seeded)
        assertTrue(f.relayReported(report))
        assertTrue(f.seeded)
        assertEquals(
            mapOf(Feature.FAMILY_MAP to false, Feature.FACE_TIME to true, Feature.TRANSLATION to true, Feature.VOICE_ASSISTANT to false),
            f.switches,
        )
        // A later, different report only annotates: no switch moves.
        assertFalse(f.relayReported(mapOf("facetime" to false, "map" to true, "translate" to false, "voice" to true)))
        assertTrue(f.isOn(Feature.FACE_TIME))
        assertFalse(f.isOn(Feature.FAMILY_MAP))
        assertEquals(false, f.lastReported[Feature.FACE_TIME])
        assertEquals(true, f.lastReported[Feature.FAMILY_MAP])
    }

    @Test fun stranger_partialReport_seedsOnlyTheNamesListed() {
        val f = strangerFlags()
        assertTrue(f.relayReported(mapOf("facetime" to true)))
        assertTrue(f.isOn(Feature.FACE_TIME))
        assertFalse(f.isOn(Feature.FAMILY_MAP))
        assertFalse(f.isOn(Feature.TRANSLATION))
        assertTrue(f.seeded)
    }

    @Test fun stranger_emptyReport_seedsNothing_laterReportStillCan() {
        val f = strangerFlags()
        assertFalse(f.relayReported(emptyMap()))
        assertFalse(f.seeded)
        assertFalse(f.anyStored)
        assertEquals(emptyMap<Feature, Boolean>(), f.lastReported)
        assertTrue(f.relayReported(report))
        assertTrue(f.isOn(Feature.TRANSLATION))
    }

    @Test fun stranger_unknownNamesOnly_countsAsEmpty() {
        val f = strangerFlags()
        assertFalse(f.relayReported(mapOf("sms" to true)))
        assertFalse(f.seeded)
    }

    @Test fun stranger_manualFlipBeforeAnyReport_blocksTheSeed() {
        val f = strangerFlags()
        f.set(Feature.FAMILY_MAP, true)
        assertFalse(f.relayReported(report))
        assertTrue(f.isOn(Feature.FAMILY_MAP))      // the user's choice, not the relay's false
        assertFalse(f.isOn(Feature.FACE_TIME))     // still the build default
        assertFalse(f.seeded)
        assertEquals(false, f.lastReported[Feature.FAMILY_MAP])
    }

    @Test fun owner_neverSeeded_reportOnlyAnnotates() {
        val f = FeatureFlags(build(present = false, relay = true), FeatureFlags.InMemoryBacking())
        assertFalse(f.relayReported(report))
        assertEquals(all, f.switches.filterValues { it }.keys)
        assertFalse(f.seeded)
        assertFalse(f.anyStored)
        assertEquals(false, f.lastReported[Feature.FAMILY_MAP])
        assertEquals(true, f.lastReported[Feature.FACE_TIME])
    }

    @Test fun authoritativeEmptyList_neverSeeded() {
        val f = FeatureFlags(build(present = true, raw = "", relay = false), FeatureFlags.InMemoryBacking())
        assertFalse(f.relayReported(report))
        assertTrue(f.switches.values.none { it })
        assertFalse(f.seeded)
    }

    @Test fun shouldSeed_table() {
        val known = mapOf(Feature.FACE_TIME to true)
        assertTrue(FeatureFlags.shouldSeed(seedable = true, anyStored = false, seeded = false, known = known))
        assertFalse(FeatureFlags.shouldSeed(seedable = false, anyStored = false, seeded = false, known = known))
        assertFalse(FeatureFlags.shouldSeed(seedable = true, anyStored = true, seeded = false, known = known))
        assertFalse(FeatureFlags.shouldSeed(seedable = true, anyStored = false, seeded = true, known = known))
        assertFalse(FeatureFlags.shouldSeed(seedable = true, anyStored = false, seeded = false, known = emptyMap()))
    }

    // ---- manual switches over the build default ----

    @Test fun ownerTurnsOneOff_othersKeepTheBuildDefault() {
        val f = FeatureFlags(build(present = false, relay = true), FeatureFlags.InMemoryBacking())
        f.set(Feature.FACE_TIME, false)
        assertFalse(f.isOn(Feature.FACE_TIME))
        assertTrue(f.isOn(Feature.FAMILY_MAP))
        assertTrue(f.isOn(Feature.TRANSLATION))
        assertTrue(f.isOn(Feature.VOICE_ASSISTANT))
        f.set(Feature.FACE_TIME, true)
        assertTrue(f.isOn(Feature.FACE_TIME))
    }

    // ---- annotations ----

    @Test fun reportedUnavailable_onlyWhenTheRelaySaidFalse() {
        val last = mapOf(Feature.FACE_TIME to false, Feature.TRANSLATION to true)
        assertTrue(FeatureFlags.reportedUnavailable(Feature.FACE_TIME, last, mapOnRelay = false))
        assertFalse(FeatureFlags.reportedUnavailable(Feature.TRANSLATION, last, mapOnRelay = false))
        assertFalse(FeatureFlags.reportedUnavailable(Feature.VOICE_ASSISTANT, last, mapOnRelay = false))   // not reported
        assertFalse(FeatureFlags.reportedUnavailable(Feature.FACE_TIME, emptyMap(), mapOnRelay = false))   // older relay
    }

    @Test fun reportedUnavailable_familyMap_onlyForAMapOnTheRelay() {
        val last = mapOf(Feature.FAMILY_MAP to false)
        assertTrue(FeatureFlags.reportedUnavailable(Feature.FAMILY_MAP, last, mapOnRelay = true))
        // The owner's map lives on another host: the relay's verdict about its own /map does not apply.
        assertFalse(FeatureFlags.reportedUnavailable(Feature.FAMILY_MAP, last, mapOnRelay = false))
        assertFalse(FeatureFlags.reportedUnavailable(Feature.FAMILY_MAP, mapOf(Feature.FAMILY_MAP to true), mapOnRelay = true))
    }

    @Test fun mapIsTheRelays_relayOrigin_orNoUrlYet_notAnotherHost() {
        val relay = RelayConfig(base = "https://relay.example.test", token = "test-token")
        assertTrue(FeatureFlags.mapIsTheRelays("https://relay.example.test/map", relay))
        assertTrue(FeatureFlags.mapIsTheRelays("https://relay.example.test/map?scheme=dark#x", relay))
        // A stranger's first run: no URL yet, and the relay's /map is what they will type.
        assertTrue(FeatureFlags.mapIsTheRelays("", relay))
        assertTrue(FeatureFlags.mapIsTheRelays("   ", relay))
        // The owner's map on another host, or the relay host on another port: not the relay's to judge.
        assertFalse(FeatureFlags.mapIsTheRelays("https://home.example.test/local/family-map.html", relay))
        assertFalse(FeatureFlags.mapIsTheRelays("https://relay.example.test:8443/map", relay))
        // No relay configured at all: a typed URL cannot be the relay's, but no URL still is.
        assertFalse(FeatureFlags.mapIsTheRelays("https://home.example.test/map", RelayConfig.EMPTY))
        assertTrue(FeatureFlags.mapIsTheRelays("", RelayConfig.EMPTY))
    }

    @Test fun strangerWithNoMapUrl_seesTheRelaysMapVerdict() {
        // Both halves together: blank URL + map=false -> annotated; another host + map=false -> not.
        val relay = RelayConfig(base = "https://relay.example.test", token = "test-token")
        val last = mapOf(Feature.FAMILY_MAP to false)
        assertTrue(FeatureFlags.reportedUnavailable(Feature.FAMILY_MAP, last, FeatureFlags.mapIsTheRelays("", relay)))
        assertFalse(FeatureFlags.reportedUnavailable(Feature.FAMILY_MAP, last, FeatureFlags.mapIsTheRelays("https://home.example.test/map", relay)))
    }

    // ---- the translation gate ----

    @Test fun autoTranslateControl_shownWhileOn_andForAFlaggedThreadWhileOff() {
        assertTrue(TranslationGate.showAutoTranslateControl(featureOn = true, threadFlagged = false))
        assertTrue(TranslationGate.showAutoTranslateControl(featureOn = true, threadFlagged = true))
        // Off: only "Stop translating" for a thread already flagged on the relay, so the flag can be cleared.
        assertTrue(TranslationGate.showAutoTranslateControl(featureOn = false, threadFlagged = true))
        assertFalse(TranslationGate.showAutoTranslateControl(featureOn = false, threadFlagged = false))
    }

    // ---- storage ----

    @Test fun keys_neverCollideWithTheRelayStore() {
        assertTrue(FeatureFlags.KEYS.none { it in RelayConfigStore.KEYS })
        assertEquals(FeatureFlags.KEYS.size, FeatureFlags.KEYS.toSet().size)
        assertTrue(Feature.entries.all { it.prefsKey in FeatureFlags.KEYS })
    }

    @Test fun roundTrip_throughTheBacking() {
        val backing = FeatureFlags.InMemoryBacking()
        val first = strangerFlags(backing)
        assertTrue(first.relayReported(report))
        first.set(Feature.FAMILY_MAP, true)
        // Only this store's keys are written, as strings.
        assertTrue(backing.read().keys.all { it in FeatureFlags.KEYS })
        assertEquals("on", backing.read()["feature_map"])
        assertEquals("off", backing.read()["feature_voice"])
        assertEquals("1", backing.read()[FeatureFlags.KEY_SEEDED])

        val second = strangerFlags(backing)
        assertEquals(first.switches, second.switches)
        assertTrue(second.seeded)
        assertTrue(second.anyStored)
        assertEquals(first.lastReported, second.lastReported)
        assertEquals(
            mapOf(Feature.FAMILY_MAP to false, Feature.FACE_TIME to true, Feature.TRANSLATION to true, Feature.VOICE_ASSISTANT to false),
            second.lastReported,
        )
    }

    @Test fun foreignKeysInTheBacking_areNeitherReadNorWritten() {
        // The relay store's keys share the file; this store reads only its own and writes only its own
        // (the Android backing removes/puts KEYS one by one, so the rest of the file is never touched).
        val backing = FeatureFlags.InMemoryBacking(mapOf("token" to "x", "feature_map" to "on", "feature_seeded" to "1"))
        val f = strangerFlags(backing)
        assertTrue(f.isOn(Feature.FAMILY_MAP))
        assertTrue(f.seeded)
        f.set(Feature.VOICE_ASSISTANT, true)
        assertTrue(backing.read().keys.all { it in FeatureFlags.KEYS })
        assertEquals("on", backing.read()["feature_voice"])
        assertEquals("on", backing.read()["feature_map"])
    }

    @Test fun unreadableStoredValue_fallsBackToTheBuildDefault() {
        val backing = FeatureFlags.InMemoryBacking(mapOf("feature_facetime" to "maybe"))
        val f = FeatureFlags(build(present = false, relay = true), backing)
        assertTrue(f.isOn(Feature.FACE_TIME))
        assertNull(FeatureFlags.decodeSwitch("maybe"))
    }

    @Test fun reportedMap_encodesAndDecodes() {
        val known = mapOf(Feature.VOICE_ASSISTANT to true, Feature.FAMILY_MAP to false)
        assertEquals("map=0,voice=1", FeatureFlags.encodeReported(known))
        assertEquals(known, FeatureFlags.decodeReported("map=0,voice=1"))
        assertEquals(emptyMap<Feature, Boolean>(), FeatureFlags.decodeReported(null))
        assertEquals(emptyMap<Feature, Boolean>(), FeatureFlags.decodeReported(""))
        assertEquals(mapOf(Feature.FACE_TIME to true), FeatureFlags.decodeReported("facetime=1,junk,=0,sms=1,map=x"))
    }
}
