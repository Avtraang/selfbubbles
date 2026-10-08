package io.github.avtraang.selfbubbles

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The four optional features. Each is named as the relay's `/health`
 * "features" map names it, and that name is also the vocabulary of the
 * FEATURES build key — this enum is the one mapping table between relay name,
 * stored key and switch. The switches are the source of truth (Settings >
 * Features); the relay's report only seeds a stranger's first values and
 * annotates the rows.
 */
enum class Feature(val relayName: String, val prefsKey: String) {
    FAMILY_MAP("map", "feature_map"),
    FACE_TIME("facetime", "feature_facetime"),
    TRANSLATION("translate", "feature_translate"),
    VOICE_ASSISTANT("voice", "feature_voice");

    companion object {
        /** The feature a relay or FEATURES name stands for; null for an unknown name. */
        fun byRelayName(name: String): Feature? {
            val n = name.trim().lowercase()
            return entries.firstOrNull { it.relayName == n }
        }
    }
}

/** A FEATURES value split into the features it names and the names it got wrong. */
data class ParsedFeatures(val on: Set<Feature>, val unknown: List<String>)

/** Parses a comma-separated FEATURES value; blanks are skipped, unknown names reported, not thrown. */
fun parseFeatureList(raw: String): ParsedFeatures {
    val on = linkedSetOf<Feature>()
    val unknown = mutableListOf<String>()
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
        val f = Feature.byRelayName(name)
        if (f != null) on += f else unknown += name
    }
    return ParsedFeatures(on, unknown)
}

/**
 * What the build says the switches start as. Plain values, so the rule is a
 * unit test (FeaturesTest), mirroring the comment in app/build.gradle.kts:
 * - FEATURES present (even as an empty `FEATURES=`): it is authoritative —
 *   exactly the names listed start ON; unknown names are ignored (Gradle
 *   warned about them by name).
 * - FEATURES absent: all four ON when RELAY_REMOTE_BASE was set at build time
 *   (the owner's unchanged file), all OFF otherwise (a stranger's empty file).
 * Only that last build — no FEATURES, no relay — is [seedable] from the relay.
 */
class BuildFeatureDefault(val featuresPresent: Boolean, featuresRaw: String, val relayBuiltIn: Boolean) {
    val parsed: ParsedFeatures = parseFeatureList(featuresRaw)

    val switches: Map<Feature, Boolean> = Feature.entries.associateWith { f ->
        if (featuresPresent) f in parsed.on else relayBuiltIn
    }

    /** True only for the "nothing baked in" build: the relay's `/health` may seed its switches once. */
    val seedable: Boolean = !featuresPresent && !relayBuiltIn

    companion object {
        fun fromBuildConfig(): BuildFeatureDefault = BuildFeatureDefault(
            featuresPresent = BuildConfig.FEATURES_PRESENT,
            featuresRaw = BuildConfig.FEATURES,
            relayBuiltIn = BuildConfig.RELAY_REMOTE_BASE.isNotBlank(),
        )
    }
}

/**
 * The switch store, in plain Kotlin: the build default under per-feature
 * stored values, plus the relay's last reported map and the once-only seed.
 * Lives in the same SharedPreferences file as [RelayConfigStore] ("relay_config",
 * excluded from Auto Backup with it) under its own [KEYS], which never collide
 * with that store's; each store reads and writes only its own keys.
 *
 * Seeding: a build whose default was all OFF ([BuildFeatureDefault.seedable])
 * takes its first values from the first non-empty `/health` features map it
 * sees, once — then [KEY_SEEDED] is set and no later report flips a switch.
 * A switch the user already set (anything stored) also blocks the seed, and an
 * empty map (an older relay) seeds nothing. The owner's build is never seedable.
 * A partial map seeds only the names it lists; a feature the relay did not
 * mention keeps the build default (OFF) rather than guessing "available" — a
 * stranger can turn it on in Settings, whereas a button for something the relay
 * never claimed to offer would only fail. (The relay reports all four today.)
 */
class FeatureFlags(val build: BuildFeatureDefault, private val backing: Backing) {

    /** Where the values live. Strings keyed by [KEYS]; a key absent from [write]'s map is removed. */
    interface Backing {
        fun read(): Map<String, String>
        fun write(values: Map<String, String>)
    }

    /** For tests and for the window before [Features.init] runs. */
    class InMemoryBacking(initial: Map<String, String> = emptyMap()) : Backing {
        private var values: Map<String, String> = initial.toMap()
        override fun read(): Map<String, String> = values
        override fun write(values: Map<String, String>) { this.values = values.toMap() }
    }

    @Volatile
    private var stored: Map<String, String> = backing.read().filterKeys { it in KEYS }

    /** Every switch's live value: the stored one, else the build default. */
    val switches: Map<Feature, Boolean>
        get() = Feature.entries.associateWith { f -> stored[f.prefsKey]?.let(::decodeSwitch) ?: build.switches.getValue(f) }

    fun isOn(f: Feature): Boolean = switches.getValue(f)

    /** True once a relay report has seeded the switches (or a manual flip stored one, see [relayReported]). */
    val seeded: Boolean get() = stored[KEY_SEEDED] == SEEDED

    /** True when any switch has a stored value, from a seed or from Settings. */
    val anyStored: Boolean get() = Feature.entries.any { stored.containsKey(it.prefsKey) }

    /** The relay's last reported map, known names only; empty when it reported nothing. Annotations only. */
    val lastReported: Map<Feature, Boolean> get() = decodeReported(stored[KEY_REPORTED])

    /** Stores one switch (Settings). Stored as a difference from nothing: the value itself, always. */
    @Synchronized
    fun set(f: Feature, on: Boolean) {
        write(stored + (f.prefsKey to encodeSwitch(on)))
    }

    /**
     * Records [reported] as [lastReported] and, when [shouldSeed] says so, seeds
     * the switches from it once. Returns true when it seeded.
     */
    @Synchronized
    fun relayReported(reported: Map<String, Boolean>): Boolean {
        val known = fromRelay(reported)
        var next = stored - KEY_REPORTED
        if (known.isNotEmpty()) next = next + (KEY_REPORTED to encodeReported(known))
        val seed = shouldSeed(build.seedable, anyStored, seeded, known)
        if (seed) {
            next = next + known.map { (f, on) -> f.prefsKey to encodeSwitch(on) } + (KEY_SEEDED to SEEDED)
        }
        write(next)
        return seed
    }

    private fun write(values: Map<String, String>) {
        val kept = values.filterKeys { it in KEYS }
        backing.write(kept)
        stored = kept
    }

    companion object {
        const val KEY_SEEDED = "feature_seeded"
        const val KEY_REPORTED = "feature_reported"
        /** Every key this store may touch in the shared prefs file. */
        val KEYS: List<String> = Feature.entries.map { it.prefsKey } + listOf(KEY_SEEDED, KEY_REPORTED)

        private const val ON = "on"
        private const val OFF = "off"
        private const val SEEDED = "1"

        fun encodeSwitch(on: Boolean): String = if (on) ON else OFF

        /** "on"/"off"; anything else is treated as not stored (the build default applies). */
        fun decodeSwitch(v: String): Boolean? = when (v) { ON -> true; OFF -> false; else -> null }

        /** The known features in a `/health` map, by relay name; unknown names are dropped. */
        fun fromRelay(reported: Map<String, Boolean>): Map<Feature, Boolean> =
            reported.entries.mapNotNull { (k, v) -> Feature.byRelayName(k)?.let { it to v } }.toMap()

        /**
         * The seed rule: only a [seedable] build, with nothing stored yet and not
         * seeded before, and only from a map that names at least one feature.
         */
        fun shouldSeed(seedable: Boolean, anyStored: Boolean, seeded: Boolean, known: Map<Feature, Boolean>): Boolean =
            seedable && !anyStored && !seeded && known.isNotEmpty()

        /**
         * Whether the Settings row for [f] says "The relay reports this as
         * unavailable.": the relay said false for it. For the family map that
         * verdict describes the relay's own `/map` page, so it applies only when
         * the map is the relay's ([mapOnRelay], see [mapIsTheRelays]); a map
         * hosted elsewhere (the owner's) is not the relay's to judge.
         */
        fun reportedUnavailable(f: Feature, lastReported: Map<Feature, Boolean>, mapOnRelay: Boolean): Boolean {
            if (lastReported[f] != false) return false
            return f != Feature.FAMILY_MAP || mapOnRelay
        }

        /**
         * Whether the family map is the relay's own `/map` page, for
         * [reportedUnavailable]: a URL on the relay origin is; a URL on another
         * host (the owner's) is not; and no URL at all counts as the relay's,
         * because that is a stranger's first-run state and the relay's `/map` is
         * what they are about to type — its verdict is the one they need to see.
         */
        fun mapIsTheRelays(mapUrl: String, config: RelayConfig): Boolean =
            mapUrl.isBlank() || config.isRelayUrl(mapUrl)

        /** "facetime=1,map=0,…" in enum order; known names only. */
        fun encodeReported(known: Map<Feature, Boolean>): String =
            Feature.entries.mapNotNull { f -> known[f]?.let { "${f.relayName}=${if (it) 1 else 0}" } }.joinToString(",")

        fun decodeReported(v: String?): Map<Feature, Boolean> {
            if (v.isNullOrEmpty()) return emptyMap()
            return v.split(',').mapNotNull { item ->
                val eq = item.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val f = Feature.byRelayName(item.substring(0, eq)) ?: return@mapNotNull null
                when (item.substring(eq + 1)) { "1" -> f to true; "0" -> f to false; else -> null }
            }.toMap()
        }
    }
}

/**
 * The process-wide switches, observable from Compose. Wraps a [FeatureFlags]
 * over the "relay_config" prefs file; [init] runs once per process from
 * [ImsgApp], like [RelayConfigStore.init], so every component (the push
 * service, the voice and FaceTime activities) reads the same values.
 */
object Features {

    private class PrefsBacking(private val prefs: SharedPreferences) : FeatureFlags.Backing {
        override fun read(): Map<String, String> =
            FeatureFlags.KEYS.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }.toMap()

        /** Touches only [FeatureFlags.KEYS]; the relay store's keys in the same file are left alone. */
        override fun write(values: Map<String, String>) {
            prefs.edit().also { e ->
                FeatureFlags.KEYS.forEach { k ->
                    val v = values[k]
                    if (v.isNullOrEmpty()) e.remove(k) else e.putString(k, v)
                }
            }.commit()
        }
    }

    @Volatile
    private var flags: FeatureFlags = FeatureFlags(BuildFeatureDefault.fromBuildConfig(), FeatureFlags.InMemoryBacking())

    @Volatile
    private var initialized = false

    private var switches: Map<Feature, Boolean> by mutableStateOf(flags.switches)

    /** The relay's last reported map (known names), for the Settings annotations only. */
    var lastReported: Map<Feature, Boolean> by mutableStateOf(flags.lastReported)
        private set

    val familyMap: Boolean get() = isOn(Feature.FAMILY_MAP)
    val faceTime: Boolean get() = isOn(Feature.FACE_TIME)
    val translation: Boolean get() = isOn(Feature.TRANSLATION)
    val voiceAssistant: Boolean get() = isOn(Feature.VOICE_ASSISTANT)

    /** The Map button's real condition: the switch, and a map URL to load (Relay section). */
    val familyMapUsable: Boolean get() = familyMap && RelayConfigStore.current.mapUrl.isNotBlank()

    fun isOn(f: Feature): Boolean = switches[f] ?: false

    /** True when the Settings row for [f] should say the relay reports it unavailable. */
    fun reportedUnavailable(f: Feature): Boolean {
        val cfg = RelayConfigStore.current
        return FeatureFlags.reportedUnavailable(f, lastReported, FeatureFlags.mapIsTheRelays(cfg.mapUrl, cfg))
    }

    /** Loads the stored values. Called once per process by [ImsgApp]; later calls are no-ops. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext
                .getSharedPreferences(RelayConfigStore.PREFS_FILE, Context.MODE_PRIVATE)
            flags = FeatureFlags(BuildFeatureDefault.fromBuildConfig(), PrefsBacking(prefs))
            publish()
            initialized = true
        }
    }

    /** A flip in Settings. */
    @Synchronized
    fun set(f: Feature, on: Boolean) {
        flags.set(f, on)
        publish()
    }

    /** A successful Test connection or Save probe: records the report and seeds a fresh stranger build once. */
    @Synchronized
    fun relayReported(outcome: ProbeOutcome.Connected) {
        flags.relayReported(outcome.features)
        publish()
    }

    private fun publish() {
        switches = flags.switches
        lastReported = flags.lastReported
    }
}

/**
 * The Translation switch over the conversation's translation paths
 * (MainActivity.kt). The switch is one choke point: ChatVM.translate and
 * ChatVM.autoTranslateThread return at once while it is off, so neither a
 * tapped chip nor a thread flagged "Always translate" on the relay reaches
 * `/translate`. The one control that stays is the pin menu's "Stop translating"
 * for a thread already flagged, so that relay-side flag can still be cleared
 * without turning the feature back on.
 */
object TranslationGate {
    /** The pin menu's Always/Stop translating button: while the feature is on, or for a thread already flagged. */
    fun showAutoTranslateControl(featureOn: Boolean, threadFlagged: Boolean): Boolean = featureOn || threadFlagged
}
