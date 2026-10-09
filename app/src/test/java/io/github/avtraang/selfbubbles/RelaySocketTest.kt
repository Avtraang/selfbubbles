package io.github.avtraang.selfbubbles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SocketOwner] is driven here the way WsManager drives it: `attempt()` when
 * it connects, `opened` / `ended` from the socket's callbacks, `stop()` when
 * its screen is cleared.
 */
class RelaySocketTest {

    // ---- one screen ----

    @Test fun there_is_no_connection_until_one_opens() {
        val s = SocketLiveness()
        val screen = s.owner()
        val a = screen.attempt()
        assertFalse(s.live)
        screen.opened(a)
        assertTrue(s.live)
    }

    @Test fun a_connection_that_closes_or_fails_is_not_live() {
        val s = SocketLiveness()
        val screen = s.owner()
        val a = screen.attempt().also(screen::opened)
        screen.ended(a)
        assertFalse(s.live)
        screen.ended(a)                       // closing, then closed: told twice
        assertFalse(s.live)
    }

    @Test fun a_reconnection_is_live_again_once_it_opens() {
        val s = SocketLiveness()
        val screen = s.owner()
        val first = screen.attempt().also(screen::opened)
        screen.ended(first)                   // the relay restarted
        val second = screen.attempt()
        assertFalse(s.live)
        screen.opened(second)
        assertTrue(s.live)
    }

    @Test fun an_old_connection_ending_late_does_not_take_the_new_one_down() {
        val s = SocketLiveness()
        val screen = s.owner()
        val first = screen.attempt().also(screen::opened)
        val second = screen.attempt().also(screen::opened)      // the settings were saved: connect again
        screen.ended(first)                   // the cancelled socket's failure arrives after the new one opened
        assertTrue(s.live)
        screen.ended(second)
        assertFalse(s.live)
    }

    @Test fun a_connection_that_was_replaced_before_it_opened_does_not_count() {
        val s = SocketLiveness()
        val screen = s.owner()
        val first = screen.attempt()
        val second = screen.attempt()
        screen.opened(first)                  // its "open" arrives after it was dropped
        assertFalse(s.live)
        screen.opened(second)
        assertTrue(s.live)
    }

    // ---- the screen is recreated: two managers for a moment (the bug of 52ab0c5) ----

    @Test fun the_old_screen_stopping_does_not_take_the_new_screens_connection_down() {
        val s = SocketLiveness()
        val old = s.owner()
        old.attempt().also(old::opened)
        val new = s.owner()
        new.attempt().also(new::opened)       // the new screen's connection is up
        old.stop()                            // the old screen is cleared a moment later
        assertTrue(s.live)
    }

    @Test fun the_old_screens_late_callbacks_do_not_take_the_new_screens_connection_down() {
        val s = SocketLiveness()
        val old = s.owner()
        val oldSocket = old.attempt().also(old::opened)
        val new = s.owner()
        new.attempt().also(new::opened)
        old.stop()
        old.ended(oldSocket)                  // the failure its cancel triggers
        old.opened(oldSocket)                 // and nothing brings it back
        assertTrue(s.live)
    }

    @Test fun the_new_screen_is_not_live_before_its_own_connection_opens_once_the_old_one_is_gone() {
        val s = SocketLiveness()
        val old = s.owner()
        old.attempt().also(old::opened)
        val new = s.owner()
        val socket = new.attempt()
        old.stop()
        assertFalse(s.live)                   // no socket is open: a push must not be dropped now
        new.opened(socket)
        assertTrue(s.live)
    }

    @Test fun after_a_recreation_the_new_screen_reconnects_like_any_other() {
        val s = SocketLiveness()
        val old = s.owner()
        old.attempt().also(old::opened)
        val new = s.owner()
        val first = new.attempt().also(new::opened)
        old.stop()
        new.ended(first)                      // the relay restarted
        assertFalse(s.live)
        val second = new.attempt().also(new::opened)
        assertTrue(s.live)
        new.ended(first)                      // late
        assertTrue(s.live)
        new.ended(second)
        assertFalse(s.live)
    }

    @Test fun stopping_ends_only_what_is_ones_own_and_can_be_done_twice() {
        val s = SocketLiveness()
        val a = s.owner()
        val b = s.owner()
        b.attempt().also(b::opened)
        a.stop()                              // never connected
        a.stop()
        assertTrue(s.live)
        b.stop()
        b.stop()
        assertFalse(s.live)
    }

    // ---- what the state is for ----

    @Test fun a_push_for_the_open_chat_is_dropped_only_while_the_connection_can_show_it() {
        // In front, unlocked, this very chat open: the socket frame draws the message, so the push is redundant.
        assertTrue(pushIsRedundant(foreground = true, gated = false, openChat = "c1", chatGuid = "c1", socketLive = true))
        // No connection (the relay restarted, or dropped a connection that took nothing): nothing will draw it.
        assertFalse(pushIsRedundant(foreground = true, gated = false, openChat = "c1", chatGuid = "c1", socketLive = false))
    }
}
