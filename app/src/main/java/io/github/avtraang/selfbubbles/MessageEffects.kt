package io.github.avtraang.selfbubbles

// iMessage effects as the app knows them: Apple's thirteen effect ids, each with the name iOS
// shows for it and whether it plays on the bubble or over the whole screen. Plain Kotlin
// (MessageEffectsTest). The relay serves a message's effect as "effect" (chat.db's
// expressive_send_style_id, null for a message sent without one) and takes one on /send; an
// older relay sends none and ignores the field, which is why nothing here is gated on the
// relay's capabilities. A message with an effect says so under its bubble, in iOS's words
// ("Sent with Confetti"). How and when an effect plays is EffectPlayback.kt (the rules and the
// specs) and EffectPlayer.kt (the engine): the bubble effects play on the bubble, Invisible Ink
// hides its words until a tap, and the screen effects wait for round 2.

/** Where an effect plays: on the bubble itself, or over the whole conversation. */
enum class EffectKind { BUBBLE, SCREEN }

/** One effect: Apple's [id] as chat.db and BlueBubbles spell it, the [name] iOS shows, and its [kind]. */
data class MessageEffect(val id: String, val name: String, val kind: EffectKind)

/** The name shown for an id the app does not know (a newer iOS, a spelling the catalogue lacks). */
const val UNKNOWN_EFFECT_NAME = "Effect"

/** The toast after a send that asked for an effect which the relay delivered without it. */
const val SENT_WITHOUT_EFFECT = "Sent without the effect"

object MessageEffects {
    const val INVISIBLE_INK = "com.apple.MobileSMS.expressivesend.invisibleink"

    /** The four bubble effects, in iOS's order. */
    val bubble: List<MessageEffect> = listOf(
        MessageEffect("com.apple.MobileSMS.expressivesend.impact", "Slam", EffectKind.BUBBLE),
        MessageEffect("com.apple.MobileSMS.expressivesend.loud", "Loud", EffectKind.BUBBLE),
        MessageEffect("com.apple.MobileSMS.expressivesend.gentle", "Gentle", EffectKind.BUBBLE),
        MessageEffect(INVISIBLE_INK, "Invisible Ink", EffectKind.BUBBLE),
    )

    /** The nine screen effects, in iOS's order. */
    val screen: List<MessageEffect> = listOf(
        MessageEffect("com.apple.messages.effect.CKEchoEffect", "Echo", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKSpotlightEffect", "Spotlight", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKHappyBirthdayEffect", "Balloons", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKConfettiEffect", "Confetti", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKHeartEffect", "Love", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKLasersEffect", "Lasers", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKFireworksEffect", "Fireworks", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKShootingStarEffect", "Shooting Star", EffectKind.SCREEN),
        MessageEffect("com.apple.messages.effect.CKSparklesEffect", "Celebration", EffectKind.SCREEN),
    )

    /** Every effect the picker offers and the relay accepts: the bubble ones, then the screen ones. */
    val all: List<MessageEffect> = bubble + screen

    private val byId: Map<String, MessageEffect> = all.associateBy { it.id }

    /** The effect with this id, or null for one the app does not know. */
    fun byId(id: String): MessageEffect? = byId[id]

    /** The name shown for [id]: iOS's for a known effect, [UNKNOWN_EFFECT_NAME] for any other. */
    fun displayName(id: String): String = byId[id]?.name ?: UNKNOWN_EFFECT_NAME
}

/**
 * The quiet line under a bubble sent with [effect], received or sent: iOS says
 * "Sent with Confetti" under both. Null for a message sent without one (the
 * relay's null, or an older relay's silence).
 */
fun effectCaption(effect: String?): String? =
    effect?.takeIf { it.isNotBlank() }?.let { "Sent with ${MessageEffects.displayName(it)}" }

/**
 * The caption under the bubble of [m], or null. Not under one of the owner's
 * own messages with nothing left to show: an Undo Send would otherwise leave a
 * lone "Sent with Slam" where the bubble was, as the "Edited" caption would
 * without the same rule ([showsEditedCaption]).
 */
fun effectCaptionFor(m: Msg): String? =
    if (m.is_from_me && isEmptied(m)) null else effectCaption(m.effect)

/**
 * Whether the composer of [t] offers the effect picker: an iMessage chat only.
 * Effects are iMessage's; a text thread (SMS or RCS on the Mac) and a Google
 * Messages chat have none, and the relay answers 422 to an effect for the
 * latter. A Google Messages chat is also known by its "bp:" guid ([isBridgeId]),
 * as Edit and Undo Send know it: a chat opened from a notification, a search hit
 * or compose is a bare stub without the relay's labels until the list has loaded
 * (ChatVM.open), and the long press would otherwise be offered there, the relay
 * would refuse the send, and "Send again" would repeat the effect to the same refusal.
 */
fun offersEffects(t: Thread?): Boolean = t != null && !isTextThread(t) && !isBridgeId(t.chat_guid)

/**
 * What the owner is told after a send that asked for an effect, from the
 * relay's `effect_sent`: nothing when the effect went with the text (true) or
 * the relay did not say (null: no effect was asked for, or an older relay);
 * [SENT_WITHOUT_EFFECT] when a fallback engine delivered the words alone.
 */
fun effectSendNotice(effectSent: Boolean?): String? = if (effectSent == false) SENT_WITHOUT_EFFECT else null
