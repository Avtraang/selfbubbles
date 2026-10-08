package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the inline voice-message player (AudioSupport.kt). */
class AudioSupportTest {

    // ---- audio detection ----

    @Test fun detect_mime_plainOddCaseAndParameters() {
        assertTrue(isAudioAttachment("audio/mp4", "Audio Message.m4a"))     // iPhone voice message
        assertTrue(isAudioAttachment("audio/x-m4a", null))
        assertTrue(isAudioAttachment("audio/mpeg", "song"))
        assertTrue(isAudioAttachment("AUDIO/MP4", "clip.bin"))
        assertTrue(isAudioAttachment("Audio/OGG; codecs=opus", null))
        assertTrue(isAudioAttachment("  audio/amr ;name=\"a.amr\"", null))
    }

    @Test fun detect_nameOnly() {
        for (ext in listOf("m4a", "mp3", "caf", "ogg", "oga", "opus", "amr", "aac", "wav", "flac")) {
            assertTrue(ext, isAudioAttachment(null, "Audio Message.$ext"))
            assertTrue(ext, isAudioAttachment("", "memo.${ext.uppercase()}"))
            assertTrue(ext, isAudioAttachment("application/octet-stream", "memo.$ext"))
            assertTrue(ext, isAudioAttachment("*/*", "memo.$ext  "))
        }
    }

    @Test fun detect_neither() {
        assertFalse(isAudioAttachment("video/mp4", "clip.mp4"))
        assertFalse(isAudioAttachment("video/quicktime", "IMG_0001.MOV"))
        assertFalse(isAudioAttachment("image/jpeg", "photo.jpg"))
        assertFalse(isAudioAttachment("application/pdf", "lease.pdf"))
        assertFalse(isAudioAttachment("text/audio/x", "notes.txt"))
        assertFalse(isAudioAttachment("application/x-audio", "m4a"))
        assertFalse(isAudioAttachment(null, ".m4a"))              // an extension alone is not a name
        assertFalse(isAudioAttachment(null, "memo.m4a.exe"))
        assertFalse(isAudioAttachment(null, "memo.mp3x"))
    }

    @Test fun detect_nullAndBlank() {
        assertFalse(isAudioAttachment(null, null))
        assertFalse(isAudioAttachment("", ""))
        assertFalse(isAudioAttachment("   ", "   "))
        assertFalse(isAudioAttachment(";", null))
    }

    // ---- clock ----

    @Test fun clock_minutesAndSeconds() {
        assertEquals("0:00", formatClock(0L))
        assertEquals("0:00", formatClock(999L))          // whole seconds only
        assertEquals("0:01", formatClock(1_000L))
        assertEquals("0:07", formatClock(7_400L))
        assertEquals("0:42", formatClock(42_000L))
        assertEquals("1:00", formatClock(60_000L))
        assertEquals("12:34", formatClock(754_000L))
        assertEquals("59:59", formatClock(3_599_999L))
    }

    @Test fun clock_hoursPastAnHour() {
        assertEquals("1:00:00", formatClock(3_600_000L))
        assertEquals("1:02:03", formatClock(3_723_000L))
        assertEquals("10:00:05", formatClock(36_005_000L))
    }

    @Test fun clock_unknownForNegative() {
        assertEquals(AUDIO_CLOCK_UNKNOWN, formatClock(-1L))
        assertEquals(AUDIO_CLOCK_UNKNOWN, formatClock(Long.MIN_VALUE))
        assertEquals(AUDIO_CLOCK_UNKNOWN, formatClock(Long.MIN_VALUE + 1))   // media3's TIME_UNSET
        assertEquals("–:––", AUDIO_CLOCK_UNKNOWN)
    }

    // ---- slider position ----

    @Test fun fraction_withinKnownDuration() {
        assertEquals(0f, progressFraction(0L, 10_000L), 0f)
        assertEquals(0.5f, progressFraction(5_000L, 10_000L), 1e-6f)
        assertEquals(1f, progressFraction(10_000L, 10_000L), 0f)
        assertEquals(0.25f, progressFraction(1L, 4L), 1e-6f)
    }

    @Test fun fraction_isClamped() {
        assertEquals(1f, progressFraction(10_001L, 10_000L), 0f)
        assertEquals(1f, progressFraction(Long.MAX_VALUE, 10_000L), 0f)
        assertEquals(0f, progressFraction(-1L, 10_000L), 0f)
        assertEquals(0f, progressFraction(Long.MIN_VALUE, 10_000L), 0f)
    }

    @Test fun fraction_unknownDuration_isTheStart() {
        for (unknown in listOf(0L, -1L, Long.MIN_VALUE + 1)) {
            assertEquals(0f, progressFraction(5_000L, unknown), 0f)
            assertEquals(0f, progressFraction(0L, unknown), 0f)
            assertEquals(0f, progressFraction(-5L, unknown), 0f)
        }
    }

    // ---- label ----

    @Test fun label_voiceMessages() {
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("Audio Message.m4a"))
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("Audio Message.caf"))
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("audio message.M4A"))
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("AUDIO MESSAGE"))
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("Audio Message 2.m4a"))
        assertEquals(AUDIO_VOICE_LABEL, audioLabel("  Audio Message.m4a  "))
        assertEquals("Voice message", AUDIO_VOICE_LABEL)
    }

    @Test fun label_otherFiles_loseTheExtension() {
        assertEquals("song", audioLabel("song.mp3"))
        assertEquals("Meeting notes", audioLabel("Meeting notes.M4A"))
        assertEquals("archive.tar", audioLabel("archive.tar.ogg"))      // only the last extension goes
        assertEquals("memo", audioLabel("memo"))                        // no extension to drop
        assertEquals("memo", audioLabel("  memo.wav "))
        assertEquals("My Audio Messages", audioLabel("My Audio Messages.mp3"))   // not at the start
    }

    @Test fun label_fallsBackWhenNothingUsable() {
        assertEquals(AUDIO_FALLBACK_LABEL, audioLabel(null))
        assertEquals(AUDIO_FALLBACK_LABEL, audioLabel(""))
        assertEquals(AUDIO_FALLBACK_LABEL, audioLabel("   "))
        assertEquals(".m4a", audioLabel(".m4a"))                        // a dot-file has no extension to drop
    }

    // ---- errors ----

    @Test fun error_404IsExpired() {
        assertTrue(isExpiredStatus(404))
        assertFalse(isExpiredStatus(403))
        assertFalse(isExpiredStatus(500))
        assertFalse(isExpiredStatus(null))
        assertEquals(AUDIO_EXPIRED_MESSAGE, audioErrorMessage(404))
        assertEquals(AUDIO_ERROR_MESSAGE, audioErrorMessage(500))
        assertEquals(AUDIO_ERROR_MESSAGE, audioErrorMessage(null))
        // One line for every attachment the Mac no longer has, voice messages included.
        assertEquals(ATTACHMENT_MISSING_MESSAGE, AUDIO_EXPIRED_MESSAGE)
        assertEquals(ATTACHMENT_MISSING_STATUS, AUDIO_EXPIRED_STATUS)
        assertFalse(AUDIO_EXPIRED_MESSAGE == AUDIO_ERROR_MESSAGE)
        assertEquals("Can't play this voice message", AUDIO_ERROR_MESSAGE)
    }

    // ---- polling ----

    @Test fun poll_isAboutFourHertz() {
        assertEquals(250L, AUDIO_POLL_MS)
    }

    // ---- clock readings during a switch (AudioController.sync guard) ----

    @Test fun sync_acceptsOnlyTheCurrentMessageWhileLoaded() {
        assertTrue(shouldSyncClock("B", "B", idle = false))
    }

    @Test fun sync_ignoresThePreviousItemStillInThePlayer() {
        // Play A, tap B: stop() reports A's clock while A is still the player's item.
        assertFalse(shouldSyncClock("B", "A", idle = false))
        assertFalse(shouldSyncClock("B", "A", idle = true))
    }

    @Test fun sync_ignoresAnEmptyOrIdlePlayer() {
        assertFalse(shouldSyncClock("B", null, idle = false))     // after clearMediaItems()
        assertFalse(shouldSyncClock("B", "B", idle = true))       // stopped, or a 404 put it in IDLE
        assertFalse(shouldSyncClock(null, null, idle = true))     // nothing ever played
        assertFalse(shouldSyncClock(null, "A", idle = false))
    }

    // ---- long press on the live slider ----

    @Test fun slop_stillFingerIsNotADrag() {
        assertFalse(pastTouchSlop(0f, 0f, 16f))
        assertFalse(pastTouchSlop(16f, 0f, 16f))         // exactly on the edge still counts as still
        assertFalse(pastTouchSlop(-10f, 10f, 16f))       // ~14.1 < 16, either sign
        assertFalse(pastTouchSlop(0f, -16f, 16f))
    }

    @Test fun slop_movedFingerIsADrag() {
        assertTrue(pastTouchSlop(17f, 0f, 16f))
        assertTrue(pastTouchSlop(0f, 17f, 16f))
        assertTrue(pastTouchSlop(12f, 12f, 16f))         // ~17 on the diagonal
        assertTrue(pastTouchSlop(-12f, -12f, 16f))
        assertTrue(pastTouchSlop(1f, 0f, 0f))
    }
}
