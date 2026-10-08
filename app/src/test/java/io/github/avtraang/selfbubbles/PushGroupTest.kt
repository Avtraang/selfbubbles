package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A push for a group must look like a group: a reply typed into the
 * notification goes to everyone in it (hardening audit 2026-10-08). Synthetic names.
 */
class PushGroupTest {

    @Test fun theRelaysWord_decides() {
        assertTrue(pushIsGroup("1", chatName = "", sender = "Alice Anders"))       // unnamed group, title not cached
        assertTrue(pushIsGroup("1", "Alice Anders", "Alice Anders"))
        assertFalse(pushIsGroup("0", "+15550000001", "Alice Anders"))             // a 1:1 named by its number
    }

    @Test fun aRelayThatDoesNotSayIt_isJudgedByTheOldRule() {
        assertTrue(pushIsGroup(null, "Alice, Bob", "Alice Anders"))
        assertFalse(pushIsGroup(null, "", "Alice Anders"))
        assertFalse(pushIsGroup(null, "Alice Anders", "Alice Anders"))
        assertFalse(pushIsGroup("", "", "Alice Anders"))
    }

    @Test fun aGroupIsNeverTitledWithTheSender() {
        assertEquals("Group chat", pushChatTitle(true, chatName = "", sender = "Alice Anders"))
        assertEquals("Alice, Bob", pushChatTitle(true, "Alice, Bob", "Alice Anders"))
        assertEquals("Alice Anders", pushChatTitle(false, "", "Alice Anders"))
        assertEquals("Alice Anders", pushChatTitle(false, "Alice Anders", "Alice Anders"))
    }
}
