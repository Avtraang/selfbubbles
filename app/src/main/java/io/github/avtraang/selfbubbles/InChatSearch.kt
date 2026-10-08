package io.github.avtraang.selfbubbles

// Pure helpers behind "search in this chat" (the Conversation screen's search
// mode: the top-bar field, the results overlay and the jump to a hit). No
// Android or Compose types in this file, so everything here is covered by JVM
// unit tests (InChatSearchTest).

/** The relay answers only queries of at least this many characters (shorter ones return nothing). */
const val SEARCH_MIN_QUERY_LENGTH = 2

/** How long typing is left alone before the query goes to the relay. */
const val SEARCH_DEBOUNCE_MS = 250L

/** How many hits one in-chat search asks for. */
const val SEARCH_IN_CHAT_LIMIT = 30

/** How many older pages a jump may load before giving up ("Too far back to jump"). */
const val JUMP_PAGE_CAP = 40

/** How long the jumped-to bubble stays tinted, fading out over the whole span. */
const val HIGHLIGHT_MS = 1500L

/** The tint's starting opacity over the bubble, before the fade. */
const val HIGHLIGHT_ALPHA = 0.35f

/**
 * The query as it goes to the relay: trimmed, or null when it is too short to
 * search ([SEARCH_MIN_QUERY_LENGTH]). Null means "show the messages", not "no results".
 */
fun normalizeQuery(raw: String): String? =
    raw.trim().takeIf { it.length >= SEARCH_MIN_QUERY_LENGTH }

/** A hit's identity in the held list: rowid for iMessage rows, guid for Google Messages rows (rowid 0). */
fun matchesHit(m: Msg, rowid: Long, guid: String?): Boolean =
    if (rowid != 0L) m.rowid == rowid else guid != null && m.guid == guid

/**
 * The list index to scroll to for the message with [rowid] (iMessage) or, when
 * [rowid] is 0, with [guid] (Google Messages), or null when it is not held.
 * [bubbles] is chronological (oldest first) as the chat holds it, and the
 * conversation list is `reverseLayout`, so index 0 is the newest message:
 * the answer is the position counted from the newest end.
 */
fun indexOfMessage(bubbles: List<Msg>, rowid: Long, guid: String?): Int? {
    val chrono = bubbles.indexOfFirst { matchesHit(it, rowid, guid) }
    return if (chrono < 0) null else bubbles.size - 1 - chrono
}

/**
 * Whether a jump that found nothing held should ask for an older page:
 * only while the chat can page ([hasOlder]) and fewer than [cap] pages have
 * been loaded for this jump. False means "Too far back to jump".
 */
fun needsPaging(found: Boolean, hasOlder: Boolean, pagesLoaded: Int, cap: Int = JUMP_PAGE_CAP): Boolean =
    !found && hasOlder && pagesLoaded < cap

/**
 * Whether a jump that has just ended (found, gave up or was cancelled) may clear
 * the screen's jump state: only while it is still the jump the screen holds. A
 * second result tap replaces the held jump at once, but the older jump's
 * cancellation lands later (its coroutine resumes on the UI dispatcher), so the
 * older one must leave the newer one's state alone or Cancel, the X and back
 * would lose their handle on the jump that is still paging.
 */
fun ownsJumpSlot(current: Any?, finishing: Any?): Boolean =
    finishing != null && current === finishing

/**
 * The tint opacity over the jumped-to bubble at [now], for a highlight that
 * started at [startedAt] (both in the same millisecond clock): [HIGHLIGHT_ALPHA]
 * at the start, fading linearly to 0 at [durationMs], and 0 from then on.
 */
fun highlightAlpha(startedAt: Long, now: Long, durationMs: Long = HIGHLIGHT_MS, peak: Float = HIGHLIGHT_ALPHA): Float {
    if (durationMs <= 0L) return 0f
    val elapsed = now - startedAt
    if (elapsed < 0L) return peak
    if (elapsed >= durationMs) return 0f
    return peak * (1f - elapsed.toFloat() / durationMs.toFloat())
}

/** Whether the highlight that started at [startedAt] is still showing at [now]. */
fun isHighlightedNow(startedAt: Long, now: Long, durationMs: Long = HIGHLIGHT_MS): Boolean =
    highlightAlpha(startedAt, now, durationMs) > 0f

/**
 * The bubble a jump landed on and when its highlight began: the one piece of
 * state the fade is driven from. A bubble that scrolls away and back within
 * [HIGHLIGHT_MS] resumes the fade where the clock says, not from the start.
 */
data class BubbleHighlight(val rowid: Long, val guid: String?, val startedAt: Long)

/** The tint over [m] at [now]: the fade's opacity when [m] is the highlighted message, 0 for every other. */
fun BubbleHighlight.alphaFor(m: Msg, now: Long, durationMs: Long = HIGHLIGHT_MS): Float =
    if (matchesHit(m, rowid, guid)) highlightAlpha(startedAt, now, durationMs) else 0f

/** Whether [m] is the message this highlight is for and the fade has not run out at [now]. */
fun BubbleHighlight.isHighlightedNow(m: Msg, now: Long, durationMs: Long = HIGHLIGHT_MS): Boolean =
    alphaFor(m, now, durationMs) > 0f

/**
 * The date on a result row, formatted like the thread list's timestamp (time of
 * day today, a short date otherwise); empty when the relay sent no date.
 */
fun searchHitTime(unix: Double?): String = unix?.let { fmtListTime(it) } ?: ""

/** What a result row shows beside its date: the snippet with runs of whitespace collapsed. */
fun searchHitSnippet(snippet: String): String = snippet.trim().replace(Regex("\\s+"), " ")
