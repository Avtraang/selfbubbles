package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the conversation shortcuts (ConversationShortcuts.kt). */
class ConversationShortcutsTest {

    private fun thread(
        guid: String, name: String? = guid, pinned: Boolean = false, pinIndex: Int = -1,
        archived: Boolean = false, lastDate: Double? = null,
    ) = Thread(
        chat_guid = guid, chat_name = name, last_rowid = 0,
        pinned = pinned, pin_index = pinIndex, archived = archived, last_date = lastDate,
    )

    // ---- selection ----

    @Test fun select_pinnedFirstByPinIndex_thenMostRecent() {
        val list = listOf(
            thread("recent-2", lastDate = 200.0),
            thread("pin-b", pinned = true, pinIndex = 1, lastDate = 50.0),
            thread("recent-1", lastDate = 300.0),
            thread("pin-a", pinned = true, pinIndex = 0, lastDate = 10.0),
            thread("recent-3", lastDate = 100.0),
        )
        assertEquals(
            listOf("pin-a", "pin-b", "recent-1", "recent-2", "recent-3"),
            selectShortcutThreads(list, 10).map { it.chat_guid },
        )
    }

    @Test fun select_excludesArchived_evenWhenPinned() {
        val list = listOf(
            thread("a", pinned = true, pinIndex = 0, archived = true),
            thread("b", lastDate = 5.0, archived = true),
            thread("c", lastDate = 1.0),
        )
        assertEquals(listOf("c"), selectShortcutThreads(list, 10).map { it.chat_guid })
    }

    @Test fun select_capsAtMax_andNothingBelowOne() {
        val list = (1..6).map { thread("t$it", lastDate = it.toDouble()) }
        assertEquals(listOf("t6", "t5", "t4"), selectShortcutThreads(list, 3).map { it.chat_guid })
        assertEquals(emptyList<Thread>(), selectShortcutThreads(list, 0))
        assertEquals(emptyList<Thread>(), selectShortcutThreads(list, -4))
    }

    @Test fun select_missingDateSortsLast_andKeepsRelayOrder() {
        val list = listOf(thread("x"), thread("y", lastDate = 1.0), thread("z"))
        assertEquals(listOf("y", "x", "z"), selectShortcutThreads(list, 10).map { it.chat_guid })
    }

    @Test fun select_dropsBlankGuids_andDuplicates() {
        val list = listOf(thread("", lastDate = 9.0), thread("a", lastDate = 2.0), thread("a", lastDate = 1.0))
        assertEquals(listOf("a"), selectShortcutThreads(list, 10).map { it.chat_guid })
    }

    // ---- ids ----

    @Test fun id_isStable_prefixed_hexSha256() {
        val id = shortcutIdFor("iMessage;-;+15551234567")
        assertEquals(id, shortcutIdFor("iMessage;-;+15551234567"))
        assertTrue(id.startsWith(SHORTCUT_ID_PREFIX))
        assertEquals(SHORTCUT_ID_PREFIX.length + 64, id.length)
        assertTrue(isValidShortcutId(id))
        // Known digest of "abc".
        assertEquals(
            SHORTCUT_ID_PREFIX + "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            shortcutIdFor("abc"),
        )
    }

    @Test fun id_differsPerGuid_andNeverContainsIt() {
        val a = shortcutIdFor("iMessage;-;alice@example.com")
        val b = shortcutIdFor("iMessage;-;bob@example.com")
        assertNotEquals(a, b)
        assertFalse(a.contains("alice"))
    }

    @Test fun id_validation_rejectsForeignShapes() {
        assertFalse(isValidShortcutId(null))
        assertFalse(isValidShortcutId(""))
        assertFalse(isValidShortcutId("chat-abc"))
        assertFalse(isValidShortcutId("x" + shortcutIdFor("a").drop(1)))
        assertFalse(isValidShortcutId(shortcutIdFor("a").uppercase()))
    }

    // ---- labels ----

    @Test fun label_fallsBackToChat_whenBlank() {
        assertEquals("Chat", shortcutShortLabel(null))
        assertEquals("Chat", shortcutShortLabel("   "))
        assertEquals("Chat", shortcutLongLabel(null))
    }

    @Test fun label_shortIsCappedWithEllipsis_longIsFull() {
        val name = "Family group with grandparents and cousins"
        val short = shortcutShortLabel(name)
        assertEquals(SHORTCUT_SHORT_LABEL_MAX, short.length)
        assertTrue(short.endsWith("…"))
        assertEquals("Family group with grandp…", short)
        assertEquals(name, shortcutLongLabel(name))
        // Exactly at the cap: untouched.
        val exact = "a".repeat(SHORTCUT_SHORT_LABEL_MAX)
        assertEquals(exact, shortcutShortLabel(exact))
        assertEquals("Mom", shortcutShortLabel("  Mom "))
    }

    @Test fun plan_carriesRankAndLabels() {
        val plans = planShortcuts(listOf(thread("g1", name = "One"), thread("g2", name = null))) { it.chat_name }
        assertEquals(listOf(0, 1), plans.map { it.rank })
        assertEquals(listOf("One", "Chat"), plans.map { it.shortLabel })
        assertEquals(listOf(shortcutIdFor("g1"), shortcutIdFor("g2")), plans.map { it.id })
        assertEquals(listOf("g1", "g2"), plans.map { it.guid })
    }

    // ---- a handle is never a label ----

    @Test fun displayName_dropsNumbersShortCodesAndAddresses() {
        // The relay names an un-contacted 1:1 chat after its handle.
        assertNull(shortcutDisplayName("+15551234567"))
        assertNull(shortcutDisplayName("+1 (555) 123-4567"))
        assertNull(shortcutDisplayName("555-1234"))
        assertNull(shortcutDisplayName("22000"))
        assertNull(shortcutDisplayName("alice@example.com"))
        assertNull(shortcutDisplayName("Alice@Example.com"))
        // Letters beside seven or more digits are still a number.
        assertNull(shortcutDisplayName("tel 5551234567"))
        assertNull(shortcutDisplayName(null))
        assertNull(shortcutDisplayName("  "))
    }

    @Test fun displayName_keepsRealNames_andCleansGroupTitles() {
        assertEquals("Mom", shortcutDisplayName(" Mom "))
        assertEquals("Room 101", shortcutDisplayName("Room 101"))
        assertEquals("2 Chainz", shortcutDisplayName("2 Chainz"))
        assertEquals("Alice, Bob", shortcutDisplayName("Alice, Bob, "))
        assertEquals("Élodie", shortcutDisplayName("Élodie"))
    }

    @Test fun select_excludesHandleNamedChats() {
        val list = listOf(
            Thread(chat_guid = "SMS;-;+15551234567", chat_name = "+15551234567", handles = listOf("+15551234567"), last_rowid = 0, last_date = 9.0),
            Thread(chat_guid = "iMessage;-;alice@example.com", chat_name = "alice@example.com", last_rowid = 0, last_date = 8.0),
            thread("short-code", name = "22000", lastDate = 7.0),
            // A group titled from its members' numbers is no better than a number.
            Thread(chat_guid = "iMessage;+;chat1", chat_name = "+15551234567, +15559876543", is_group = true, last_rowid = 0, last_date = 6.0),
            thread("nameless", name = null, lastDate = 5.0),
            thread("blank", name = "  ", lastDate = 4.0),
            thread("named", name = "Mom", lastDate = 1.0),
        )
        assertEquals(listOf("named"), selectShortcutThreads(list, 10).map { it.chat_guid })
        // Pinning does not get a handle-named chat in either.
        val pinnedHandle = Thread(chat_guid = "SMS;-;+15550001111", chat_name = "+15550001111", pinned = true, pin_index = 0, last_rowid = 0)
        assertEquals(listOf("named"), selectShortcutThreads(list + pinnedHandle, 10).map { it.chat_guid })
    }

    @Test fun select_slotGoesToTheNextNamedChat() {
        val list = listOf(
            thread("a", name = "Alice", lastDate = 5.0),
            thread("SMS;-;+15551234567", name = "+15551234567", lastDate = 4.0),
            thread("b", name = "Bob", lastDate = 3.0),
            thread("c", name = "Carol", lastDate = 2.0),
        )
        // Two slots: the handle-named chat in second place does not use one up.
        assertEquals(listOf("a", "b"), selectShortcutThreads(list, 2).map { it.chat_guid })
        val plans = planShortcuts(selectShortcutThreads(list, 3)) { shortcutDisplayName(it.chat_name) }
        assertEquals(listOf("Alice", "Bob", "Carol"), plans.map { it.shortLabel })
        assertEquals(listOf(0, 1, 2), plans.map { it.rank })
        assertTrue(plans.none { it.shortLabel.contains("555") || it.longLabel.contains("555") })
    }

    @Test fun notificationPlan_isNullForAnUnnamedChat_lastRankedForANamedOne() {
        assertNull(planNotificationShortcut("SMS;-;+15551234567", "+15551234567", 4))
        assertNull(planNotificationShortcut("iMessage;-;alice@example.com", "alice@example.com", 4))
        assertNull(planNotificationShortcut("iMessage;-;22000", "22000", 4))
        assertNull(planNotificationShortcut("iMessage;+;chat1", "+15551234567, +15559876543", 4))
        assertNull(planNotificationShortcut("g", null, 4))
        assertNull(planNotificationShortcut("g", "  ", 4))
        val p = planNotificationShortcut("g", " Mom ", 4)!!
        assertEquals(shortcutIdFor("g"), p.id)
        assertEquals("g", p.guid)
        assertEquals("Mom", p.shortLabel)
        assertEquals("Mom", p.longLabel)
        assertEquals(notificationShortcutRank(4), p.rank)
    }

    // ---- the icon matches the list's avatar ----

    @Test fun avatar_initialsAndFillFollowTheRawName_asTheListDoes() {
        // Nameless: the list draws the Person glyph (no initials), never a "C" for "Chat".
        assertEquals("", shortcutAvatarInitials(null))
        assertEquals("", shortcutAvatarInitials("+15551234567"))
        assertEquals("A‌B", shortcutAvatarInitials("Alice Brown"))
        // The fill key is the raw chat_name's hash, as Avatar(name = t.chat_name) keys it:
        // the relay's trailing ", " shape must pick the same fill as the list row.
        val raw = "Alice, Bob, "
        assertEquals(raw.hashCode().toLong(), shortcutAvatarKey(raw))
        assertNotEquals((cleanTitle(raw) ?: "").hashCode().toLong(), shortcutAvatarKey(raw))
        assertEquals("".hashCode().toLong(), shortcutAvatarKey(null))
    }

    // ---- what a publish leaves in the map ----

    @Test fun mergeMap_keepsEntriesTheSystemStillHolds_dropsTheRest() {
        val a = shortcutIdFor("a"); val b = shortcutIdFor("b"); val n = shortcutIdFor("notified")
        val old = mapOf(a to "a", b to "b", n to "notified")
        val published = mapOf(a to "a")
        // b fell out of the top set and nothing holds it; the notified chat's cached copy stays.
        assertEquals(mapOf(a to "a", n to "notified"), mergeShortcutMap(old, published, setOf(n)))
        // A pinned copy of b keeps b resolvable.
        assertEquals(old, mergeShortcutMap(old, published, setOf(n, b)))
        // The system could not be asked: nothing is dropped.
        assertEquals(old, mergeShortcutMap(old, published, null))
        // A chat new to the map comes in.
        val c = shortcutIdFor("c")
        assertEquals(mapOf(c to "c"), mergeShortcutMap(emptyMap(), mapOf(c to "c"), emptySet()))
    }

    @Test fun notificationRank_isLast_neverNegative() {
        assertEquals(4, notificationShortcutRank(4))
        assertEquals(0, notificationShortcutRank(0))
        assertEquals(0, notificationShortcutRank(-1))
    }

    // ---- the bounded icon read ----

    @Test fun readCapped_returnsBytesWithinLimit_nullBeyondIt() {
        val small = ByteArray(10_000) { it.toByte() }
        assertTrue(small.contentEquals(readCapped(small.inputStream(), 10_000)))
        assertTrue(small.contentEquals(readCapped(small.inputStream(), 1 shl 20)))
        assertNull(readCapped(small.inputStream(), 9_999))
        assertNull(readCapped(ByteArray(SHORTCUT_ICON_MAX_BYTES + 1).inputStream(), SHORTCUT_ICON_MAX_BYTES))
        assertEquals(0, readCapped(ByteArray(0).inputStream(), 1)!!.size)
    }

    // ---- id <-> guid map ----

    @Test fun map_roundTrips() {
        val map = mapOf(
            shortcutIdFor("iMessage;-;+15551234567") to "iMessage;-;+15551234567",
            shortcutIdFor("bp:abc") to "bp:abc",
        )
        val decoded = shortcutMapDecode(shortcutMapEncode(map))
        assertEquals(map, decoded)
        for ((id, guid) in map) assertEquals(guid, decoded[id])
    }

    @Test fun map_dropsMalformedEntries_andNullSet() {
        assertEquals(emptyMap<String, String>(), shortcutMapDecode(null))
        val good = shortcutIdFor("g")
        val decoded = shortcutMapDecode(setOf("$good\tg", "no-tab", "\tguid", "$good\t", "bogus\tguid"))
        assertEquals(mapOf(good to "g"), decoded)
        assertNull(decoded["bogus"])
    }
}
