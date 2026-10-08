package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversation shortcuts after the launcher activity's class name changed
 * (ConversationShortcuts.kt): the first publish is forced, the shortcuts it does
 * not cover are judged one by one from the class their own intent names, rebuilt
 * in place when their chat is known and disabled when it is not, the retarget is
 * recorded only once it could do its work, and a notification stops reusing a
 * shortcut made for the old class.
 */
class ShortcutRetargetTest {

    private val current = "io.github.avtraang.selfbubbles.MainActivity"
    private val old = "com.example.oldpackage.MainActivity"

    private fun guid(n: Int) = "iMessage;-;+1555555010$n"
    private fun id(n: Int) = shortcutIdFor(guid(n))

    /** A held shortcut as the system hands it back: its class, and its own chat guid unless [guid] says otherwise. */
    private fun held(n: Int, className: String? = old, guid: String? = guid(n), enabled: Boolean = true) =
        HeldShortcut(id(n), className, guid, enabled)

    // ---- is a retarget due? force the publish? ----

    @Test fun aRetargetIsDue_whenNothingWasRecorded_orAnotherClassWas() {
        assertTrue(shortcutsNeedRetarget(null, current))           // an install from before the key existed
        assertTrue(shortcutsNeedRetarget(old, current))
        assertTrue(shortcutsNeedRetarget("", current))
        assertTrue(shortcutsNeedRetarget(current.lowercase(), current))   // class names are case-sensitive
    }

    @Test fun noRetarget_onceTheCurrentClassIsRecorded() {
        assertFalse(shortcutsNeedRetarget(current, current))
    }

    @Test fun force_table() {
        data class Row(val requested: Boolean, val due: Boolean, val forcedBefore: Boolean, val expected: Boolean)
        val rows = listOf(
            Row(requested = false, due = false, forcedBefore = false, expected = false),   // an ordinary refresh: debounced
            Row(requested = true, due = false, forcedBefore = false, expected = true),     // the Settings switch came back on
            Row(requested = false, due = true, forcedBefore = false, expected = true),     // the first publish after the rename
            Row(requested = false, due = true, forcedBefore = true, expected = false),     // a retarget that did not complete: once per process
            Row(requested = true, due = true, forcedBefore = true, expected = true),
            Row(requested = false, due = false, forcedBefore = true, expected = false),
        )
        for (r in rows) assertEquals("$r", r.expected, shortcutPublishIsForced(r.requested, r.due, r.forcedBefore))
    }

    // ---- which ids to rebuild, which to disable ----

    @Test fun retarget_table() {
        val map = mapOf(id(1) to guid(1), id(2) to guid(2), id(3) to guid(3), id(4) to guid(4))
        val known = setOf(guid(1), guid(2), guid(3), guid(6))      // chat 4 left the list (or lost its name)
        val shortcuts = listOf(
            held(1),                                               // the publish just rebuilt chat 1
            held(2),                                               // old class, chat known
            held(3, guid = null),                                  // old class, guid only in the map
            held(4),                                               // old class, chat unknown
            held(5),                                               // old class, chat unknown, not in the map either
            held(6, className = current),                          // already names this class: a pin that works
            held(7, className = current, guid = null),             // current, chat unknown: still left alone
        )

        val plan = planShortcutRetarget(
            retargetDue = true, currentComponent = current, held = shortcuts,
            publishedIds = setOf(id(1)), map = map, knownGuids = known,
        )

        assertEquals(mapOf(id(2) to guid(2), id(3) to guid(3)), plan.rebuild)
        assertEquals(setOf(id(4), id(5)), plan.disable)
    }

    @Test fun aShortcutThatAlreadyNamesThisClass_isNeverTouched() {
        // A phone that already ran a build with the new class: nothing recorded yet, but the pins work.
        val shortcuts = listOf(held(1, className = current), held(2, className = current, guid = null))
        val plan = planShortcutRetarget(true, current, shortcuts, emptySet(), emptyMap(), setOf(guid(9)))
        assertEquals(ShortcutRetarget(), plan)
    }

    @Test fun anOutOfDateShortcut_isFoundByItsOwnIntent_evenAfterTheRetargetWasRecorded() {
        // retargetDue is false (the current class is recorded), yet this one still names the old class:
        // it was disabled while its chat was not in the list, and the list has the chat now.
        val shortcuts = listOf(held(2, enabled = false), held(4, enabled = false))
        val plan = planShortcutRetarget(false, current, shortcuts, emptySet(), emptyMap(), setOf(guid(2)))
        assertEquals(mapOf(id(2) to guid(2)), plan.rebuild)
        assertTrue(plan.disable.isEmpty())                         // chat 4 is disabled already: not disabled again
    }

    @Test fun aClassTheSystemDidNotHandBack_countsAsOutOfDate_onlyWhileARetargetIsDue() {
        val shortcuts = listOf(held(2, className = null, guid = null), held(4, className = null, guid = null))
        val map = mapOf(id(2) to guid(2), id(4) to guid(4))
        val due = planShortcutRetarget(true, current, shortcuts, emptySet(), map, setOf(guid(2)))
        assertEquals(mapOf(id(2) to guid(2)), due.rebuild)
        assertEquals(setOf(id(4)), due.disable)
        assertEquals(ShortcutRetarget(), planShortcutRetarget(false, current, shortcuts, emptySet(), map, setOf(guid(2))))
    }

    @Test fun aGuidThatIsNotTheOneTheIdWasMadeFrom_isNotTrusted() {
        // The shortcut claims another chat than its id says: the map's entry decides.
        val odd = HeldShortcut(id(2), old, guid(3), enabled = true)
        val withMap = planShortcutRetarget(true, current, listOf(odd), emptySet(), mapOf(id(2) to guid(2)), setOf(guid(2), guid(3)))
        assertEquals(mapOf(id(2) to guid(2)), withMap.rebuild)
        val withoutMap = planShortcutRetarget(true, current, listOf(odd), emptySet(), emptyMap(), setOf(guid(2), guid(3)))
        assertTrue(withoutMap.rebuild.isEmpty())
        assertEquals(setOf(id(2)), withoutMap.disable)
    }

    @Test fun whatThePublishCovered_isNeitherRebuiltNorDisabled() {
        val plan = planShortcutRetarget(true, current, listOf(held(1), held(2)), setOf(id(1), id(2)), emptyMap(), setOf(guid(1)))
        assertEquals(ShortcutRetarget(), plan)
    }

    @Test fun nothingIsTouched_whenTheSystemCouldNotBeAsked() {
        assertEquals(ShortcutRetarget(), planShortcutRetarget(true, current, null, emptySet(), mapOf(id(1) to guid(1)), setOf(guid(1))))
    }

    @Test fun nothingIsDisabled_onTheWordOfAListThatKnowsNoChat() {
        // The first publish came before the list loaded, or the relay returned no thread.
        val plan = planShortcutRetarget(true, current, listOf(held(1), held(2)), emptySet(), emptyMap(), emptySet())
        assertEquals(ShortcutRetarget(), plan)
    }

    @Test fun nothingHeld_meansNothingLeft() {
        assertEquals(ShortcutRetarget(), planShortcutRetarget(true, current, emptyList(), setOf(id(1)), mapOf(id(1) to guid(1)), setOf(guid(1))))
    }

    @Test fun anIdThatIsNotAConversationShortcut_isNeverTouched() {
        val plan = planShortcutRetarget(
            retargetDue = true, currentComponent = current,
            held = listOf(
                HeldShortcut("compose", old, guid(2)), HeldShortcut("chat-not-a-hash", old, null), held(1),
            ),
            publishedIds = emptySet(),
            map = mapOf(id(1) to guid(1), "compose" to guid(2)),
            knownGuids = setOf(guid(1), guid(2)),
        )
        assertEquals(mapOf(id(1) to guid(1)), plan.rebuild)
        assertTrue(plan.disable.isEmpty())
    }

    @Test fun aRebuiltShortcut_keepsItsId() {
        val plan = planShortcutRetarget(true, current, listOf(held(2)), emptySet(), emptyMap(), setOf(guid(2)))
        val (rebuiltId, rebuiltGuid) = plan.rebuild.entries.single().toPair()
        assertEquals(shortcutIdFor(rebuiltGuid), rebuiltId)        // the id is still the chat's own
        assertEquals(SHORTCUT_CATEGORY_CONVERSATION, "android.shortcut.conversation")
    }

    @Test fun theDisabledMessage_namesNothing() {
        assertEquals("Out of date. Open the chat from the app.", SHORTCUT_OUTDATED_MESSAGE)
        assertFalse(SHORTCUT_OUTDATED_MESSAGE.any { it.isDigit() })
    }

    @Test fun aRebuiltCachedConversation_staysOffTheLauncher_whereTheUpdateWouldNotBeDropped() {
        assertTrue(rebuiltShortcutIsOffLauncher(pinned = false, sdk = 33))
        assertTrue(rebuiltShortcutIsOffLauncher(pinned = false, sdk = 36))
        // Up to Android 12L the compat library drops a launcher-excluded shortcut from an update: no mark there.
        assertFalse(rebuiltShortcutIsOffLauncher(pinned = false, sdk = 32))
        assertFalse(rebuiltShortcutIsOffLauncher(pinned = false, sdk = 28))
        // A home-screen pin is the owner's own and never carries the mark.
        assertFalse(rebuiltShortcutIsOffLauncher(pinned = true, sdk = 36))
        assertFalse(rebuiltShortcutIsOffLauncher(pinned = true, sdk = 30))
    }

    // ---- may the retarget be recorded as done? ----

    @Test fun final_table() {
        data class Row(val heldKnown: Boolean, val listEmpty: Boolean, val publishOk: Boolean, val rebuildOk: Boolean, val expected: Boolean)
        val rows = listOf(
            Row(heldKnown = true, listEmpty = false, publishOk = true, rebuildOk = true, expected = true),
            Row(heldKnown = false, listEmpty = false, publishOk = true, rebuildOk = true, expected = false),   // the system could not be asked
            Row(heldKnown = true, listEmpty = true, publishOk = true, rebuildOk = true, expected = false),     // no thread to judge by
            Row(heldKnown = true, listEmpty = false, publishOk = false, rebuildOk = true, expected = false),   // rate-limited
            Row(heldKnown = true, listEmpty = false, publishOk = true, rebuildOk = false, expected = false),   // an update was refused
            Row(heldKnown = false, listEmpty = true, publishOk = false, rebuildOk = false, expected = false),
        )
        for (r in rows) {
            assertEquals("$r", r.expected, shortcutRetargetIsFinal(r.heldKnown, r.listEmpty, r.publishOk, r.rebuildOk))
        }
    }

    // ---- may a notification reuse the shortcut it finds? ----

    @Test fun aNotification_reusesAShortcut_onlyWhenItIsInTheMapAndCurrent() {
        assertTrue(notificationShortcutIsCurrent(inMap = true, storedComponent = current, currentComponent = current))
        assertFalse(notificationShortcutIsCurrent(inMap = false, storedComponent = current, currentComponent = current))
        assertFalse(notificationShortcutIsCurrent(inMap = true, storedComponent = old, currentComponent = current))
        assertFalse(notificationShortcutIsCurrent(inMap = true, storedComponent = null, currentComponent = current))
        assertFalse(notificationShortcutIsCurrent(inMap = false, storedComponent = null, currentComponent = current))
    }

    @Test fun aShortcutThisProcessBuilt_isCurrent_beforeTheRetargetIsRecorded() {
        // A publish built it for this class but could not finish the retarget: a notification must not replace it.
        assertTrue(notificationShortcutIsCurrent(inMap = true, storedComponent = null, currentComponent = current, builtThisProcess = true))
        assertTrue(notificationShortcutIsCurrent(inMap = true, storedComponent = old, currentComponent = current, builtThisProcess = true))
        // Not in the map: the system let it go, so it is pushed again whoever built it.
        assertFalse(notificationShortcutIsCurrent(inMap = false, storedComponent = null, currentComponent = current, builtThisProcess = true))
    }
}
