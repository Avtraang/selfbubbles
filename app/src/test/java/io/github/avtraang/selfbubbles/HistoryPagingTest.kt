package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind history paging (HistoryPaging.kt). */
class HistoryPagingTest {

    private fun msg(rowid: Long, guid: String = "g$rowid", chat: String = "iMessage;-;+1") =
        Msg(rowid = rowid, guid = guid, chat_guid = chat)

    private fun sms(guid: String) = Msg(rowid = 0L, guid = guid, chat_guid = "bp:1")

    private fun page(from: Long, count: Int): List<Msg> = (from until from + count).map { msg(it) }

    // ---- allHaveRowids ----

    @Test fun allHaveRowids_iMessageOnly() {
        assertTrue(allHaveRowids(page(100, 3)))
        assertFalse(allHaveRowids(emptyList()))
        assertFalse(allHaveRowids(listOf(sms("a"), sms("b"))))
        assertFalse(allHaveRowids(page(100, 3) + sms("x")))      // one gmessages row disqualifies the chat
    }

    // ---- pageHasOlder ----

    @Test fun pageHasOlder_fullPageOfRowids() {
        assertTrue(pageHasOlder(page(1000, 50)))
        assertTrue(pageHasOlder(page(1000, 51)))                 // more than asked still counts as full
    }

    @Test fun pageHasOlder_shortEmptyOrSms() {
        assertFalse(pageHasOlder(page(1000, 49)))
        assertFalse(pageHasOlder(emptyList()))
        assertFalse(pageHasOlder(List(50) { sms("s$it") }))     // a full Beeper page is never paged
        assertTrue(pageHasOlder(page(1, 10), pageSize = 10))
        assertFalse(pageHasOlder(page(1, 9), pageSize = 10))
    }

    // ---- nextBefore ----

    @Test fun nextBefore_smallestRowidHeld() {
        assertEquals(7L, nextBefore(listOf(msg(9), msg(7), msg(12))))
        assertEquals(1L, nextBefore(listOf(msg(1))))
    }

    @Test fun nextBefore_nullWhenNothingToPage() {
        assertNull(nextBefore(emptyList()))
        assertNull(nextBefore(listOf(sms("a"))))
        assertNull(nextBefore(listOf(msg(5), sms("a"))))
    }

    // ---- mergeOlderPage ----

    @Test fun merge_prependsOlderPageInOrder() {
        val held = page(100, 3)                                  // 100, 101, 102
        val older = page(50, 3)                                  // 50, 51, 52
        val merged = mergeOlderPage(held, older)
        assertEquals(listOf(50L, 51L, 52L, 100L, 101L, 102L), merged.map { it.rowid })
    }

    @Test fun merge_dedupesByRowid() {
        val held = page(100, 3)
        val older = listOf(msg(98), msg(99), msg(100, guid = "dup"), msg(101, guid = "dup2"))
        val merged = mergeOlderPage(held, older)
        assertEquals(listOf(98L, 99L, 100L, 101L, 102L), merged.map { it.rowid })
        assertEquals("g100", merged[2].guid)                     // the held copy wins, not the page's
    }

    @Test fun merge_keepsHeldWhenPageEmptyOrAllKnown() {
        val held = page(100, 3)
        assertEquals(held, mergeOlderPage(held, emptyList()))
        assertEquals(held, mergeOlderPage(held, page(100, 3)))
        assertEquals(held, mergeOlderPage(held, listOf(sms("ignored"))))   // rowid 0 is never merged
    }

    @Test fun merge_sortsAnOutOfOrderPage() {
        val held = page(100, 2)
        val older = listOf(msg(60), msg(55), msg(58))
        assertEquals(listOf(55L, 58L, 60L, 100L, 101L), mergeOlderPage(held, older).map { it.rowid })
    }

    @Test fun merge_keepsNewMessagesThatArrivedMeanwhile() {
        // A WebSocket message appended while the page was in flight stays at the newest end.
        val held = page(100, 3) + msg(200)
        val merged = mergeOlderPage(held, page(50, 2))
        assertEquals(listOf(50L, 51L, 100L, 101L, 102L, 200L), merged.map { it.rowid })
    }

    // ---- nearOldestEnd ----

    @Test fun nearOldestEnd_withinThresholdOfLastIndex() {
        // 50 items: lastIndex 49, threshold 5 -> fires from index 44 on.
        assertTrue(nearOldestEnd(49, 50))
        assertTrue(nearOldestEnd(44, 50))
        assertFalse(nearOldestEnd(43, 50))
        assertFalse(nearOldestEnd(10, 50))
    }

    @Test fun nearOldestEnd_emptyOrNothingLaidOut() {
        assertFalse(nearOldestEnd(-1, 50))
        assertFalse(nearOldestEnd(0, 0))
        assertFalse(nearOldestEnd(-1, 0))
    }

    @Test fun nearOldestEnd_shortListsCountAsNear() {
        // Fewer items than the threshold: whatever is visible is near the end.
        assertTrue(nearOldestEnd(0, 3))
        assertTrue(nearOldestEnd(2, 3))
        assertTrue(nearOldestEnd(0, 1))
        assertFalse(nearOldestEnd(0, 7, threshold = 5))
        assertTrue(nearOldestEnd(1, 7, threshold = 5))
    }

    // ---- pageStillApplies ----

    @Test fun pageStillApplies_sameChatSameGeneration() {
        assertTrue(pageStillApplies(PageRequest("A", 3), "A", 3))
        assertFalse(pageStillApplies(PageRequest("A", 3), "B", 3))   // another chat is open
        assertFalse(pageStillApplies(PageRequest("A", 3), null, 3))  // back(): no chat open
        assertFalse(pageStillApplies(PageRequest("A", 3), "A", 4))   // same chat, list replaced since
    }

    @Test fun reconnectReload_dropsOlderPageInFlight() {
        // Finding 1. Held 1000..1300 with before=1000 in flight; the socket reconnects and
        // the reload replaces the list with the latest 50 (1251..1300). The reload bumps the
        // generation, so the in-flight page [950..999] must NOT be merged: it would leave
        // 1000..1250 missing and the next before would start below the gap.
        var generation = 1
        val chat = "iMessage;-;+1"
        var held = page(1000, 301)
        val older = PageRequest(chat, generation)              // loadOlder() started here
        assertEquals(1000L, nextBefore(held))

        generation++                                           // reconnect: resetPaging() + reload
        held = page(1251, 50)
        val inFlight = page(950, 50)
        if (pageStillApplies(older, chat, generation)) held = mergeOlderPage(held, inFlight)

        assertEquals((1251L..1300L).toList(), held.map { it.rowid })   // no gap below the fresh page
        assertEquals(1251L, nextBefore(held))                           // next page continues from it
    }

    @Test fun reconnectReload_withoutGenerationBumpWouldLeaveGap() {
        // The same scenario with only the guid guard: documents why the generation is needed.
        val chat = "iMessage;-;+1"
        val merged = mergeOlderPage(page(1251, 50), page(950, 50))
        assertEquals(100, merged.size)
        assertTrue(merged.none { it.rowid in 1000L..1250L })   // the silent gap
        assertEquals(950L, nextBefore(merged))                  // and paging would continue below it
        assertTrue(pageStillApplies(PageRequest(chat, 1), chat, 1))   // guid alone lets it through
    }

    @Test fun lateFirstPage_fromPreviousChatIsDropped() {
        // Finding 2. Tap A (slow), then B (fast): B's page lands first, then A's.
        var generation = 0
        var current: String? = null
        var held = emptyList<Msg>()
        var hasOlder = false

        fun open(chat: String): PageRequest { current = chat; generation++; hasOlder = false; return PageRequest(chat, generation) }
        fun land(req: PageRequest, pageOf: List<Msg>) {
            if (!pageStillApplies(req, current, generation)) return
            held = pageOf; hasOlder = pageHasOlder(pageOf)
        }

        val reqA = open("A")
        val reqB = open("B")
        val pageB = page(5000, 50).map { it.copy(chat_guid = "B") }
        val pageA = page(100, 50).map { it.copy(chat_guid = "A") }

        land(reqB, pageB)
        land(reqA, pageA)                                       // late: must be ignored whole

        assertTrue(held.all { it.chat_guid == "B" })
        assertTrue(hasOlder)
        assertEquals(5000L, nextBefore(held))                   // B's smallest rowid, not A's 100
    }

    @Test fun lateFirstPage_reopeningSameChatDropsTheOlderRequest() {
        // A -> B -> A: only the page from the latest open(A) counts.
        var generation = 0
        val first = PageRequest("A", ++generation)
        ++generation                                            // open(B)
        val third = PageRequest("A", ++generation)              // open(A) again
        assertFalse(pageStillApplies(first, "A", generation))
        assertTrue(pageStillApplies(third, "A", generation))
    }

    // ---- newestKey ----

    @Test fun newestKey_rowidForIMessageGuidForSms() {
        assertEquals(102L, newestKey(page(100, 3)))
        assertEquals("b", newestKey(listOf(sms("a"), sms("b"))))
        assertNull(newestKey(emptyList()))
    }

    @Test fun newestKey_unchangedByAnOlderPage() {
        val held = page(100, 3)
        assertEquals(newestKey(held), newestKey(mergeOlderPage(held, page(50, 50))))
    }

    // ---- holdsMessage (the socket's echo against the held list) ----

    @Test fun holdsMessage_byRowidForIMessage_byGuidForSms() {
        val held = page(100, 3)
        assertTrue(holdsMessage(held, msg(101, guid = "another-guid")))     // the rowid is the identity
        assertFalse(holdsMessage(held, msg(103)))
        // Google Messages rows all carry rowid 0: only the guid tells them apart.
        val texts = listOf(sms("a"), sms("b"))
        assertTrue(holdsMessage(texts, sms("b")))
        assertFalse(holdsMessage(texts, sms("c")))
        assertFalse(holdsMessage(emptyList(), sms("a")))
    }

    // ---- mergeLatestPage (re-reading the chat after a send that ended without an answer) ----

    @Test fun mergeLatestPage_addsOnlyWhatIsNew_atTheNewestEnd() {
        val held = page(100, 3)                                             // 100, 101, 102
        val merged = mergeLatestPage(held, page(101, 4))                    // 101..104: two of them new
        assertEquals(listOf(100L, 101L, 102L, 103L, 104L), merged.map { it.rowid })
        assertEquals(104L, newestKey(merged))
    }

    @Test fun mergeLatestPage_nothingNew_isTheSameList() {
        val held = page(100, 3)
        assertTrue(mergeLatestPage(held, page(100, 3)) === held)
        assertTrue(mergeLatestPage(held, emptyList()) === held)
        assertTrue(mergeLatestPage(held, page(101, 1)) === held)
    }

    @Test fun mergeLatestPage_keepsOlderPagesAlreadyLoaded() {
        // 150 messages held after paging back; the latest page is only the newest 50 plus one new message.
        val held = page(1, 150)
        val merged = mergeLatestPage(held, page(101, 51))
        assertEquals(151, merged.size)
        assertEquals(1L, nextBefore(merged))                                // the oldest held is still the oldest
        assertEquals(151L, newestKey(merged))
    }

    @Test fun mergeLatestPage_sms_appendsNewGuids_inThePagesOrder() {
        val held = listOf(sms("a"), sms("b"))
        val merged = mergeLatestPage(held, listOf(sms("a"), sms("b"), sms("c"), sms("d"), sms("c")))
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.guid })    // each once, held ones untouched
        assertEquals("d", newestKey(merged))
    }

    @Test fun mergeLatestPage_intoAnEmptyList_isThePage() {
        assertEquals(page(100, 3), mergeLatestPage(emptyList(), page(100, 3)))
        assertEquals(listOf("a", "b"), mergeLatestPage(emptyList(), listOf(sms("a"), sms("b"))).map { it.guid })
    }
}
