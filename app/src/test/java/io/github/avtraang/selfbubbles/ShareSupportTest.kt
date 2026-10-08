package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure helpers behind receiving from the share sheet (ShareSupport.kt). */
class ShareSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun accept(action: String, hasText: Boolean, schemes: List<String?>) =
        checkShareIntent(action, null, hasText, schemes) as ShareCheck.Accept

    private fun reject(action: String?, hasText: Boolean, schemes: List<String?>, count: Int = schemes.size) =
        (checkShareIntent(action, null, hasText, schemes, count) as ShareCheck.Reject).reason

    // ---- intent validation ----

    @Test fun check_refusesStreamsOnOurOwnFileProvider() {
        val r = checkShareIntent(
            action = SHARE_ACTION_SEND, mime = "image/jpeg", hasText = false,
            uriSchemes = listOf("content"), uriAuthorities = listOf("io.github.avtraang.selfbubbles.fileprovider"),
            ownAuthority = "io.github.avtraang.selfbubbles.fileprovider",
        )
        assertTrue(r is ShareCheck.Reject && r.reason == ShareReject.BAD_URI)
        val ok = checkShareIntent(
            action = SHARE_ACTION_SEND, mime = "image/jpeg", hasText = false,
            uriSchemes = listOf("content"), uriAuthorities = listOf("com.android.providers.media.documents"),
            ownAuthority = "io.github.avtraang.selfbubbles.fileprovider",
        )
        assertTrue(ok is ShareCheck.Accept)
    }

    @Test fun send_textOnly_isAccepted() {
        val a = accept(SHARE_ACTION_SEND, hasText = true, schemes = emptyList())
        assertTrue(a.text)
        assertEquals(0, a.streams)
    }

    @Test fun send_oneStream_isAccepted_withOrWithoutText() {
        assertEquals(ShareCheck.Accept(text = false, streams = 1), accept(SHARE_ACTION_SEND, false, listOf("content")))
        assertEquals(ShareCheck.Accept(text = true, streams = 1), accept(SHARE_ACTION_SEND, true, listOf("content")))
    }

    @Test fun send_twoStreams_isTooMany() {
        assertEquals(ShareReject.TOO_MANY, reject(SHARE_ACTION_SEND, false, listOf("content", "content")))
    }

    @Test fun sendMultiple_streams_areAccepted_textIgnored() {
        val a = accept(SHARE_ACTION_SEND_MULTIPLE, hasText = true, schemes = List(3) { "content" })
        assertFalse(a.text)
        assertEquals(3, a.streams)
    }

    @Test fun sendMultiple_withoutStreams_isNothing() {
        assertEquals(ShareReject.NOTHING, reject(SHARE_ACTION_SEND_MULTIPLE, true, emptyList()))
    }

    @Test fun send_nothingAtAll_isNothing() {
        assertEquals(ShareReject.NOTHING, reject(SHARE_ACTION_SEND, false, emptyList()))
    }

    @Test fun wrongAction_isRejected() {
        assertEquals(ShareReject.WRONG_ACTION, reject("android.intent.action.VIEW", true, listOf("content")))
        assertEquals(ShareReject.WRONG_ACTION, reject(null, true, emptyList()))
    }

    @Test fun tenItems_fit_elevenDoNot() {
        assertEquals(10, accept(SHARE_ACTION_SEND_MULTIPLE, false, List(SHARE_MAX_ITEMS) { "content" }).streams)
        assertEquals(ShareReject.TOO_MANY, reject(SHARE_ACTION_SEND_MULTIPLE, false, List(SHARE_MAX_ITEMS + 1) { "content" }))
    }

    @Test fun fileAndOtherSchemes_areRejected() {
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND, false, listOf("file")))
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND_MULTIPLE, false, listOf("content", "file")))
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND_MULTIPLE, false, listOf("content", "http")))
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND, false, listOf(null)))
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND, false, listOf("")))
    }

    @Test fun contentScheme_anyCase() {
        assertEquals(1, accept(SHARE_ACTION_SEND, false, listOf("CONTENT")).streams)
    }

    @Test fun countAndSchemes_mustAgree() {
        assertEquals(ShareReject.BAD_URI, reject(SHARE_ACTION_SEND_MULTIPLE, false, listOf("content"), count = 2))
        // The cap is checked on the delivered count before the schemes.
        assertEquals(ShareReject.TOO_MANY, reject(SHARE_ACTION_SEND_MULTIPLE, false, emptyList(), count = 11))
    }

    // ---- size cap ----

    @Test fun sizeCap_isTheTunnelLimit() {
        assertTrue(shareItemFits(null))
        assertTrue(shareItemFits(0))
        assertTrue(shareItemFits(50L * 1024 * 1024))
        assertFalse(shareItemFits(TUNNEL_MAX_BODY_BYTES))
        assertFalse(shareItemFits(TUNNEL_MAX_BODY_BYTES + 1))
        assertEquals(TUNNEL_MAX_BODY_BYTES, SHARE_MAX_ITEM_BYTES)
    }

    // ---- sizes for people ----

    @Test fun byteSizes_readNaturally() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("0 B", formatByteSize(-5))
        assertEquals("12 B", formatByteSize(12))
        assertEquals("1023 B", formatByteSize(1023))
        assertEquals("1 KB", formatByteSize(1024))
        assertEquals("340 KB", formatByteSize(340L * 1024 + 500))
        assertEquals("1.0 MB", formatByteSize(1024L * 1024))
        assertEquals("1.2 MB", formatByteSize((1.2 * 1024 * 1024).toLong()))
        assertEquals("99.9 MB", formatByteSize((99.94 * 1024 * 1024).toLong()))
        assertEquals("2.5 GB", formatByteSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    // ---- ids and folders ----

    @Test fun shareId_isSixteenHex_fromEightBytes() {
        val id = shareIdFrom(byteArrayOf(0, 1, 0x7f, -1, 0x10, 0x20, 0x30, 0x40))
        assertEquals("00017fff10203040", id)
        assertTrue(isValidShareId(id))
        assertEquals("ab00000000000000", shareIdFrom(byteArrayOf(-85)))   // short input is padded, still valid
        assertTrue(isValidShareId(shareIdFrom(byteArrayOf(-85))))
        assertEquals("0001020304050607", shareIdFrom(ByteArray(12) { it.toByte() }))   // long input is cut
    }

    @Test fun shareId_rejectsAnythingElse() {
        assertFalse(isValidShareId(null))
        assertFalse(isValidShareId(""))
        assertFalse(isValidShareId("../../../data"))
        assertFalse(isValidShareId("00017FFF10203040"))         // uppercase
        assertFalse(isValidShareId("00017fff1020304"))          // 15
        assertFalse(isValidShareId("00017fff102030400"))        // 17
        assertFalse(isValidShareId("00017fff1020304g"))
        assertFalse(isValidShareId("00017fff10203040\n"))
    }

    @Test fun shareFolder_isUnderShareIn_onlyForValidIds() {
        assertEquals("share-in/00017fff10203040", shareFolderPath("00017fff10203040"))
        assertNull(shareFolderPath("../x"))
        assertNull(shareFolderPath(null))
    }

    @Test fun staleFolders_areOlderThanADay() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(shareFolderIsStale(lastModified = 1_000, now = 1_000 + day))
        assertTrue(shareFolderIsStale(lastModified = 1_000, now = 1_000 + day + 1))
        assertFalse(shareFolderIsStale(lastModified = 5_000, now = 4_000))   // clock went back: keep
        assertTrue(shareFolderIsStale(0, 10, maxAge = 5))
    }

    // ---- file names ----

    @Test fun extension_comesOffTheCleanedName() {
        assertEquals(".jpg", extensionOf("IMG_0001.JPG"))
        assertEquals(".pdf", extensionOf("../Lease.pdf"))
        assertNull(extensionOf("README"))
        assertNull(extensionOf(".hidden"))
        assertNull(extensionOf("trailing."))
        assertNull(extensionOf("a.very long thing"))
        assertNull(extensionOf("name.toolongext1"))
        assertNull(extensionOf(null))
    }

    @Test fun fileName_keepsItsExtension_elseTheMimes_elseNone() {
        assertEquals("IMG_0001.jpg", shareFileName("IMG_0001.JPG", "jpeg", 1))   // its own extension, lowercased
        assertEquals("shared-1.jpeg", shareFileName(null, "jpeg", 1))
        assertEquals("shared-2.jpeg", shareFileName("   ", ".jpeg", 2))
        assertEquals("notes", shareFileName("notes", null, 3))
        assertEquals("shared-4", shareFileName("", "", 4))
        // The app's cleaner drops separators and "..", it does not take the last segment.
        assertEquals("tmpLease.pdf", shareFileName("/tmp/../Lease.pdf", "pdf", 5))
        assertEquals("Lease.pdf", shareFileName("../Lease.pdf", "pdf", 5))
        assertEquals("evil.pdf", shareFileName("evil‮.pdf", "pdf", 6))
    }

    @Test fun fileName_isCapped() {
        val long = "x".repeat(300) + ".png"
        val out = shareFileName(long, "png", 1)
        assertTrue(out.endsWith(".png"))
        assertTrue(out.length <= PDF_NAME_MAX_CHARS)
    }

    @Test fun uniqueName_countsUp_beforeTheExtension() {
        assertEquals("a.jpg", uniqueFileName("a.jpg", emptySet()))
        assertEquals("a (2).jpg", uniqueFileName("a.jpg", setOf("a.jpg")))
        assertEquals("a (3).jpg", uniqueFileName("a.jpg", setOf("a.jpg", "a (2).jpg")))
        assertEquals("notes (2)", uniqueFileName("notes", setOf("notes")))
    }

    /** A stream another app calls "share.json" must not land on the manifest (stage seeds `taken` with the reserved names). */
    @Test fun manifestName_isReserved_soASharedFileCannotTakeIt() {
        assertEquals("share.json", SHARE_MANIFEST_NAME)
        assertTrue(SHARE_MANIFEST_NAME in shareReservedNames())
        // The sanitiser keeps the name as is; only the reservation moves it aside.
        assertEquals(SHARE_MANIFEST_NAME, shareFileName("share.json", "json", 1))
        val taken = HashSet(shareReservedNames())
        val first = uniqueFileName(shareFileName("share.json", "json", 1), taken)
        assertEquals("share (2).json", first)
        taken += first
        assertEquals("share (3).json", uniqueFileName(shareFileName("share.json", "json", 2), taken))
    }

    // ---- progress toast ----

    @Test fun progressToast_waitsOutTheRestOfTheSecond_fromWhenTheCopyBegan() {
        assertEquals(SHARE_PROGRESS_TOAST_AFTER_MILLIS, shareProgressDelay(0))
        assertEquals(600, shareProgressDelay(400))
        assertEquals(0, shareProgressDelay(SHARE_PROGRESS_TOAST_AFTER_MILLIS))
        assertEquals(0, shareProgressDelay(5_000))    // a recreated activity mid-copy says so at once
        assertEquals(SHARE_PROGRESS_TOAST_AFTER_MILLIS, shareProgressDelay(-10))   // a clock oddity never waits longer than the threshold
    }

    // ---- launch seq across process death ----

    @Test fun launchSeq_freshCreate_takesTheNextNumber() {
        val counter = java.util.concurrent.atomic.AtomicLong()
        assertEquals(1, launchSeqOnCreate(counter, saved = 0))
        assertEquals(2, launchSeqOnCreate(counter, saved = 0))
        assertEquals(2, counter.get())
    }

    @Test fun launchSeq_restoredAfterProcessDeath_keepsNewDeliveriesAbove() {
        // Launcher start (1), one share (2), process killed, restored with saved seq 2.
        val counter = java.util.concurrent.atomic.AtomicLong()   // the new process starts at 0
        val restored = launchSeqOnCreate(counter, saved = 2)
        assertEquals(2, restored)                                 // the recreated request keeps its seq …
        val handled = restored                                    // … which the restored VM already handled
        assertFalse(launchRequestIsNew(restored, handled))        // so it is not acted on again
        // A notification tap and a share, delivered through onNewIntent, must both route.
        val tap = counter.incrementAndGet()
        val share = counter.incrementAndGet()
        assertEquals(3, tap)
        assertTrue(launchRequestIsNew(tap, handled))
        assertTrue(launchRequestIsNew(share, tap))
    }

    @Test fun launchSeq_restoredWhileNeverHandled_isStillNew() {
        // Killed while the app was locked: the VM never existed, nothing was handled.
        val counter = java.util.concurrent.atomic.AtomicLong()
        assertTrue(launchRequestIsNew(launchSeqOnCreate(counter, saved = 2), handled = 0))
    }

    @Test fun launchSeq_restore_neverLowersTheCounter() {
        val counter = java.util.concurrent.atomic.AtomicLong(5)   // process alive, activity recreated later
        assertEquals(2, launchSeqOnCreate(counter, saved = 2))
        assertEquals(6, counter.incrementAndGet())
    }

    // ---- targets ----

    @Test fun everyThreadWithAGuid_takesFiles_gmessagesIncluded() {
        assertTrue(canReceiveSharedFiles(Thread(chat_guid = "iMessage;-;+15551234567", last_rowid = 0)))
        assertTrue(canReceiveSharedFiles(Thread(chat_guid = "bp:123", network = "gmessages", last_rowid = 0)))
        assertFalse(canReceiveSharedFiles(Thread(chat_guid = "  ", last_rowid = 0)))
    }

    // ---- manifest -> pending share ----

    @Test fun manifest_dropsMissingAndEscapingEntries() {
        val dir = tmp.newFolder("0123456789abcdef")
        File(dir, "a.jpg").writeBytes(ByteArray(10))
        val m = ShareManifest(
            text = "hello",
            items = listOf(
                ShareManifestItem("a.jpg", "image/jpeg", "a.jpg", 10),
                ShareManifestItem("gone.jpg", "image/jpeg", "gone.jpg", 10),
                ShareManifestItem("../a.jpg", "image/jpeg", "a.jpg", 10),
                ShareManifestItem("..", "image/jpeg", "a.jpg", 10),
                ShareManifestItem("", "image/jpeg", "a.jpg", 10),
            ),
        )
        val s = pendingShareFrom("0123456789abcdef", dir, m)
        assertEquals(1, s.items.size)
        assertEquals(File(dir, "a.jpg"), s.items[0].file)
        assertTrue(s.items[0].isImage)
        assertFalse(s.items[0].isVideo)
        assertEquals("hello", s.text)
        assertTrue(s.hasFiles)
    }

    @Test fun manifest_entryNamedLikeTheManifest_isDropped() {
        val dir = tmp.newFolder("0123456789abcdea")
        File(dir, SHARE_MANIFEST_NAME).writeText("{}")
        File(dir, "share (2).json").writeBytes(ByteArray(4))
        val m = ShareManifest(
            items = listOf(
                ShareManifestItem(SHARE_MANIFEST_NAME, "application/json", "share.json", 2),
                ShareManifestItem("share (2).json", "application/json", "share (2).json", 4),
            ),
        )
        val s = pendingShareFrom("0123456789abcdea", dir, m)
        assertEquals(listOf(File(dir, "share (2).json")), s.items.map { it.file })
    }

    @Test fun manifest_blankText_isNoText() {
        val dir = tmp.newFolder("0123456789abcdee")
        val s = pendingShareFrom("0123456789abcdee", dir, ShareManifest(text = "  \n"))
        assertNull(s.text)
        assertFalse(s.hasFiles)
    }

    @Test fun manifest_roundTripsThroughJson() {
        val m = ShareManifest("t", listOf(ShareManifestItem("f.pdf", "application/pdf", "f.pdf", 3)))
        val back = json.decodeFromString(ShareManifest.serializer(), json.encodeToString(ShareManifest.serializer(), m))
        assertEquals(m, back)
    }

    // ---- draft ----

    @Test fun sharedText_joinsTheDraft_onANewLine() {
        assertEquals("https://x.y", appendToDraft("", "https://x.y"))
        assertEquals("https://x.y", appendToDraft("   ", "https://x.y"))
        assertEquals("hi\nhttps://x.y", appendToDraft("hi", "https://x.y"))
        assertEquals("hi\nhttps://x.y", appendToDraft("hi\n", "https://x.y"))
    }

    // ---- direct-share target -> thread ----

    @Test fun shareTarget_resolvesFromTheLoadedList_elseThePicker() {
        val a = Thread(chat_guid = "a", chat_name = "A", last_rowid = 0)
        val b = Thread(chat_guid = "b", chat_name = "B", last_rowid = 0, archived = true)
        val list = listOf(a, b)
        assertEquals(a, resolveShareTarget(list, "a"))
        // The owner chose it in the share sheet, archived or not.
        assertEquals(b, resolveShareTarget(list, "b"))
        // Unknown to the list (left it, stale shortcut), no target, or no list yet: the picker.
        assertNull(resolveShareTarget(list, "gone"))
        assertNull(resolveShareTarget(list, null))
        assertNull(resolveShareTarget(list, ""))
        assertNull(resolveShareTarget(emptyList(), "a"))
    }

    @Test fun shareTargetWait_isBoundedButNotAThreeSecondClock() {
        // The wait ends when a load of the list finishes; this only bounds a relay that never answers.
        assertTrue(SHARE_TARGET_LIST_WAIT_MILLIS > 3_000)
        assertTrue(SHARE_TARGET_LIST_WAIT_MILLIS <= 30_000)
    }
}
