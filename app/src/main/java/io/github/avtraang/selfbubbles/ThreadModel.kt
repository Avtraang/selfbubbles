package io.github.avtraang.selfbubbles

// Thread and message model helpers: pure functions over Thread and Msg with no Compose or
// Android dependencies. Moved verbatim out of MainActivity.kt (SelfBubbles split, step C1).

// Google Messages (SMS/RCS via Beeper) — green, like iOS (MessagesTheme.colors.bubbleSms).
fun isSms(network: String?) = network == "gmessages"
/** [this] with the relay's send labels (network/service/via_label/send_warning) from [f]. */
fun Thread.withLabelsFrom(f: Thread) = copy(
    network = f.network ?: network,
    service = f.service, via_label = f.via_label, send_warning = f.send_warning,
)
// Text (SMS/RCS) rather than iMessage: Google Messages threads, plus Mac-side
// chats whose service the relay reports as SMS or RCS.
fun isTextThread(t: Thread?) = t?.network == "gmessages" || t?.service == "SMS" || t?.service == "RCS"
fun isTextMsg(m: Msg) = isSms(m.network) || m.service == "SMS" || m.service == "RCS"

internal val TAPBACK_EMOJI = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE02", "\u203C\uFE0F", "\u2753")
internal val URL_REGEX = Regex("""https?://\S+""")

// Zero-width non-joiner: keeps two Persian or Arabic initials from joining into one shape. Invisible otherwise.
private val InitialsSeparator = Char(0x200C).toString()

/** First letters of the first two words. Empty when no word starts with a letter: short codes and phone numbers. */
fun initialsOf(title: String): String =
    title.trim().split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2)
        .joinToString(InitialsSeparator) { it.take(1).uppercase() }

/**
 * A thread's display name (spec L7). The relay joins group participants' names before sending,
 * and a participant with no name leaves a trailing ", ": drop blank parts so no title ends in a
 * comma. Null when there is no usable name, so callers fall back to the guid.
 */
fun cleanTitle(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return name.split(", ").filter { it.isNotBlank() }.joinToString(", ")
        .trimEnd(',', ' ').ifBlank { null }
}

val Thread.title: String get() = cleanTitle(chat_name) ?: chat_guid
