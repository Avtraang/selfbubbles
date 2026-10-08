package io.github.avtraang.selfbubbles

import java.util.UUID

/** The relay form's fields, for pinning a validator message to the field it is about. */
enum class RelayField { BASE, TOKEN, CF, MAP }

/**
 * What the relay form (RelaySetupScreen.kt) holds while it is being edited: the
 * typed text of each field, the Cloudflare Access switch, and the config the
 * form was opened with. An immutable value — every keystroke makes a new one
 * with [copy] — so the composable just renders it, and all the logic here
 * (what the form would save, what is wrong with it, whether it changed) runs
 * in plain JVM tests.
 *
 * There is no WebSocket field: the WebSocket URL is derived from the relay URL
 * by `RelayConfigValidator.normalized`, except that an untouched relay URL keeps
 * the WebSocket URL it came with, so an owner whose build names one explicitly
 * does not have it replaced by the derived form on an unrelated edit.
 *
 * Nothing here is persisted (see [RelayFormSaver] for what survives process
 * death: never the token or the secret).
 */
data class RelayFormState(
    val base: String = "",
    val token: String = "",
    val cfEnabled: Boolean = false,
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val mapUrl: String = "",
    /** The config the form was opened with (the store's current); [dirty] compares against it. */
    val prefill: RelayConfig = RelayConfig.EMPTY,
) {
    /** The config Save would hand to the store: trimmed, WebSocket URL filled in, the CF pair blank when the switch is off. */
    fun toConfig(): RelayConfig {
        val typedBase = RelayConfig(base = base).base
        return RelayConfigValidator.normalized(
            RelayConfig(
                base = base,
                wsUrl = if (typedBase == prefill.base) prefill.wsUrl else "",
                token = token,
                cfClientId = if (cfEnabled) cfClientId else "",
                cfClientSecret = if (cfEnabled) cfClientSecret else "",
                mapUrl = mapUrl,
            ),
        )
    }

    /**
     * The one rule the form adds to the validator's: the Cloudflare switch is
     * on but neither of the pair is filled in. The produced config is valid
     * (no pair at all), but saving it would flip the switch back off without
     * a word, so it is a problem ([CF_PAIR_EMPTY]) until the pair is typed or
     * the switch turned off.
     */
    val cfPairEmpty: Boolean get() = cfEnabled && cfClientId.isBlank() && cfClientSecret.isBlank()

    /**
     * The rules the typed values break, as shown by the form: the validator's,
     * plus [cfPairEmpty]. The one thing dropped: a WebSocket-URL message while
     * the relay URL itself is faulty, since the form has no such field (the URL
     * is derived from the relay URL) and "WebSocket URL is required" would only
     * puzzle. [canSave] still answers for the validator's full list, so nothing
     * the validator refuses can be saved.
     */
    val problems: List<String>
        get() {
            val all = RelayConfigValidator.problems(toConfig())
            val baseBroken = all.any { it.startsWith("Relay URL") }
            val shown = if (baseBroken) all.filterNot { it.startsWith("WebSocket URL") } else all
            return if (cfPairEmpty) shown + CF_PAIR_EMPTY else shown
        }

    /** True when RelayConfigValidator would accept what the form would save, and the form's own rule holds. */
    val canSave: Boolean get() = RelayConfigValidator.problems(toConfig()).isEmpty() && !cfPairEmpty

    /**
     * The relay URL parses as https with a host, and the credentials can
     * travel as headers — all a Test connection needs. A missing token is
     * something Test reports ("rejected the token"), not something that blocks it.
     */
    val canTest: Boolean
        get() = toConfig().let { it.origin != null && RelayConfigValidator.credentialProblems(it).isEmpty() }

    /** True once what the form would save differs from what it was opened with. */
    val dirty: Boolean get() = toConfig() != from(prefill).toConfig()

    /** The problems about one field, by the field name every validator message starts with. */
    fun problemsFor(field: RelayField): List<String> = problems.filter { p -> prefixesOf(field).any { p.startsWith(it) } }

    /**
     * The [RelayField.CF] problems to draw under one of the pair's two fields:
     * a rule about that field's own text goes under it, and the pair rules
     * (both-or-neither, [CF_PAIR_EMPTY]) go under the field that needs input.
     */
    fun problemsForCf(secret: Boolean): List<String> = problemsFor(RelayField.CF).filter { p ->
        when {
            p.startsWith(CF_ID_PREFIX) -> !secret
            p.startsWith(CF_SECRET_PREFIX) -> secret
            p == CF_PAIR_EMPTY -> !secret
            else -> if (secret) cfClientSecret.isBlank() else cfClientId.isBlank()
        }
    }

    /** Problems no field claims (none today; kept so a new validator rule is never silently hidden). */
    val otherProblems: List<String>
        get() = problems.filter { p -> RelayField.entries.none { f -> prefixesOf(f).any { p.startsWith(it) } } }

    /** Resets to [config] (after a Save or a Reset, the store's new current). */
    fun reloaded(config: RelayConfig): RelayFormState = from(config)

    /** Never prints a credential: this ends up in logs and crash reports. */
    override fun toString(): String =
        "RelayFormState(base=$base, token=${if (token.isEmpty()) "unset" else "set"}, cf=${if (cfEnabled) "on" else "off"}, " +
            "map=${if (mapUrl.isEmpty()) "unset" else "set"}, dirty=$dirty)"

    companion object {
        /** The form's own rule, worded like the validator's: a field name first, never a value. */
        const val CF_PAIR_EMPTY =
            "Cloudflare Access is on: enter the service token's client id and secret, or turn the switch off."

        // How RelayConfigValidator.credentialProblems names the two CF values.
        private const val CF_ID_PREFIX = "Cloudflare Access client id"
        private const val CF_SECRET_PREFIX = "Cloudflare Access client secret"

        /** A form pre-filled from [config] (the owner sees their values; a stranger an empty form). */
        fun from(config: RelayConfig): RelayFormState = RelayFormState(
            base = config.base,
            token = config.token,
            cfEnabled = config.hasCfAccess,
            cfClientId = config.cfClientId,
            cfClientSecret = config.cfClientSecret,
            mapUrl = config.mapUrl,
            prefill = config,
        )

        // The WebSocket URL is derived from the relay URL, so its problems belong to that field.
        private fun prefixesOf(field: RelayField): List<String> = when (field) {
            RelayField.BASE -> listOf("Relay URL", "WebSocket URL")
            RelayField.TOKEN -> listOf("Token")
            RelayField.CF -> listOf("Cloudflare Access")
            RelayField.MAP -> listOf("Map URL")
        }
    }
}

/**
 * The token and the Cloudflare secret as typed, held in memory only. The
 * composable keeps one inside a ViewModel, which lives through a rotation and
 * is gone with the process; nothing here is ever written to disk. [session]
 * ties the two values to one opening of the form (see [RelayFormSaver]).
 */
class RelayDraft {
    var session: String? = null
    var token: String = ""
    var cfClientSecret: String = ""
}

/**
 * What of the relay form rides the saved instance state, and how the form
 * comes back from it. Saved: the draft's session id, the relay URL, the
 * Cloudflare switch and client id, and the map URL — never the token or the
 * secret. On restore those two come from the [RelayDraft] when it belongs to
 * the saved session (a rotation: the ViewModel lived on), and from the store
 * when it does not (the process died in between — a half-typed token or secret
 * is lost then, by design). A draft that is not the saved session's adopts
 * that session, so every later rotation keeps what is typed from then on.
 * Plain Kotlin, unit-tested without Compose.
 */
object RelayFormSaver {
    /** Opens the form on [stored]: a fresh session whose draft starts as the store's values. */
    fun open(stored: RelayFormState, draft: RelayDraft): RelayFormState {
        draft.session = UUID.randomUUID().toString()
        draft.token = stored.token
        draft.cfClientSecret = stored.cfClientSecret
        return stored
    }

    /** The saveable part of [f]; the token and the secret stay in [draft]. */
    fun save(f: RelayFormState, draft: RelayDraft): List<Any> =
        listOf(draft.session.orEmpty(), f.base, f.cfEnabled, f.cfClientId, f.mapUrl)

    /** The form after a restore of what [save] wrote, over [stored] (the store's current, as a form). */
    fun restore(saved: List<Any?>, draft: RelayDraft, stored: RelayFormState): RelayFormState {
        val session = saved[0] as String
        if (session.isEmpty() || session != draft.session) {
            draft.session = session.ifEmpty { UUID.randomUUID().toString() }
            draft.token = stored.token
            draft.cfClientSecret = stored.cfClientSecret
        }
        return stored.copy(
            base = saved[1] as String,
            cfEnabled = saved[2] as Boolean,
            cfClientId = saved[3] as String,
            mapUrl = saved[4] as String,
            token = draft.token,
            cfClientSecret = draft.cfClientSecret,
        )
    }
}
