package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind search-in-chat (InChatSearch.kt). */
class InChatSearchTest {

    private fun msg(rowid: Long, guid: String = "g$rowid") =
        Msg(rowid = rowid, guid = guid, chat_guid = "iMessage;-;+1")

    private fun sms(guid: String) = Msg(rowid = 0L, guid = guid, chat_guid = "bp:1")

    // ---- normalizeQuery ----

    @Test fun normalizeQuery_trimsAndRequiresTwoChars() {
        assertEquals("ab", normalizeQuery("  ab  "))
        assertEquals("hello world", normalizeQuery("hello world\n"))
        assertNull(normalizeQuery(""))
        assertNull(normalizeQuery("a"))
        assertNull(normalizeQuery("   a   "))
        assertNull(normalizeQuery("      "))
    }

    // ---- matchesHit / indexOfMessage ----

    @Test fun matchesHit_rowidForIMessage_guidForGoogleMessages() {
        assertTrue(matchesHit(msg(5), 5, null))
        assertTrue(matchesHit(msg(5), 5, "other"))          // rowid wins when it is set
        assertFalse(matchesHit(msg(5), 6, "g5"))
        assertTrue(matchesHit(sms("x"), 0, "x"))
        assertFalse(matchesHit(sms("x"), 0, "y"))
        assertFalse(matchesHit(sms("x"), 0, null))          // a rowid-0 hit without a guid matches nothing
        assertTrue(matchesHit(msg(5), 0, "g5"))             // rowid 0 means "match by guid", as ChatVM dedups
    }

    @Test fun indexOfMessage_isReverseLayoutAware() {
        val held = listOf(msg(10), msg(11), msg(12))        // oldest first, as held
        assertEquals(2, indexOfMessage(held, 10, null))     // oldest = highest index on screen
        assertEquals(1, indexOfMessage(held, 11, null))
        assertEquals(0, indexOfMessage(held, 12, null))     // newest = index 0
    }

    @Test fun indexOfMessage_googleMessagesByGuid() {
        val held = listOf(sms("a"), sms("b"), sms("c"))
        assertEquals(1, indexOfMessage(held, 0, "b"))
        assertNull(indexOfMessage(held, 0, "zzz"))
        assertNull(indexOfMessage(held, 0, null))
    }

    @Test fun indexOfMessage_missingOrEmpty() {
        assertNull(indexOfMessage(emptyList(), 1, null))
        assertNull(indexOfMessage(listOf(msg(1), msg(2)), 3, null))
    }

    // ---- needsPaging ----

    @Test fun needsPaging_onlyWhenNotFoundAndChatPagesUnderCap() {
        assertTrue(needsPaging(found = false, hasOlder = true, pagesLoaded = 0))
        assertTrue(needsPaging(found = false, hasOlder = true, pagesLoaded = JUMP_PAGE_CAP - 1))
        assertFalse(needsPaging(found = false, hasOlder = true, pagesLoaded = JUMP_PAGE_CAP))
        assertFalse(needsPaging(found = false, hasOlder = false, pagesLoaded = 0))   // Google Messages chats
        assertFalse(needsPaging(found = true, hasOlder = true, pagesLoaded = 0))
        assertTrue(needsPaging(found = false, hasOlder = true, pagesLoaded = 2, cap = 3))
        assertFalse(needsPaging(found = false, hasOlder = true, pagesLoaded = 3, cap = 3))
    }

    @Test fun pageCapIsForty() {
        assertEquals(40, JUMP_PAGE_CAP)
    }

    // ---- ownsJumpSlot ----

    @Test fun ownsJumpSlot_onlyTheHeldJumpMayClearTheState() {
        val older = Any()
        val newer = Any()
        assertTrue(ownsJumpSlot(current = older, finishing = older))      // the only jump, ending: clears
        // A second tap replaced the slot before the older jump's cancellation landed:
        // the older one must leave the newer one's state alone.
        assertFalse(ownsJumpSlot(current = newer, finishing = older))
        assertTrue(ownsJumpSlot(current = newer, finishing = newer))      // the newer one, ending later: clears
        assertFalse(ownsJumpSlot(current = null, finishing = older))      // already cleared
        assertFalse(ownsJumpSlot(current = null, finishing = null))
        assertFalse(ownsJumpSlot(current = older, finishing = null))
    }

    @Test fun ownsJumpSlot_isIdentityNotEquality() {
        // Two equal-but-distinct values are still different jumps.
        val a = "jump"; val b = String(charArrayOf('j', 'u', 'm', 'p'))
        assertEquals(a, b)
        assertFalse(ownsJumpSlot(current = a, finishing = b))
    }

    // ---- highlight timing ----

    @Test fun highlightAlpha_fadesLinearlyToZero() {
        assertEquals(HIGHLIGHT_ALPHA, highlightAlpha(1000, 1000), 1e-6f)
        assertEquals(HIGHLIGHT_ALPHA / 2f, highlightAlpha(1000, 1000 + HIGHLIGHT_MS / 2), 1e-6f)
        assertEquals(0f, highlightAlpha(1000, 1000 + HIGHLIGHT_MS), 1e-6f)
        assertEquals(0f, highlightAlpha(1000, 1000 + HIGHLIGHT_MS * 10), 1e-6f)
        assertEquals(HIGHLIGHT_ALPHA, highlightAlpha(1000, 900), 1e-6f)      // a clock skew before the start: full tint
        assertEquals(0f, highlightAlpha(1000, 1000, durationMs = 0), 1e-6f)
    }

    @Test fun isHighlightedNow_withinDurationOnly() {
        assertTrue(isHighlightedNow(0, 0))
        assertTrue(isHighlightedNow(0, HIGHLIGHT_MS - 1))
        assertFalse(isHighlightedNow(0, HIGHLIGHT_MS))
        assertFalse(isHighlightedNow(0, HIGHLIGHT_MS + 1))
        assertTrue(isHighlightedNow(0, 5, durationMs = 10))
        assertFalse(isHighlightedNow(0, 10, durationMs = 10))
    }

    @Test fun bubbleHighlight_onlyItsMessageAndOnlyWhileFading() {
        val h = BubbleHighlight(rowid = 7, guid = null, startedAt = 100)
        assertTrue(h.isHighlightedNow(msg(7), 100))
        assertEquals(HIGHLIGHT_ALPHA, h.alphaFor(msg(7), 100), 1e-6f)
        assertFalse(h.isHighlightedNow(msg(8), 100))                 // another bubble
        assertEquals(0f, h.alphaFor(msg(8), 100), 1e-6f)
        assertFalse(h.isHighlightedNow(msg(7), 100 + HIGHLIGHT_MS))  // run out
        val g = BubbleHighlight(rowid = 0, guid = "gm", startedAt = 0)
        assertTrue(g.isHighlightedNow(sms("gm"), 10))
        assertFalse(g.isHighlightedNow(sms("other"), 10))
    }

    // ---- result row text ----

    @Test fun searchHitTime_emptyWithoutDate_formattedOtherwise() {
        assertEquals("", searchHitTime(null))
        val longAgo = searchHitTime(0.0)                     // 1970: a short date, never a time of day
        assertTrue(longAgo.isNotEmpty())
        assertFalse(longAgo.contains(':'))
        val now = System.currentTimeMillis() / 1000.0
        assertTrue(searchHitTime(now).contains(':'))         // today: time of day
    }

    @Test fun searchHitSnippet_collapsesWhitespace() {
        assertEquals("a b c", searchHitSnippet("  a \n\n b\t c  "))
        assertEquals("", searchHitSnippet("   "))
    }
}
