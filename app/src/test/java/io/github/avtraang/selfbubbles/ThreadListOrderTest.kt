package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads of the conversation list overlap, and their answers can arrive in the
 * other order (hardening audit 2026-10-08, G4-F4): one message for the open chat
 * starts a load at once and another after the chat was marked read, and the
 * relay answers each only after it has asked Beeper. The list was replaced by
 * whichever answer came last, so the older snapshot could win: a chat just read
 * shown as unread again, until the next load.
 */
class ThreadListOrderTest {

    /** What ChatVM does with each answer: [arrivals] are load numbers in the order their answers arrive. */
    private fun listShownAfter(vararg arrivals: Int): Int {
        var shown = 0
        for (load in arrivals) if (threadListApplies(load, shown)) shown = load
        return shown
    }

    @Test fun theFirstListIsShown() {
        assertTrue(threadListApplies(load = 1, shown = 0))
    }

    @Test fun aNewerListReplacesAnOlderOne() {
        assertTrue(threadListApplies(load = 2, shown = 1))
        assertTrue(threadListApplies(load = 9, shown = 3))
    }

    @Test fun anOlderListThatArrivesLate_doesNotReplaceANewerOne() {
        assertFalse(threadListApplies(load = 1, shown = 2))
        assertFalse(threadListApplies(load = 3, shown = 9))
    }

    @Test fun answersInOrder_endWithTheLast() {
        assertEquals(3, listShownAfter(1, 2, 3))
    }

    @Test fun answersOutOfOrder_stillEndWithTheLoadStartedLast() {
        assertEquals(2, listShownAfter(2, 1))
        assertEquals(3, listShownAfter(2, 3, 1))
        assertEquals(3, listShownAfter(3, 1, 2))
    }

    @Test fun aLoadThatBroughtNoList_isSimplyNotInTheOrder() {
        // Load 2 failed and brought nothing: load 3 follows load 1 on screen.
        assertEquals(3, listShownAfter(1, 3))
        // Only load 1 ever answers: its list stays, which is better than none.
        assertEquals(1, listShownAfter(1))
    }
}
