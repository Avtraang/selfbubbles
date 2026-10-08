package io.github.avtraang.selfbubbles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests (plan C0) for the pure, clock-independent top-level helpers in
 * MainActivity.kt, written before the section-5 split so each move can be proven
 * behaviour-preserving. They pin CURRENT behaviour, odd corners included; a case marked
 * "pins current behaviour" is not a statement that the behaviour is desired.
 *
 * Clock- and zone-sensitive helpers (fmtTime, fmtListTime, sameDay, needsDivider, sameRun,
 * dividerLabel, bubbleTime) live in PureHelpersTimeTest.
 */
class PureHelpersTest {

    private val zwnj = "\u200C" // InitialsSeparator: zero-width non-joiner (escaped: the raw character is invisible in source)

    private fun thread(
        guid: String = "iMessage;-;+15550100",
        name: String? = null,
        network: String? = null,
        service: String? = null,
        viaLabel: String? = null,
        warning: String? = null,
    ) = Thread(
        chat_guid = guid, chat_name = name, last_rowid = 1L,
        network = network, service = service, via_label = viaLabel, send_warning = warning,
    )

    private fun msg(
        rowid: Long,
        guid: String = "MSG-$rowid",
        text: String? = "m$rowid",
        fromMe: Boolean = false,
        sender: String? = null,
        handle: String? = null,
        assocGuid: String? = null,
        assocType: Int? = null,
        network: String? = null,
        service: String? = null,
    ) = Msg(
        rowid = rowid, guid = guid, text = text, is_from_me = fromMe, sender = sender,
        sender_handle = handle, chat_guid = "iMessage;-;+15550100", assoc_guid = assocGuid,
        assoc_type = assocType, network = network, service = service,
    )

    // ---- isSms ----

    @Test fun isSms_onlyExactGmessages() {
        assertTrue(isSms("gmessages"))
        assertFalse(isSms(null))
        assertFalse(isSms(""))
        assertFalse(isSms("imessage"))
        assertFalse(isSms("GMessages"))   // pins current behaviour: case-sensitive
        assertFalse(isSms(" gmessages")) // pins current behaviour: no trimming
    }

    // ---- isTextThread ----

    @Test fun isTextThread_nullThread_false() {
        assertFalse(isTextThread(null))
    }

    @Test fun isTextThread_byNetwork() {
        assertTrue(isTextThread(thread(network = "gmessages")))
        assertTrue(isTextThread(thread(network = "gmessages", service = "iMessage"))) // network wins
        assertFalse(isTextThread(thread(network = "imessage")))
        assertFalse(isTextThread(thread()))
    }

    @Test fun isTextThread_byService() {
        assertTrue(isTextThread(thread(service = "SMS")))
        assertTrue(isTextThread(thread(service = "RCS")))
        assertFalse(isTextThread(thread(service = "iMessage")))
        assertFalse(isTextThread(thread(service = "iMessageLite")))
        assertFalse(isTextThread(thread(service = "sms"))) // pins current behaviour: case-sensitive
        assertFalse(isTextThread(thread(service = "")))
    }

    // ---- isTextMsg ----

    @Test fun isTextMsg_mirrorsThreadRule() {
        assertTrue(isTextMsg(msg(1, network = "gmessages")))
        assertTrue(isTextMsg(msg(1, service = "SMS")))
        assertTrue(isTextMsg(msg(1, service = "RCS")))
        assertFalse(isTextMsg(msg(1, service = "iMessage")))
        assertFalse(isTextMsg(msg(1, service = "iMessageLite")))
        assertFalse(isTextMsg(msg(1)))
        assertFalse(isTextMsg(msg(1, network = "GMESSAGES", service = "rcs"))) // pins: case-sensitive
    }

    // ---- Thread.withLabelsFrom ----

    @Test fun withLabelsFrom_copiesServiceLabelsAndKeepsOwnNetworkWhenSourceHasNone() {
        // Every non-label field differs between receiver and source, so the exhaustive equality at
        // the end proves that exactly four fields are taken from the source and nothing else moves.
        val mine = thread(name = "Ada", network = "imessage", service = "iMessage", viaLabel = "old", warning = "old-w").copy(
            is_group = false, handles = listOf("+15550100"), icon_url = null, last_date = 1_000.0, last_rowid = 10L,
            preview = "mine-preview", pinned = true, pin_index = 1, archived = false, unread = 3, auto_translate = false,
        )
        val src = thread(guid = "other", name = "Other", network = null, service = "RCS", viaLabel = "RCS · Google Messages", warning = "w").copy(
            is_group = true, handles = listOf("+15550101", "+15550102"), icon_url = "http://192.168.0.2/icon.png", last_date = 2_000.0,
            last_rowid = 20L, preview = "src-preview", pinned = false, pin_index = 7, archived = true, unread = 9, auto_translate = true,
        )
        val out = mine.withLabelsFrom(src)
        assertEquals("imessage", out.network)               // f.network null -> keep own
        assertEquals("RCS", out.service)
        assertEquals("RCS · Google Messages", out.via_label)
        assertEquals("w", out.send_warning)
        // Everything else is untouched (chat_guid, chat_name, is_group, handles, icon_url, last_date,
        // last_rowid, preview, pinned, pin_index, archived, unread, auto_translate): exhaustive.
        assertEquals(mine.copy(service = "RCS", via_label = "RCS · Google Messages", send_warning = "w"), out)
    }

    @Test fun withLabelsFrom_sourceNetworkOverridesOwn() {
        val out = thread(network = "imessage").withLabelsFrom(thread(network = "gmessages"))
        assertEquals("gmessages", out.network)
    }

    @Test fun withLabelsFrom_nullServiceLabelsInSourceClearOwn() {
        // pins current behaviour: unlike network, service/via_label/send_warning are
        // overwritten even when the source has null, so an older relay clears them.
        val out = thread(service = "SMS", viaLabel = "v", warning = "w").withLabelsFrom(thread())
        assertNull(out.service)
        assertNull(out.via_label)
        assertNull(out.send_warning)
    }

    @Test fun withLabelsFrom_leavesPinnedUnreadArchivedAlone() {
        val mine = thread(name = "Ada").copy(pinned = true, pin_index = 2, unread = 5, archived = true, auto_translate = true)
        val out = mine.withLabelsFrom(thread().copy(pinned = false, unread = 0))
        assertTrue(out.pinned)
        assertEquals(2, out.pin_index)
        assertEquals(5, out.unread)
        assertTrue(out.archived)
        assertTrue(out.auto_translate)
    }

    // ---- initialsOf ----

    @Test fun initialsOf_firstTwoWordsUppercasedWithZwnj() {
        assertEquals("A${zwnj}L", initialsOf("Ada Lovelace"))
        assertEquals("A${zwnj}L", initialsOf("ada lovelace"))
        assertEquals("A${zwnj}L", initialsOf("Ada Lovelace King")) // third word ignored
        assertEquals("A", initialsOf("Ada"))
    }

    @Test fun initialsOf_trimsAndSkipsEmptyRunsBetweenSpaces() {
        assertEquals("A${zwnj}L", initialsOf("   Ada Lovelace   "))
        assertEquals("A${zwnj}L", initialsOf("Ada    Lovelace"))
    }

    @Test fun initialsOf_tabIsNotAWordSeparator() {
        // pins current behaviour: split(' ') only; a tab-joined name is one word
        assertEquals("A", initialsOf("Ada\tLovelace"))
    }

    @Test fun initialsOf_emptyAndBlank() {
        assertEquals("", initialsOf(""))
        assertEquals("", initialsOf("    "))
    }

    @Test fun initialsOf_wordsNotStartingWithALetterAreSkipped() {
        assertEquals("", initialsOf("+1 555 0100"))
        assertEquals("", initialsOf("20446"))
        assertEquals("M", initialsOf("123 Main"))
        assertEquals("P", initialsOf("🎉 Party"))        // emoji word skipped
        assertEquals("L", initialsOf("(Ada) Lovelace"))             // pins: "(Ada)" starts with '('
        assertEquals("A${zwnj}L", initialsOf("Ada 5th Lovelace"))  // "5th" skipped, not counted toward two
    }

    @Test fun initialsOf_nonLatinLettersKept() {
        assertEquals("ع${zwnj}ر", initialsOf("علی رضا")) // Persian
        assertEquals("א${zwnj}ב", initialsOf("אבג בד"))      // Hebrew
        assertEquals("中${zwnj}文", initialsOf("中国 文字"))           // CJK
        assertEquals("É${zwnj}Ö", initialsOf("émile örn"))                // accented, uppercased
    }

    @Test fun initialsOf_sharpSExpandsToSS() {
        // pins current behaviour: "ß".uppercase() is "SS", so a single initial becomes two letters
        assertEquals("SS", initialsOf("ßtraße"))
    }

    @Test fun initialsOf_decomposedAccentIsDropped() {
        // pins current behaviour: take(1) keeps only the base letter of an NFD "é" (e + U+0301),
        // unlike the precomposed form above which keeps its accent
        assertEquals("E", initialsOf("e\u0301mile"))
        assertEquals("E${zwnj}O", initialsOf("e\u0301mile o\u0308rn"))
    }

    @Test fun initialsOf_supplementaryPlaneLetterIsSkipped() {
        // pins current behaviour: Char.isLetter() on a lone high surrogate is false,
        // so a name starting with a mathematical-bold "A" (U+1D538) gets no initial
        assertEquals("L", initialsOf("𝔸da Lovelace"))
    }

    @Test fun initialsOf_veryLongInput() {
        val words = (1..5000).joinToString(" ") { "w$it" }
        assertEquals("W${zwnj}W", initialsOf(words))
        assertEquals("X", initialsOf("x".repeat(100_000)))
    }

    // ---- cleanTitle / Thread.title ----

    @Test fun cleanTitle_nullForNothingUsable() {
        assertNull(cleanTitle(null))
        assertNull(cleanTitle(""))
        assertNull(cleanTitle("   "))
        assertNull(cleanTitle(", "))
        assertNull(cleanTitle(", , "))
        assertNull(cleanTitle(","))
    }

    @Test fun cleanTitle_passesPlainNamesThrough() {
        assertEquals("Ada", cleanTitle("Ada"))
        assertEquals("Ada, Bob", cleanTitle("Ada, Bob"))
        assertEquals("Ada,Bob", cleanTitle("Ada,Bob"))      // no ", " separator: untouched
        assertEquals("علی", cleanTitle("علی"))
    }

    @Test fun cleanTitle_dropsBlankCommaSeparatedParts() {
        assertEquals("Ada, Bob", cleanTitle("Ada, , Bob"))
        assertEquals("Ada, Bob", cleanTitle(", Ada, Bob"))
        assertEquals("Ada, Bob", cleanTitle("Ada, Bob, "))
        assertEquals("Ada", cleanTitle("Ada, "))
    }

    @Test fun cleanTitle_dropsWhitespaceOnlyParts() {
        // A participant whose name is a lone space produces a whitespace-only part: the filter is
        // isNotBlank, not isNotEmpty (the first two cases differ between the two).
        assertEquals("Ada, Bob", cleanTitle("Ada,  , Bob"))   // parts: "Ada", " ", "Bob"
        assertEquals("Ada", cleanTitle(" , Ada"))             // parts: " ", "Ada"
        assertEquals("Ada, Bob", cleanTitle("Ada, \t, Bob"))  // parts: "Ada", "\t", "Bob"
        assertEquals("Ada", cleanTitle("Ada,  "))             // parts: "Ada", " "
    }

    @Test fun cleanTitle_trimsTrailingCommasAndSpacesOnly() {
        assertEquals("Ada", cleanTitle("Ada,"))
        assertEquals("Ada", cleanTitle("Ada, ,"))
        assertEquals("Ada", cleanTitle("Ada   "))
        assertEquals("  Ada", cleanTitle("  Ada"))        // pins current behaviour: leading space kept
        assertEquals("Ada , Bob", cleanTitle("Ada , Bob")) // pins: " ," inside a part is kept
    }

    @Test fun threadTitle_cleanedNameElseGuid() {
        assertEquals("Ada, Bob", thread(name = "Ada, Bob, ").title)
        assertEquals("iMessage;-;+15550100", thread(name = null).title)
        assertEquals("iMessage;-;+15550100", thread(name = "  ").title)
        assertEquals("iMessage;-;+15550100", thread(name = ", ").title)
        assertEquals("bp:7", thread(guid = "bp:7").title)
    }

    // ---- looksLikeAddress ----

    @Test fun looksLikeAddress_emailNeedsAtAndDot() {
        assertTrue(looksLikeAddress("ada@example.test"))
        assertTrue(looksLikeAddress("  ada@example.test  "))
        assertFalse(looksLikeAddress("ada@example"))
        assertFalse(looksLikeAddress("ada.example"))
        assertTrue(looksLikeAddress("@."))          // pins current behaviour: order/content not checked
        assertTrue(looksLikeAddress("a.b@c"))       // pins: dot may come before the @
    }

    @Test fun looksLikeAddress_sevenDigitsAnywhere() {
        assertTrue(looksLikeAddress("5550100"))                    // exactly 7
        assertFalse(looksLikeAddress("555010"))                    // 6
        assertTrue(looksLikeAddress("+1 (555) 010-0100"))          // 11 digits across formatting
        assertFalse(looksLikeAddress("+1 (555) 01"))               // 6 digits with formatting
        assertTrue(looksLikeAddress("order 1234567 please"))      // pins: digits need not be contiguous
        assertTrue(looksLikeAddress("1 2 3 4 5 6 7"))
    }

    @Test fun looksLikeAddress_plainNamesEmptyAndBlank() {
        assertFalse(looksLikeAddress(""))
        assertFalse(looksLikeAddress("   "))
        assertFalse(looksLikeAddress("Ada Lovelace"))
        assertFalse(looksLikeAddress("Ada, Bob"))
    }

    @Test fun looksLikeAddress_nonAsciiDigitsCount() {
        // pins current behaviour: Char.isDigit() accepts Arabic-Indic and fullwidth digits
        assertTrue(looksLikeAddress("١٢٣٤٥٦٧"))
        assertTrue(looksLikeAddress("１２３４５６７"))
        assertFalse(looksLikeAddress("١٢٣٤٥٦"))
    }

    // ---- addRecipient ----

    private fun hit(name: String, address: String) = ContactHit(name = name, address = address)

    // ---- the New Message screen's recipients across a rotation or a lock ----

    @Test fun recipients_surviveBeingSavedAndRestored() {
        val list = listOf(hit("Ada", "ada@example.test"), hit("+15550100", "+15550100"), hit("", "bob@example.test"))
        assertEquals(listOf("Ada", "ada@example.test", "+15550100", "+15550100", "", "bob@example.test"), recipientsSaved(list))
        assertEquals(list, recipientsRestored(recipientsSaved(list)))
        assertEquals(emptyList<String>(), recipientsSaved(emptyList()))
        assertEquals(emptyList<ContactHit>(), recipientsRestored(emptyList()))
        // A damaged saved list never yields half a recipient.
        assertEquals(listOf(hit("Ada", "ada@example.test")), recipientsRestored(listOf("Ada", "ada@example.test", "Bob")))
    }

    @Test fun addRecipient_appendsNewAddressesInOrder() {
        val list = mutableListOf<ContactHit>()
        addRecipient(list, hit("Ada", "ada@example.test"))
        addRecipient(list, hit("Bob", "+15550100"))
        assertEquals(listOf(hit("Ada", "ada@example.test"), hit("Bob", "+15550100")), list)
    }

    @Test fun addRecipient_duplicateAddressIgnoredCaseInsensitively_firstWins() {
        val list = mutableListOf(hit("Ada", "ada@example.test"))
        addRecipient(list, hit("Ada", "ada@example.test"))
        addRecipient(list, hit("A. Lovelace", "ADA@Example.TEST"))
        assertEquals(1, list.size)
        assertEquals("Ada", list[0].name) // the existing entry is kept, the new name dropped
    }

    @Test fun addRecipient_sameNameDifferentAddressIsAdded() {
        val list = mutableListOf(hit("Ada", "ada@example.test"))
        addRecipient(list, hit("Ada", "ada@work.example.test"))
        assertEquals(2, list.size)
    }

    @Test fun addRecipient_noTrimmingOnAddresses() {
        // pins current behaviour: surrounding whitespace makes it a different address
        val list = mutableListOf(hit("Ada", "ada@example.test"))
        addRecipient(list, hit("Ada", "ada@example.test "))
        assertEquals(2, list.size)
    }

    // ---- splitReactions ----

    private val love = "\u2764\uFE0F"       // same escapes as TAPBACK_EMOJI in MainActivity.kt
    private val like = "\uD83D\uDC4D"
    private val dislike = "\uD83D\uDC4E"
    private val laugh = "\uD83D\uDE02"
    private val emph = "\u203C\uFE0F"
    private val question = "\u2753"

    private fun tapback(rowid: Long, type: Int, target: String?, handle: String? = "+15550101", fromMe: Boolean = false, sender: String? = null) =
        msg(rowid, text = null, fromMe = fromMe, handle = handle, sender = sender, assocGuid = target, assocType = type)

    @Test fun splitReactions_emptyInput() {
        val (bubbles, reactions) = splitReactions(emptyList())
        assertTrue(bubbles.isEmpty())
        assertTrue(reactions.isEmpty())
    }

    @Test fun splitReactions_plainMessagesAreBubblesSortedByRowid() {
        val a = msg(3); val b = msg(1); val c = msg(2, assocType = 0)
        val (bubbles, reactions) = splitReactions(listOf(a, b, c))
        assertEquals(listOf(b, c, a), bubbles)
        assertTrue(reactions.isEmpty())
    }

    @Test fun splitReactions_sortIsStableForEqualRowids() {
        // Google Messages rows all carry rowid 0; input order must survive.
        val a = msg(0, guid = "A"); val b = msg(0, guid = "B"); val c = msg(0, guid = "C")
        assertEquals(listOf(a, b, c), splitReactions(listOf(a, b, c)).first)
        assertEquals(listOf(c, a, b), splitReactions(listOf(c, a, b)).first)
    }

    @Test fun splitReactions_eachAddTypeMapsToItsEmoji() {
        val all = listOf(msg(1)) + listOf(2000, 2001, 2002, 2003, 2004, 2005).mapIndexed { i, t ->
            tapback(10L + i, t, "p:0/MSG-1", handle = "+1555010$i")
        }
        val (bubbles, reactions) = splitReactions(all)
        assertEquals(listOf(msg(1)), bubbles)
        assertEquals(
            listOf(love, like, dislike, laugh, emph, question).map { Reaction(it, 1, false) },
            reactions.getValue("MSG-1"),
        )
    }

    @Test fun splitReactions_targetIsLastPathSegmentWithBpPrefixStripped() {
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1"),
            tapback(2, 2001, "bp:MSG-2"),
            tapback(3, 2002, "bp:p:0/MSG-3"),
            tapback(4, 2003, "MSG-4"),
        )
        val reactions = splitReactions(all).second
        assertEquals(setOf("MSG-1", "MSG-2", "MSG-3", "MSG-4"), reactions.keys)
    }

    @Test fun splitReactions_reactionWithoutTargetIsDroppedNotBubbled() {
        val (bubbles, reactions) = splitReactions(listOf(tapback(1, 2000, null), msg(2)))
        assertEquals(listOf(msg(2)), bubbles)
        assertTrue(reactions.isEmpty())
    }

    @Test fun splitReactions_assocTypeRangeBoundaries() {
        // 2000..3999 are consumed (never bubbles); only 2000..2005 / 3000..3005 produce chips.
        val consumedNoChip = listOf(2006, 2999, 3006, 3999).mapIndexed { i, t -> tapback(10L + i, t, "p:0/MSG-1") }
        val stillBubbles = listOf(1999, 4000, -1).mapIndexed { i, t -> tapback(20L + i, t, "p:0/MSG-1") }
        val (bubbles, reactions) = splitReactions(consumedNoChip + stillBubbles)
        assertEquals(stillBubbles, bubbles)      // pins current behaviour: 1999 / 4000 / -1 render as bubbles
        assertTrue(reactions.isEmpty())
    }

    @Test fun splitReactions_mineFlagAndSenderCounting() {
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1", handle = "+15550101"),
            tapback(2, 2000, "p:0/MSG-1", handle = "+15550102"),
            tapback(3, 2000, "p:0/MSG-1", fromMe = true, handle = null),
            tapback(4, 2001, "p:0/MSG-1", fromMe = true, handle = null),
        )
        assertEquals(listOf(Reaction(love, 3, true), Reaction(like, 1, true)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_mineSurvivesLaterNonMeReactors() {
        // `mine` is OR-accumulated across senders in first-seen order, not read off the last reactor:
        // me first, then another person, still shows as mine.
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1", fromMe = true, handle = null),
            tapback(2, 2000, "p:0/MSG-1", handle = "+15550101"),
        )
        assertEquals(listOf(Reaction(love, 2, true)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_isFromMeWinsOverSenderHandle() {
        // An outgoing tapback is "me" even when the relay also puts a handle on the row, so two
        // from-me rows with different handles collapse into one reactor...
        val mineWithHandle = tapback(1, 2000, "p:0/MSG-1", fromMe = true, handle = "+15550100")
        val mineNoHandle = tapback(2, 2000, "p:0/MSG-1", fromMe = true, handle = null)
        assertEquals(listOf(Reaction(love, 1, true)), splitReactions(listOf(mineWithHandle, mineNoHandle)).second.getValue("MSG-1"))
        // ...while the same handle on an incoming row is a different identity.
        val otherSameHandle = tapback(3, 2000, "p:0/MSG-1", fromMe = false, handle = "+15550100")
        assertEquals(listOf(Reaction(love, 2, true)), splitReactions(listOf(mineWithHandle, mineNoHandle, otherSameHandle)).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_sameSenderSameEmojiCountsOnce() {
        val all = listOf(tapback(1, 2000, "p:0/MSG-1"), tapback(2, 2000, "p:0/MSG-1"))
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_senderIdentityFallbackChain() {
        // handle wins over sender; neither -> "?" so two anonymous reactors collapse into one
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1", handle = "+15550101", sender = "Ada"),
            tapback(2, 2000, "p:0/MSG-1", handle = null, sender = "Ada"),
            tapback(3, 2001, "p:0/MSG-1", handle = null, sender = null),
            tapback(4, 2001, "p:0/MSG-1", handle = null, sender = null),
        )
        val chips = splitReactions(all).second.getValue("MSG-1")
        assertEquals(listOf(Reaction(love, 2, false), Reaction(like, 1, false)), chips) // pins current behaviour
    }

    @Test fun splitReactions_removalCancelsThatSendersEmoji() {
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1", handle = "+15550101"),
            tapback(2, 2000, "p:0/MSG-1", handle = "+15550102"),
            tapback(3, 3000, "p:0/MSG-1", handle = "+15550101"),
        )
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_removalByAnotherSenderLeavesTheReactionAlone() {
        // Removal is per sender: B withdrawing a love that B never added does not cancel A's.
        val all = listOf(
            tapback(1, 2000, "p:0/MSG-1", handle = "+15550101"),
            tapback(2, 3000, "p:0/MSG-1", handle = "+15550102"),
        )
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_everyRemovalCodeCancelsItsOwnAddCode() {
        for (i in 0..5) {
            val all = listOf(tapback(1, 2000 + i, "p:0/MSG-1"), tapback(2, 3000 + i, "p:0/MSG-1"))
            assertTrue("type ${2000 + i} then ${3000 + i}", splitReactions(all).second.isEmpty())
        }
        // Removing a different emoji leaves the added one in place.
        val cross = listOf(tapback(1, 2003, "p:0/MSG-1"), tapback(2, 3000, "p:0/MSG-1"))
        assertEquals(listOf(Reaction(laugh, 1, false)), splitReactions(cross).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_targetWithOnlyRemovalsHasNoEntry() {
        val all = listOf(tapback(1, 2000, "p:0/MSG-1"), tapback(2, 3000, "p:0/MSG-1"), tapback(3, 3001, "p:0/MSG-2"))
        assertTrue(splitReactions(all).second.isEmpty())
    }

    @Test fun splitReactions_orderIsByRowidNotInputOrder() {
        val add = tapback(6, 2000, "p:0/MSG-1")
        val remove = tapback(5, 3000, "p:0/MSG-1")
        // Remove given after add, but its rowid is lower, so it is applied first and the add survives.
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(listOf(add, remove)).second.getValue("MSG-1"))
        // Same rowid: input order decides (stable sort) -> add then remove -> gone.
        val add0 = tapback(0, 2000, "p:0/MSG-1"); val remove0 = tapback(0, 3000, "p:0/MSG-1")
        assertTrue(splitReactions(listOf(add0, remove0)).second.isEmpty())
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(listOf(remove0, add0)).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_duplicateAddThenOneRemoveLeavesOne() {
        // pins current behaviour: the per-sender list keeps duplicates, remove() drops one occurrence
        val all = listOf(tapback(1, 2000, "p:0/MSG-1"), tapback(2, 2000, "p:0/MSG-1"), tapback(3, 3000, "p:0/MSG-1"))
        assertEquals(listOf(Reaction(love, 1, false)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_chipOrderIsFirstSeenAcrossSenders() {
        val all = listOf(
            tapback(1, 2001, "p:0/MSG-1", handle = "+15550101"), // A: like
            tapback(2, 2000, "p:0/MSG-1", handle = "+15550102"), // B: love
            tapback(3, 2000, "p:0/MSG-1", handle = "+15550101"), // A: love
        )
        // Senders iterate in first-seen order (A, B); A's emojis come in A's order (like, love); B's love merges.
        assertEquals(listOf(Reaction(like, 1, false), Reaction(love, 2, false)), splitReactions(all).second.getValue("MSG-1"))
    }

    @Test fun splitReactions_chipOrderIsPerSenderNotByRowid() {
        // pins current behaviour: all of A's emojis (in A's order) precede B's, so A's later laugh is
        // shown before B's earlier love. A "first seen by rowid" rule would give like, love, laugh.
        val all = listOf(
            tapback(1, 2001, "p:0/MSG-1", handle = "+15550101"), // A: like
            tapback(2, 2000, "p:0/MSG-1", handle = "+15550102"), // B: love
            tapback(3, 2003, "p:0/MSG-1", handle = "+15550101"), // A: laugh
        )
        assertEquals(
            listOf(Reaction(like, 1, false), Reaction(laugh, 1, false), Reaction(love, 1, false)),
            splitReactions(all).second.getValue("MSG-1"),
        )
    }

    @Test fun splitReactions_multipleTargetsAreIndependent() {
        val all = listOf(msg(1), msg(2), tapback(3, 2000, "p:0/MSG-1"), tapback(4, 2003, "p:0/MSG-2", fromMe = true))
        val (bubbles, reactions) = splitReactions(all)
        assertEquals(listOf(msg(1), msg(2)), bubbles)
        assertEquals(listOf(Reaction(love, 1, false)), reactions.getValue("MSG-1"))
        assertEquals(listOf(Reaction(laugh, 1, true)), reactions.getValue("MSG-2"))
    }

    @Test fun splitReactions_bubblesAreTheSameInstances() {
        val m = msg(1, text = "keep me")
        assertSame(m, splitReactions(listOf(m)).first[0])
    }

    // ---- looksForeign ----

    @Test fun looksForeign_nullEmptyBlank() {
        assertFalse(looksForeign(null))
        assertFalse(looksForeign(""))
        assertFalse(looksForeign("   \n\t"))
    }

    @Test fun looksForeign_latinScriptsAreNotForeign() {
        assertFalse(looksForeign("hello"))
        assertFalse(looksForeign("café naïve")) // Latin-1 Supplement
        assertFalse(looksForeign("Łódź"))   // Latin Extended-A
        assertFalse(looksForeign("ƀƁƂ"))    // Latin Extended-B
    }

    @Test fun looksForeign_otherScriptsAreForeign() {
        assertTrue(looksForeign("سلام"))             // Arabic/Persian
        assertTrue(looksForeign("שלום"))             // Hebrew
        assertTrue(looksForeign("你好"))                         // CJK
        assertTrue(looksForeign("Привет")) // Cyrillic
        assertTrue(looksForeign("Γειά"))             // Greek
    }

    @Test fun looksForeign_needsAtLeastTwoLetters() {
        assertFalse(looksForeign("س"))
        assertFalse(looksForeign("س 123 !!!"))
        assertTrue(looksForeign("سل"))
    }

    @Test fun looksForeign_ratioThresholdIsStrictlyAbovePointFour() {
        assertFalse(looksForeign("abcسل"))       // 2/5 = 0.40 -> not foreign
        assertTrue(looksForeign("abسل"))         // 2/4 = 0.50
        assertTrue(looksForeign("abcdسلا")) // 3/7 = 0.43
    }

    @Test fun looksForeign_mixedSentences() {
        assertTrue(looksForeign("hello سلام"))          // 4/9 = 0.44
        assertFalse(looksForeign("hello world سلام"))   // 4/14 = 0.29
        assertTrue(looksForeign("Ok چطوری؟")) // 5/7
    }

    @Test fun looksForeign_ignoresDigitsPunctuationEmojiAndSpaces() {
        assertFalse(looksForeign("123 456 !!! 🎉 😂"))
        assertTrue(looksForeign("سلام 123 !!! 🎉"))
    }

    @Test fun looksForeign_latinExtendedAdditionalCountsAsForeign() {
        // pins current behaviour: only four Latin blocks are whitelisted, so Vietnamese
        // precomposed letters (Latin Extended Additional) count as non-Latin
        assertTrue(looksForeign("ấậệ"))
        assertFalse(looksForeign("Tiếng Việt")) // 2/9 still below the threshold
    }

    @Test fun looksForeign_fullwidthLatinCountsAsForeign() {
        // pins current behaviour: fullwidth forms are letters outside the whitelisted blocks
        assertTrue(looksForeign("Ｈｅｌｌｏ"))
    }

    @Test fun looksForeign_astralPlaneLettersAreNotCounted() {
        // pins current behaviour: `for (c in text)` iterates UTF-16 units and Char.isLetter() is
        // false on surrogates, so CJK Extension B text (U+20000, U+20001) counts as zero letters
        assertFalse(looksForeign("\uD840\uDC00\uD840\uDC01"))
        assertFalse(looksForeign("ab\uD840\uDC00"))   // 0/2: the astral letter adds nothing
        assertTrue(looksForeign("a\u0633"))           // 1/2 > 0.4 as soon as a BMP letter is counted
    }

    @Test fun looksForeign_veryLongInput() {
        assertTrue(looksForeign("س".repeat(100_000)))
        assertFalse(looksForeign("a".repeat(100_000)))
        assertFalse(looksForeign("a".repeat(60_000) + "س".repeat(40_000))) // exactly 0.4
    }

    // ---- linkified ----

    private val linkColor = Color(0xFF123456)

    private fun links(text: String) = linkified(text, linkColor).let { it.getLinkAnnotations(0, it.length) }

    @Test fun linkified_plainTextHasNoLinksAndSameText() {
        val out = linkified("just words, no links.", linkColor)
        assertEquals("just words, no links.", out.text)
        assertTrue(links("just words, no links.").isEmpty())
        assertEquals("", linkified("", linkColor).text)
    }

    @Test fun linkified_visibleTextIsUnchangedEvenWithLinks() {
        val text = "see https://example.test/a. and http://example.test/b)"
        assertEquals(text, linkified(text, linkColor).text)
    }

    @Test fun linkified_urlTrailingPunctuationTrimmedButRangeKeepsIt() {
        val text = "see https://example.test/a."
        val l = links(text)
        assertEquals(1, l.size)
        val url = l[0].item as LinkAnnotation.Url
        assertEquals("https://example.test/a", url.url)
        assertEquals(text.indexOf("https"), l[0].start)
        assertEquals(text.length, l[0].end) // range covers the trailing "." too
    }

    @Test fun linkified_trimsOnlyDotCommaParenSemicolonAtEnd() {
        assertEquals("https://example.test/x", (links("(https://example.test/x).")[0].item as LinkAnnotation.Url).url)
        assertEquals("https://example.test/x", (links("https://example.test/x;,")[0].item as LinkAnnotation.Url).url)
        assertEquals("https://example.test/a,b", (links("https://example.test/a,b")[0].item as LinkAnnotation.Url).url) // interior kept
        assertEquals("https://example.test/x?q=1!", (links("https://example.test/x?q=1!")[0].item as LinkAnnotation.Url).url) // "!" not trimmed
        assertEquals("https://example.test/x\"", (links("\"https://example.test/x\"")[0].item as LinkAnnotation.Url).url)  // pins: quote kept
    }

    @Test fun linkified_schemeRequired() {
        assertTrue(links("example.test/x and www.example.test").isEmpty())
        assertTrue(links("ftp://example.test/x").isEmpty())
        assertEquals(1, links("http://example.test").size)
        assertTrue(links("HTTPS://example.test").isEmpty()) // pins current behaviour: case-sensitive scheme
    }

    @Test fun linkified_multipleLinksInOrderWithCorrectRanges() {
        val text = "a https://one.example.test b http://two.example.test/p c"
        val l = links(text)
        assertEquals(listOf("https://one.example.test", "http://two.example.test/p"), l.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(text.indexOf("https"), l[0].start)
        assertEquals(text.indexOf("https") + "https://one.example.test".length, l[0].end)
        assertEquals(text.indexOf("http://two"), l[1].start)
        assertEquals(text.length - 2, l[1].end)
    }

    @Test fun linkified_wholeTextIsAUrl() {
        val l = links("https://example.test")
        assertEquals(1, l.size)
        assertEquals(0, l[0].start)
        assertEquals("https://example.test".length, l[0].end)
    }

    @Test fun linkified_linkStyleIsUnderlineInTheGivenColor() {
        val url = links("x https://example.test y")[0].item as LinkAnnotation.Url
        val style = url.styles?.style
        assertEquals(TextDecoration.Underline, style?.textDecoration)
        assertEquals(linkColor, style?.color)
    }

    @Test fun linkified_urlStopsAtAnyWhitespaceIncludingNewlineAndTab() {
        val two = listOf("https://example.test/a", "https://example.test/b")
        assertEquals(two, links("https://example.test/a\nhttps://example.test/b").map { (it.item as LinkAnnotation.Url).url })
        assertEquals(two, links("https://example.test/a\thttps://example.test/b").map { (it.item as LinkAnnotation.Url).url })
        assertEquals("https://example.test/a", (links("https://example.test/a\r\nnext")[0].item as LinkAnnotation.Url).url)
    }

    @Test fun linkified_bareSchemeNeedsOneMoreCharacter() {
        assertTrue(links("see https:// now").isEmpty())   // \S+ needs at least one character after "://"
        assertTrue(links("https://").isEmpty())
        // pins current behaviour: "https://." matches, then trimEnd('.') leaves the bare scheme as the URL
        assertEquals("https://", (links("https://.")[0].item as LinkAnnotation.Url).url)
    }

    @Test fun linkified_nonAsciiAroundAndInsideUrls() {
        val text = "سلام https://example.test/مسیر 🎉"
        val l = links(text)
        assertEquals("https://example.test/مسیر", (l[0].item as LinkAnnotation.Url).url)
        assertEquals(text, linkified(text, linkColor).text)
    }
}
