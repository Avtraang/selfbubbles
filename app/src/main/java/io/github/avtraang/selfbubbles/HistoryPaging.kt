package io.github.avtraang.selfbubbles

// Pure helpers for paging older messages into an open chat (ChatVM.loadOlder and
// the conversation list's "near the oldest end" trigger). No Android or Compose
// types in this file, so everything here is covered by JVM unit tests
// (HistoryPagingTest).

/** How many messages one page asks the relay for; also the size of the first page. */
const val HISTORY_PAGE_SIZE = 50

/** Load the next page when the last visible item is this close to the oldest end of the list. */
const val HISTORY_PREFETCH_ITEMS = 5

/**
 * True when every message has a real rowid, i.e. the chat is an iMessage chat.
 * Google Messages (Beeper) rows carry rowid 0, and the relay ignores `before=`
 * for them, so they are never paged. An empty list has nothing to page.
 */
fun allHaveRowids(messages: List<Msg>): Boolean =
    messages.isNotEmpty() && messages.all { it.rowid > 0L }

/**
 * Whether there may be more history before [page], the page just received:
 * only when it was full (at least [pageSize] messages) and the chat pages at
 * all ([allHaveRowids]). A short or empty page means the relay reached the
 * start of the chat.
 */
fun pageHasOlder(page: List<Msg>, pageSize: Int = HISTORY_PAGE_SIZE): Boolean =
    page.size >= pageSize && allHaveRowids(page)

/**
 * The `before` for the next older page: the smallest rowid held. Null when
 * nothing is held or any message lacks a rowid (a chat that is not paged).
 */
fun nextBefore(held: List<Msg>): Long? =
    if (allHaveRowids(held)) held.minOf { it.rowid } else null

/**
 * [page] (older messages) merged in front of [held]: messages whose rowid is
 * already held are dropped, and the result keeps today's order, ascending by
 * rowid (the order the relay returns and the chat list expects). Messages
 * without a rowid in [held] (defensive: they are never paged) stay where the
 * sort puts them, at the front.
 */
fun mergeOlderPage(held: List<Msg>, page: List<Msg>): List<Msg> {
    if (page.isEmpty()) return held
    val known = HashSet<Long>(held.size)
    for (m in held) if (m.rowid != 0L) known.add(m.rowid)
    val fresh = page.filter { it.rowid != 0L && known.add(it.rowid) }
    if (fresh.isEmpty()) return held
    return (fresh + held).sortedBy { it.rowid }
}

/**
 * True when [held] already has [msg]. Google Messages rows carry rowid 0 and
 * unique guids, iMessage rows unique rowids: compare on whichever is meaningful,
 * or every Google Messages message would collide on rowid 0.
 */
fun holdsMessage(held: List<Msg>, msg: Msg): Boolean =
    held.any { if (msg.rowid != 0L) it.rowid == msg.rowid else it.guid == msg.guid }

/**
 * [held] with the messages of [page] (the chat's latest page, read again) that
 * it lacks, added at the newest end; [held] itself when it lacks none. Nothing
 * held is dropped or replaced, so older pages already loaded stay. An iMessage
 * chat keeps its order by rowid.
 */
fun mergeLatestPage(held: List<Msg>, page: List<Msg>): List<Msg> {
    val fresh = ArrayList<Msg>()
    for (m in page) if (!holdsMessage(held, m) && !holdsMessage(fresh, m)) fresh.add(m)
    if (fresh.isEmpty()) return held
    val all = held + fresh
    return if (allHaveRowids(all)) all.sortedBy { it.rowid } else all
}

/**
 * Whether the viewport is close enough to the oldest end to fetch more. With
 * `reverseLayout` the oldest message has the highest index, so this is the
 * last visible index ([lastVisibleIndex], -1 when nothing is laid out) being
 * within [threshold] items of `itemCount - 1`. Nothing to load from an empty list.
 */
fun nearOldestEnd(lastVisibleIndex: Int, itemCount: Int, threshold: Int = HISTORY_PREFETCH_ITEMS): Boolean =
    itemCount > 0 && lastVisibleIndex >= 0 && lastVisibleIndex >= itemCount - 1 - threshold

/**
 * What a page request was started for: the chat it was asked for and the paging
 * generation at that moment. ChatVM bumps the generation whenever the held list
 * is about to be replaced (open, back, the reconnect reload), so a request that
 * predates the bump describes a list that no longer exists.
 */
data class PageRequest(val chatGuid: String, val generation: Int)

/**
 * Whether a page fetched for [request] may still be applied to the held list:
 * the open chat is the same AND nothing reset paging since the request started.
 * Both are needed: a reconnect reload keeps the chat but replaces the list (an
 * older page merged into it would leave a gap), and a late first page from a
 * previous chat would overwrite the current chat's list.
 */
fun pageStillApplies(request: PageRequest, currentGuid: String?, currentGeneration: Int): Boolean =
    request.chatGuid == currentGuid && request.generation == currentGeneration

/**
 * The list key of the newest message, or null for an empty list: the scroll-to-
 * bottom effect keys on this (not on the list size) so that older pages, which
 * only lengthen the list at the far end, do not pull the viewport down.
 */
fun newestKey(messages: List<Msg>): Any? =
    messages.lastOrNull()?.let { if (it.rowid != 0L) it.rowid else it.guid }
