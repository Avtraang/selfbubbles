package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation that cannot be read says so.
 *
 * Opening a chat reads its latest page from the relay. When that read failed
 * (no network), the failure was swallowed: the chat stayed empty, with no word
 * and no way to try again, and read as "my messages are gone" (seen on a phone
 * on 2026-10-08; audit A2-F4). Now the failure is shown with a Retry, and no
 * read, whether it is starting, failing or being retried, takes away what is
 * on the screen.
 *
 * What does not change: the app stores no messages and keeps none of a chat
 * that is not open. A chat reopened while offline therefore shows the failure
 * and nothing else, until a read succeeds.
 *
 * These are the rules ChatVM applies ([chatOpening], [chatLoadStarted],
 * [chatLoadEnded], [chatLeft]); messages are plain strings here.
 */
class ChatLoadingTest {
    private val shown = ChatPage(listOf("m1", "m2", "m3"))

    // ---- a read that fails ----

    @Test fun a_first_read_that_fails_shows_the_failure_instead_of_a_blank_chat() {
        val opened = chatOpening(ChatPage<String>(emptyList()), sameChat = false)
        val failed = chatLoadEnded(opened, page = null)
        assertEquals(ChatLoad.FAILED, failed.load)
        assertTrue(chatLoadFailureShows(failed.load))
        assertEquals(emptyList<String>(), failed.messages)
    }

    @Test fun a_reload_that_fails_keeps_what_is_on_the_screen() {
        val failed = chatLoadEnded(chatLoadStarted(shown), page = null)
        assertEquals(listOf("m1", "m2", "m3"), failed.messages)
        assertEquals(ChatLoad.FAILED, failed.load)
    }

    @Test fun the_message_is_the_one_asked_for() {
        assertEquals("Couldn't load this conversation. Check your connection and retry.", CHAT_LOAD_FAILED_MESSAGE)
        assertEquals("Retry", CHAT_LOAD_RETRY_LABEL)
    }

    // ---- nothing is cleared before a read has succeeded ----

    @Test fun starting_a_read_takes_nothing_off_the_screen() {
        for (before in listOf(shown, shown.copy(load = ChatLoad.FAILED), shown.copy(load = ChatLoad.LOADING))) {
            val loading = chatLoadStarted(before)
            assertEquals(before.messages, loading.messages)
            assertEquals(ChatLoad.LOADING, loading.load)
            assertFalse(chatLoadFailureShows(loading.load))
        }
    }

    @Test fun opening_the_chat_that_is_already_open_keeps_what_is_shown() {
        val again = chatOpening(shown, sameChat = true)
        assertEquals(shown.messages, again.messages)
        assertEquals(ChatLoad.LOADING, again.load)
    }

    @Test fun another_chats_messages_never_stand_under_this_chats_name() {
        // Not "existing messages" of this chat: they belong to the one that had the screen before.
        val other = chatOpening(shown, sameChat = false)
        assertEquals(emptyList<String>(), other.messages)
        assertEquals(ChatLoad.LOADING, other.load)
    }

    // ---- Retry ----

    @Test fun retry_reads_again_and_a_page_that_arrives_is_shown() {
        val failed = chatLoadEnded(chatOpening(ChatPage<String>(emptyList()), sameChat = false), null)
        val retrying = chatLoadStarted(failed)
        assertEquals(ChatLoad.LOADING, retrying.load)
        val loaded = chatLoadEnded(retrying, listOf("m1", "m2"))
        assertEquals(ChatPage(listOf("m1", "m2"), ChatLoad.IDLE), loaded)
        assertFalse(chatLoadFailureShows(loaded.load))
    }

    @Test fun a_retry_that_fails_too_leaves_the_failure_and_the_messages_where_they_were() {
        val failed = chatLoadEnded(chatLoadStarted(shown), null)
        val again = chatLoadEnded(chatLoadStarted(failed), null)
        assertEquals(failed, again)
    }

    @Test fun a_retry_over_shown_messages_replaces_them_only_with_what_arrived() {
        val failed = chatLoadEnded(chatLoadStarted(shown), null)
        val loaded = chatLoadEnded(chatLoadStarted(failed), listOf("m1", "m2", "m3", "m4"))
        assertEquals(listOf("m1", "m2", "m3", "m4"), loaded.messages)
        assertEquals(ChatLoad.IDLE, loaded.load)
    }

    // ---- the connection comes back ----

    @Test fun after_a_reconnection_the_chat_loads_and_the_failure_goes_without_a_tap() {
        // What was seen on the phone: sent a text, went offline, left the chat, opened it again.
        var chat = chatLoadEnded(chatOpening(ChatPage<String>(emptyList()), sameChat = false), listOf("m1", "Test"))
        chat = chatLeft()                                           // back to the list: nothing of the chat is kept
        chat = chatLoadEnded(chatOpening(chat, sameChat = false), null)    // offline
        assertEquals(ChatPage<String>(emptyList(), ChatLoad.FAILED), chat) // the failure, not a silent blank
        chat = chatLoadEnded(chatLoadStarted(chat), listOf("m1", "Test"))  // the socket reopened and the chat was read
        assertEquals(ChatPage(listOf("m1", "Test"), ChatLoad.IDLE), chat)
    }

    @Test fun a_conversation_with_no_messages_yet_is_a_read_that_succeeded() {
        val empty = chatLoadEnded(chatOpening(ChatPage<String>(emptyList()), sameChat = false), emptyList())
        assertEquals(ChatLoad.IDLE, empty.load)
        assertFalse(chatLoadFailureShows(empty.load))
    }

    // ---- no local copy ----

    @Test fun leaving_a_chat_keeps_nothing_of_it() {
        assertEquals(ChatPage<String>(emptyList(), ChatLoad.IDLE), chatLeft<String>())
    }

    @Test fun the_failure_shows_only_when_the_last_read_failed() {
        assertTrue(chatLoadFailureShows(ChatLoad.FAILED))
        assertFalse(chatLoadFailureShows(ChatLoad.IDLE))
        assertFalse(chatLoadFailureShows(ChatLoad.LOADING))
    }
}
