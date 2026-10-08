package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the built-in video player (VideoSupport.kt). */
class VideoSupportTest {

    private val relay: (String) -> Boolean = { it.startsWith("https://relay.example/") }

    // ---- video detection ----

    @Test fun detect_mime_plainOddCaseAndParameters() {
        assertTrue(isVideoAttachment("video/quicktime", null))
        assertTrue(isVideoAttachment("video/mp4", "clip"))
        assertTrue(isVideoAttachment("Video/MP4", "clip.bin"))
        assertTrue(isVideoAttachment("VIDEO/QuickTime; codecs=\"hvc1\"", null))
        assertTrue(isVideoAttachment("  video/3gpp ;name=\"a.3gp\"", null))
        assertTrue(isVideoAttachment("video/x-matroska", "x"))
    }

    @Test fun detect_nameOnly() {
        for (ext in listOf("mov", "mp4", "m4v", "3gp", "webm", "mkv")) {
            assertTrue(ext, isVideoAttachment(null, "IMG_0001.$ext"))
            assertTrue(ext, isVideoAttachment("", "IMG_0001.${ext.uppercase()}"))
            assertTrue(ext, isVideoAttachment("application/octet-stream", "clip.$ext"))
            assertTrue(ext, isVideoAttachment("*/*", "clip.$ext  "))
        }
        assertTrue(isVideoAttachment(null, "My Holiday.MoV"))
    }

    @Test fun detect_neither() {
        assertFalse(isVideoAttachment("image/jpeg", "photo.jpg"))
        assertFalse(isVideoAttachment("image/heic", "IMG_0001.HEIC"))
        assertFalse(isVideoAttachment("application/pdf", "lease.pdf"))
        assertFalse(isVideoAttachment("audio/mp4", "voice.m4a"))
        assertFalse(isVideoAttachment("text/video/x", "notes.txt"))
        assertFalse(isVideoAttachment("application/x-video", "mov"))
        assertFalse(isVideoAttachment("application/octet-stream", "mp4"))
        assertFalse(isVideoAttachment(null, ".mov"))              // an extension alone is not a name
        assertFalse(isVideoAttachment(null, "clip.mov.exe"))
        assertFalse(isVideoAttachment(null, "clip.movie"))
    }

    @Test fun detect_nullAndBlank() {
        assertFalse(isVideoAttachment(null, null))
        assertFalse(isVideoAttachment("", ""))
        assertFalse(isVideoAttachment("   ", "   "))
        assertFalse(isVideoAttachment(";", null))
    }

    // ---- type to hand on ----

    @Test fun mime_keepsTheAttachmentsOwnVideoType() {
        assertEquals("video/quicktime", videoMimeFor("video/quicktime", "a.mp4"))
        assertEquals("video/mp4", videoMimeFor("Video/MP4; codecs=\"avc1\"", null))
        assertEquals("video/x-msvideo", videoMimeFor("video/x-msvideo", "clip.avi"))
    }

    @Test fun mime_fromTheExtensionWhenTheTypeIsNotVideo() {
        assertEquals("video/quicktime", videoMimeFor(null, "IMG_0001.MOV"))
        assertEquals("video/mp4", videoMimeFor("application/octet-stream", "clip.mp4"))
        assertEquals("video/mp4", videoMimeFor("", "clip.m4v"))
        assertEquals("video/3gpp", videoMimeFor("*/*", "clip.3gp"))
        assertEquals("video/webm", videoMimeFor(null, "clip.webm"))
        assertEquals("video/x-matroska", videoMimeFor(null, "clip.mkv"))
    }

    @Test fun mime_fallsBackWhenNothingSaysVideo() {
        assertEquals(VIDEO_MIME_FALLBACK, videoMimeFor(null, null))
        assertEquals(VIDEO_MIME_FALLBACK, videoMimeFor("application/octet-stream", "clip"))
        assertEquals(VIDEO_MIME_FALLBACK, videoMimeFor("image/jpeg", "photo.jpg"))
        assertTrue(VIDEO_MIME_FALLBACK.startsWith("video/"))   // Downloads.save files it under Movies
    }

    // ---- display name ----

    @Test fun displayName_trimsAndFallsBack() {
        assertEquals("IMG_0001.MOV", videoDisplayName("  IMG_0001.MOV "))
        assertEquals(VIDEO_FALLBACK_NAME, videoDisplayName(null))
        assertEquals(VIDEO_FALLBACK_NAME, videoDisplayName(""))
        assertEquals(VIDEO_FALLBACK_NAME, videoDisplayName("   "))
    }

    // ---- target across rotation ----

    @Test fun savedTarget_roundTrips() {
        val t = VideoTarget("https://relay.example/attachment/7", "IMG_0001.MOV", "video/quicktime")
        assertEquals(t, restoreVideoTarget(saveVideoTarget(t), relay))
        val unnamed = VideoTarget("https://relay.example/attachment/8", null, "video/mp4")
        assertEquals(unnamed, restoreVideoTarget(saveVideoTarget(unnamed), relay))
        assertEquals(null, saveVideoTarget(null))
    }

    @Test fun savedTarget_offRelayOrMalformed_isDropped() {
        val evil = VideoTarget("https://evil.example/x.mov", "x.mov", "video/quicktime")
        assertEquals(null, restoreVideoTarget(saveVideoTarget(evil), relay))
        assertEquals(null, restoreVideoTarget(null, relay))
        assertEquals(null, restoreVideoTarget("https://relay.example/a", relay))
        assertEquals(null, restoreVideoTarget(emptyList<String>(), relay))
        assertEquals(null, restoreVideoTarget(listOf(42, "x.mov", "video/quicktime"), relay))
        assertEquals(null, restoreVideoTarget(listOf("https://relay.example/a", "x.mov"), relay))        // no type
        assertEquals(null, restoreVideoTarget(listOf("https://relay.example/a", "x.mov", 7), relay))
        assertEquals(
            VideoTarget("https://relay.example/a", null, "video/mp4"),
            restoreVideoTarget(listOf("https://relay.example/a", 42, "video/mp4"), relay),
        )
    }

    // ---- playback across rotation ----

    @Test fun savedPlayback_roundTrips() {
        for (p in listOf(VideoPlayback(0L, true), VideoPlayback(12_345L, false), VideoPlayback(Long.MAX_VALUE, true))) {
            assertEquals(p, restoreVideoPlayback(saveVideoPlayback(p)))
        }
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(saveVideoPlayback(VIDEO_PLAYBACK_START)))
    }

    @Test fun savedPlayback_firstOpen_startsFromTheBeginningPlaying() {
        assertEquals(0L, VIDEO_PLAYBACK_START.positionMs)
        assertTrue(VIDEO_PLAYBACK_START.playWhenReady)
    }

    @Test fun savedPlayback_malformed_isAFreshStart() {
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(null))
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback("12345"))
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(emptyList<Any>()))
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(listOf("12345", true)))
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(listOf(12345L)))
        assertEquals(VIDEO_PLAYBACK_START, restoreVideoPlayback(listOf(12345L, "true")))
        // a negative saved position comes back as the start, playing state kept
        assertEquals(VideoPlayback(0L, false), restoreVideoPlayback(listOf(-5L, false)))
        // an Int position (a Bundle may widen or narrow) is accepted
        assertEquals(VideoPlayback(500L, true), restoreVideoPlayback(listOf(500, true)))
    }

    // ---- position clamping ----

    @Test fun clamp_withinKnownDuration() {
        assertEquals(0L, clampVideoPosition(0L, 10_000L))
        assertEquals(5_000L, clampVideoPosition(5_000L, 10_000L))
        assertEquals(10_000L, clampVideoPosition(10_000L, 10_000L))
        assertEquals(10_000L, clampVideoPosition(10_001L, 10_000L))
        assertEquals(10_000L, clampVideoPosition(Long.MAX_VALUE, 10_000L))
    }

    @Test fun clamp_neverNegative() {
        assertEquals(0L, clampVideoPosition(-1L, 10_000L))
        assertEquals(0L, clampVideoPosition(Long.MIN_VALUE, 10_000L))
        assertEquals(0L, clampVideoPosition(-1L, -1L))
        assertEquals(0L, clampVideoPosition(Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test fun clamp_unknownDuration_onlyFloorsAtZero() {
        // media3 reports TIME_UNSET (a large negative) or 0 before the stream has been read
        for (unknown in listOf(0L, -1L, Long.MIN_VALUE + 1)) {
            assertEquals(5_000L, clampVideoPosition(5_000L, unknown))
            assertEquals(Long.MAX_VALUE, clampVideoPosition(Long.MAX_VALUE, unknown))
            assertEquals(0L, clampVideoPosition(-5_000L, unknown))
        }
    }

    // ---- seek step ----

    @Test fun seekIncrement_isTenSeconds() {
        assertEquals(10_000L, VIDEO_SEEK_INCREMENT_MS)
    }

    // ---- playback errors ----

    @Test fun error_404MeansTheFileIsGoneFromTheMac() {
        assertEquals(VideoError.MISSING, videoError(404))
        assertEquals(VideoError.MISSING, videoError(ATTACHMENT_MISSING_STATUS))
        assertEquals(ATTACHMENT_MISSING_MESSAGE, videoError(404).message)
        assertEquals(404, ATTACHMENT_MISSING_STATUS)
    }

    @Test fun error_anythingElseIsAPlainFailure() {
        for (status in listOf(null, 200, 206, 401, 403, 410, 500, 502, 503)) {
            assertEquals(status.toString(), VideoError.FAILED, videoError(status))
        }
        assertEquals(VIDEO_PLAYBACK_FAILED_MESSAGE, videoError(500).message)
        assertEquals("Can't play this video here", VIDEO_PLAYBACK_FAILED_MESSAGE)
    }

    @Test fun error_missingReadsUnlikeAnyOtherFailure() {
        // The owner must be able to tell a file that is gone from a download that merely failed.
        assertFalse(VideoError.MISSING.message == VideoError.FAILED.message)
        assertFalse(ATTACHMENT_MISSING_MESSAGE == VIDEO_OPEN_FAILED_MESSAGE)
        assertFalse(ATTACHMENT_MISSING_MESSAGE == VIDEO_TOO_LARGE_MESSAGE)
        assertTrue(ATTACHMENT_MISSING_MESSAGE.isNotBlank())
    }

    // ---- hand-off: the cached file's name ----

    private fun assertSafeName(name: String) {
        assertTrue(name, name.isNotEmpty())
        assertTrue(name, name.length <= PDF_NAME_MAX_CHARS)
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(name, name.contains('/'))
        assertFalse(name, name.contains('\\'))
        assertFalse(name, name.contains(".."))
        assertFalse(name, name.startsWith("."))
        assertFalse(name, name.endsWith("."))
        assertFalse(name, name.any { it.isISOControl() })
        assertFalse(name, name != name.trim())
    }

    @Test fun handoffName_keepsTheVideoExtensionInLowerCase() {
        assertEquals("IMG_0001.mov", sanitizeVideoFileName("IMG_0001.MOV", "video/quicktime"))
        assertEquals("IMG_0001.mov", sanitizeVideoFileName("IMG_0001.mov", "video/mp4"))   // the name's own extension wins
        assertEquals("clip.mp4", sanitizeVideoFileName("clip.MP4", null))
        assertEquals("clip.m4v", sanitizeVideoFileName("  clip.m4v ", "video/mp4"))
        assertEquals("clip.3gp", sanitizeVideoFileName("clip.3GP", "video/3gpp"))
        assertEquals("clip.webm", sanitizeVideoFileName("clip.webm", "video/webm"))
        assertEquals("clip.mkv", sanitizeVideoFileName("clip.mkv", "video/x-matroska"))
        assertEquals("My Holiday.mov", sanitizeVideoFileName("My Holiday.MoV", "video/quicktime"))
    }

    @Test fun handoffName_appendsTheExtensionTheTypeImplies() {
        assertEquals("clip.mov", sanitizeVideoFileName("clip", "video/quicktime"))
        assertEquals("clip.mp4", sanitizeVideoFileName("clip", "Video/MP4; codecs=\"avc1\""))
        assertEquals("clip.3gp", sanitizeVideoFileName("clip", "video/3gpp"))
        assertEquals("clip.webm", sanitizeVideoFileName("clip", "video/webm"))
        assertEquals("clip.mkv", sanitizeVideoFileName("clip", "video/x-matroska"))
        // a disguised or foreign extension is kept as part of the stem, like the PDF sanitiser does
        assertEquals("clip.avi.mov", sanitizeVideoFileName("clip.avi", "video/quicktime"))
        assertEquals("clip.exe.mp4", sanitizeVideoFileName("clip.exe", "video/mp4"))
    }

    @Test fun handoffName_noKnownExtension_leavesTheNameWithoutOne() {
        assertEquals("clip", sanitizeVideoFileName("clip", VIDEO_MIME_FALLBACK))
        assertEquals("clip", sanitizeVideoFileName("clip", null))
        assertEquals("clip.avi", sanitizeVideoFileName("clip.avi", "video/x-msvideo"))
        assertEquals("video", sanitizeVideoFileName(null, null))
        assertEquals("video", sanitizeVideoFileName("", "video/*"))
    }

    @Test fun handoffName_fallsBackWhenNothingUsableIsLeft() {
        assertEquals("video.mov", sanitizeVideoFileName(null, "video/quicktime"))
        assertEquals("video.mov", sanitizeVideoFileName("", "video/quicktime"))
        assertEquals("video.mov", sanitizeVideoFileName("   ", "video/quicktime"))
        assertEquals("video.mov", sanitizeVideoFileName(".mov", "video/quicktime"))
        assertEquals("video.mov", sanitizeVideoFileName("..", "video/quicktime"))
        assertEquals("video.mp4", sanitizeVideoFileName("/\\/\\", "video/mp4"))
        assertEquals("video.mp4", sanitizeVideoFileName("\u0001\u0002", "video/mp4"))
        assertEquals("video.mp4", sanitizeVideoFileName(".", "video/mp4"))
        assertEquals("video.mp4", sanitizeVideoFileName("....", "video/mp4"))
    }

    @Test fun handoffName_traversalSeparatorsAndLeadingDots() {
        assertEquals("etcpasswd.mov", sanitizeVideoFileName("../../etc/passwd", "video/quicktime"))
        assertEquals("abc.mov", sanitizeVideoFileName("a/b\\c.mov", "video/quicktime"))
        assertEquals("hidden.mov", sanitizeVideoFileName(".hidden.mov", "video/quicktime"))
        assertEquals("hidden.mov", sanitizeVideoFileName("...hidden.MOV", null))
        for (evil in listOf("../x.mov", "/abs/path.mov", "..././..././x", "....//x.mp4", "a/../../b.mp4", "a.../...b")) {
            assertSafeName(sanitizeVideoFileName(evil, "video/mp4"))
        }
    }

    @Test fun handoffName_stripsControlAndDirectionCharacters() {
        assertEquals("ab.mov", sanitizeVideoFileName("a\u0000b.mov", "video/quicktime"))
        assertEquals("line1line2.mov", sanitizeVideoFileName("line1\r\nline2\t.mov", "video/quicktime"))
        // "clip" + RLO + "vom.4pm" displays as "clip mp4.mov"; the override goes, the real extension follows
        assertEquals("clipvom.4pm.mov", sanitizeVideoFileName("clip‮vom.4pm", "video/quicktime"))
        // a control character after the extension does not hide it
        assertEquals("IMG_0001.mov", sanitizeVideoFileName("IMG_0001.MOV\u0000", "video/quicktime"))
        for (c in listOf('؜', '‎', '‏', '‪', '‮', '⁦', '⁩')) {
            assertFalse(sanitizeVideoFileName("a${c}b.mov", null).contains(c))
        }
    }

    @Test fun handoffName_veryLongNamesAreCapped() {
        val long = sanitizeVideoFileName("x".repeat(5000) + ".mov", "video/quicktime")
        assertEquals(PDF_NAME_MAX_CHARS, long.length)
        assertTrue(long.endsWith(".mov"))
        assertSafeName(long)

        val cjk = sanitizeVideoFileName("契".repeat(400) + ".mp4", null)   // the byte cap binds first
        assertSafeName(cjk)
        assertTrue(cjk.startsWith("契"))
        assertTrue(cjk.endsWith(".mp4"))

        val emoji = sanitizeVideoFileName("🎬".repeat(300), "video/mp4")   // surrogate pairs never cut in half
        assertSafeName(emoji)
        val stem = emoji.removeSuffix(".mp4")
        assertFalse(stem.last().isHighSurrogate())
        assertEquals(0, stem.length % 2)
    }

    @Test fun handoffName_unicodeNamesKept() {
        assertEquals("Urlaub 2026 – Übersicht.mov", sanitizeVideoFileName("Urlaub 2026 – Übersicht.MOV", "video/quicktime"))
        assertEquals("動画.mp4", sanitizeVideoFileName("動画.mp4", "video/mp4"))
        assertEquals("חופשה.mov", sanitizeVideoFileName("חופשה", "video/quicktime"))
    }

    // ---- hand-off: one folder per attachment ----

    @Test fun cacheDir_isAPlainFolderNamePerUrl() {
        val a = videoCacheDirName("https://relay.example/attachment/7")
        assertTrue(a, a.startsWith("video-"))
        assertEquals("video-".length + 16, a.length)
        assertTrue(a, a.removePrefix("video-").all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(a.contains('/'))
        assertFalse(a.contains('\\'))
        assertFalse(a.contains(".."))
        assertEquals(a, videoCacheDirName("https://relay.example/attachment/7"))   // stable across calls
    }

    @Test fun cacheDir_sameNameDifferentAttachment_neverSharesAPath() {
        val first = videoCacheDirName("https://relay.example/attachment/1")
        val second = videoCacheDirName("https://relay.example/attachment/2")
        assertFalse(first == second)
        // and it is not the PDF viewer's folder for the same URL either
        assertFalse(first == pdfCacheDirName("https://relay.example/attachment/1"))
        assertEquals("IMG_0001.mov", sanitizeVideoFileName("IMG_0001.MOV", "video/quicktime"))   // same leaf name is fine
    }

    // ---- hand-off: size cap ----

    @Test fun handoffCap_isBoundedAndNotBelowThePdfOne() {
        assertTrue(VIDEO_HANDOFF_MAX_BYTES > 0L)
        assertTrue(VIDEO_HANDOFF_MAX_BYTES >= PDF_HANDOFF_MAX_BYTES)
        assertTrue(VIDEO_HANDOFF_MAX_BYTES < Long.MAX_VALUE)
    }
}
