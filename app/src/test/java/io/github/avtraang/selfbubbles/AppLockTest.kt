package io.github.avtraang.selfbubbles

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision helpers behind the app lock (AppLock.kt). */
class AppLockTest {

    private val grace = LockGrace.ONE_MINUTE.millis
    private val t0 = 1_000_000L

    // ---- shouldLock ----

    @Test fun shouldLock_freshProcess_neverUnlocked_locks() {
        // A fresh process: nothing unlocked yet, nothing backgrounded yet.
        assertTrue(shouldLock(enabled = true, now = t0, lastBackground = null, graceMillis = grace, everUnlocked = false))
        // Even a background mark does not change that.
        assertTrue(shouldLock(enabled = true, now = t0, lastBackground = t0 - 1, graceMillis = grace, everUnlocked = false))
    }

    @Test fun shouldLock_unlockedAndNeverBackgrounded_staysOpen() {
        // A configuration change lands here: unlocked earlier, no real background since.
        assertFalse(shouldLock(enabled = true, now = t0, lastBackground = null, graceMillis = grace, everUnlocked = true))
    }

    @Test fun shouldLock_elapsedBelowGrace_staysOpen() {
        assertFalse(shouldLock(true, now = t0 + grace - 1, lastBackground = t0, graceMillis = grace, everUnlocked = true))
        assertFalse(shouldLock(true, now = t0, lastBackground = t0, graceMillis = grace, everUnlocked = true))
    }

    @Test fun shouldLock_elapsedAtGrace_locks() {
        assertTrue(shouldLock(true, now = t0 + grace, lastBackground = t0, graceMillis = grace, everUnlocked = true))
    }

    @Test fun shouldLock_elapsedAboveGrace_locks() {
        assertTrue(shouldLock(true, now = t0 + grace + 1, lastBackground = t0, graceMillis = grace, everUnlocked = true))
        assertTrue(shouldLock(true, now = t0 + 10 * grace, lastBackground = t0, graceMillis = grace, everUnlocked = true))
    }

    @Test fun shouldLock_graceImmediately_locksOnAnyBackground() {
        val none = LockGrace.IMMEDIATELY.millis
        assertEquals(0L, none)
        assertTrue(shouldLock(true, now = t0, lastBackground = t0, graceMillis = none, everUnlocked = true))
        assertTrue(shouldLock(true, now = t0 + 1, lastBackground = t0, graceMillis = none, everUnlocked = true))
        // ...but a configuration change (no background mark) still does not.
        assertFalse(shouldLock(true, now = t0, lastBackground = null, graceMillis = none, everUnlocked = true))
    }

    @Test fun shouldLock_clockWentBackwards_locks() {
        // A negative gap is not proof of a short absence; err towards the lock.
        assertTrue(shouldLock(true, now = t0 - 1, lastBackground = t0, graceMillis = grace, everUnlocked = true))
        assertTrue(shouldLock(true, now = 0L, lastBackground = t0, graceMillis = grace, everUnlocked = true))
    }

    @Test fun shouldLock_disabled_neverLocks() {
        assertFalse(shouldLock(false, now = t0, lastBackground = null, graceMillis = grace, everUnlocked = false))
        assertFalse(shouldLock(false, now = t0 + 10 * grace, lastBackground = t0, graceMillis = grace, everUnlocked = true))
        assertFalse(shouldLock(false, now = t0 - 1, lastBackground = t0, graceMillis = 0L, everUnlocked = true))
    }

    // ---- authenticator flags per API level ----

    @Test fun authenticators_api30AndUp_strongOrCredential() {
        val expected = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        for (api in listOf(30, 31, 33, 34, 35, 36, 37)) {
            assertEquals("API $api", expected, allowedAuthenticators(api))
        }
    }

    @Test fun authenticators_api28and29_weakOrCredential() {
        // The library forbids STRONG together with DEVICE_CREDENTIAL before API 30.
        val expected = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        assertEquals(expected, allowedAuthenticators(28))
        assertEquals(expected, allowedAuthenticators(29))
    }

    @Test fun authenticators_alwaysAllowTheDeviceCredential() {
        for (api in 28..37) {
            assertTrue("API $api", allowedAuthenticators(api) and Authenticators.DEVICE_CREDENTIAL != 0)
        }
    }

    // ---- grace option <-> preference value ----

    @Test fun grace_roundTripsThroughThePreferenceValue() {
        for (option in LockGrace.entries) {
            assertSame(option, LockGrace.fromPref(option.prefValue))
        }
    }

    @Test fun grace_prefValuesAreDistinct() {
        assertEquals(LockGrace.entries.size, LockGrace.entries.map { it.prefValue }.toSet().size)
        assertEquals(4, LockGrace.entries.size)
    }

    @Test fun grace_unknownOrMissingValue_isTheDefault() {
        assertSame(LockGrace.ONE_MINUTE, LockGrace.DEFAULT)
        assertSame(LockGrace.DEFAULT, LockGrace.fromPref(null))
        assertSame(LockGrace.DEFAULT, LockGrace.fromPref(""))
        assertSame(LockGrace.DEFAULT, LockGrace.fromPref("2h"))
    }

    @Test fun grace_millisMatchTheirLabels() {
        assertEquals(0L, LockGrace.IMMEDIATELY.millis)
        assertEquals(60_000L, LockGrace.ONE_MINUTE.millis)
        assertEquals(5 * 60_000L, LockGrace.FIVE_MINUTES.millis)
        assertEquals(30 * 60_000L, LockGrace.THIRTY_MINUTES.millis)
    }

    // ---- lock state machine (fresh instances; AppLock is one of these) ----

    /** A state that has been through one launch and one unlock, in front, nothing pending. */
    private fun unlockedInFront(): LockState = LockState().apply {
        onStart(enabled = true, graceMillis = grace, now = t0)
        unlock()
        consumeAutoPrompt()
    }

    @Test fun freshProcess_startsLocked() {
        val s = LockState()
        assertTrue(s.locked)
        assertFalse(s.everUnlocked)
        assertTrue(s.autoPromptPending)      // the first lock screen prompts on its own
        assertEquals(0, s.startedInstances)
        // ...and onStart with the lock enabled keeps it that way, whatever the clock says.
        s.onStart(enabled = true, graceMillis = grace, now = t0)
        assertTrue(s.locked)
        assertEquals(null, s.lastBackgroundedAt)   // onStart consumes the mark
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 10 * grace)
        assertTrue(s.locked)
        // The process-wide instance starts the same way (nothing in this class unlocks it).
        assertTrue(AppLock.locked)
        assertFalse(AppLock.everUnlocked)
    }

    @Test fun freshProcess_lockDisabled_isOpen() {
        val s = LockState()
        s.onStart(enabled = false, graceMillis = grace, now = t0)
        assertFalse(s.locked)
        // Enabling later (Settings) locks at the next start: nothing has ever authenticated.
        s.onStop(now = t0 + 1, changingConfigurations = false)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 2)
        assertTrue(s.locked)
    }

    @Test fun unlock_thenBackgroundWithinGrace_staysOpen() {
        val s = unlockedInFront()
        s.onStop(now = t0 + 1, changingConfigurations = false)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + grace)   // grace - 1 away
        assertFalse(s.locked)
    }

    @Test fun unlock_thenBackgroundBeyondGrace_locks() {
        val s = unlockedInFront()
        s.onStop(now = t0 + 1, changingConfigurations = false)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 1 + grace)
        assertTrue(s.locked)
        assertTrue(s.autoPromptPending)   // the lock screen may prompt on its own
    }

    @Test fun onStart_neverUnlocks_aRecreatedActivityOverTheLockScreenStaysLocked() {
        // Locked after time away, mark consumed. A notification tap now recreates the
        // activity: its onStart must not read "unlocked once, nothing backgrounded
        // since" as open. Only unlock() opens.
        val s = unlockedInFront()
        s.onStop(now = t0 + 1, changingConfigurations = false)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 1 + grace)
        assertTrue(s.locked)
        assertEquals(null, s.lastBackgroundedAt)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 2 + grace)   // new instance, old still started
        assertTrue(s.locked)
        s.onStop(now = t0 + 2 + grace, changingConfigurations = false)       // old instance stops
        assertTrue(s.locked)
        s.unlock()
        assertFalse(s.locked)
    }

    @Test fun configurationChange_neverStampsOrRelocks() {
        val s = unlockedInFront()
        // Rotation: onStop(changingConfigurations) then onStart, however much later.
        s.onStop(now = t0 + 1, changingConfigurations = true)
        assertEquals(null, s.lastBackgroundedAt)
        assertFalse(s.autoPromptPending)
        s.onStart(enabled = true, graceMillis = LockGrace.IMMEDIATELY.millis, now = t0 + 10 * grace)
        assertFalse(s.locked)
        assertEquals(1, s.startedInstances)
    }

    @Test fun notificationTapHandOver_doesNotStampABackground() {
        // Android order for a recreated activity: NEW.onStart, then OLD.onStop
        // (not a configuration change). The owner was in front the whole time, so
        // the next rotation, however late, must find no background mark.
        val s = unlockedInFront()
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 1)              // new instance
        assertEquals(2, s.startedInstances)
        s.onStop(now = t0 + 2, changingConfigurations = false)                   // old instance
        assertEquals(1, s.startedInstances)
        assertEquals(null, s.lastBackgroundedAt)
        assertFalse(s.autoPromptPending)
        // Rotation well past the grace: still open.
        s.onStop(now = t0 + 10 * grace, changingConfigurations = true)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 10 * grace)
        assertFalse(s.locked)
    }

    @Test fun backPressExit_stillStamps() {
        // The count reaches zero, so leaving the app for real is recorded.
        val s = unlockedInFront()
        s.onStop(now = t0 + 1, changingConfigurations = false)
        assertEquals(0, s.startedInstances)
        assertEquals(t0 + 1L, s.lastBackgroundedAt)
        assertTrue(s.autoPromptPending)
    }

    @Test fun startedInstances_neverGoesNegative() {
        val s = LockState()
        s.onStop(now = t0, changingConfigurations = false)
        assertEquals(0, s.startedInstances)
        assertEquals(t0, s.lastBackgroundedAt)
    }

    @Test fun autoPrompt_armedByRealBackground_consumedOnce() {
        val s = unlockedInFront()
        // A real trip to the background arms the next appearance of the lock screen.
        s.onStop(now = t0 + 1, changingConfigurations = false)
        assertTrue(s.autoPromptPending)
        assertTrue(s.consumeAutoPrompt())
        assertFalse(s.consumeAutoPrompt())   // a recreated lock screen (rotation) gets nothing
        assertFalse(s.autoPromptPending)
        // ...until the next real background.
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 2)
        s.onStop(now = t0 + 3, changingConfigurations = false)
        assertTrue(s.consumeAutoPrompt())
    }

    @Test fun promptInFlight_resetByARealStop_keptAcrossAConfigurationChange() {
        // The library may deliver no terminal callback when the activity holding the
        // prompt is destroyed; a real stop must not leave later automatic prompts suppressed.
        val s = TestLockState()
        s.onStart(enabled = true, graceMillis = grace, now = t0)
        s.markPromptInFlight()
        s.onStop(now = t0 + 1, changingConfigurations = true)      // rotation: the prompt carries over
        assertTrue(s.promptInFlight)
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 1)
        s.markPromptInFlight()
        s.onStart(enabled = true, graceMillis = grace, now = t0 + 2)   // notification tap: new instance
        s.onStop(now = t0 + 3, changingConfigurations = false)         // old instance, prompt gone with it
        assertFalse(s.promptInFlight)
        assertEquals(null, s.lastBackgroundedAt)                       // ...and still no background stamp
        s.markPromptInFlight()
        s.onStop(now = t0 + 4, changingConfigurations = false)         // leaving the app for real
        assertFalse(s.promptInFlight)
    }

    @Test fun unlock_clearsStatusAndMark() {
        val s = TestLockState()
        s.onStart(enabled = true, graceMillis = grace, now = t0)
        s.setStatusForTest("Too many attempts")
        s.unlock()
        assertFalse(s.locked)
        assertTrue(s.everUnlocked)
        assertEquals(null, s.status)
        assertEquals(null, s.lastBackgroundedAt)
    }

    /** Exposes the protected setters the Android prompt callback uses. */
    private class TestLockState : LockState() {
        fun markPromptInFlight() { promptInFlight = true }
        fun setStatusForTest(s: String?) { status = s }
    }

    // ---- availability mapping (fail closed) ----

    @Test fun availability_successAndUnknown_areAvailable() {
        assertTrue(lockAvailabilityFor(BiometricManager.BIOMETRIC_SUCCESS) is LockAvailability.Available)
        assertTrue(lockAvailabilityFor(BiometricManager.BIOMETRIC_STATUS_UNKNOWN) is LockAvailability.Available)
    }

    @Test fun availability_transientOrUnknownErrors_keepTheLock() {
        // Only "nothing on this phone can ever authenticate" may let the content show
        // without the lock; a transient or unknown answer must not fail open.
        for (code in listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            -999,
            42,
        )) {
            assertTrue("code $code", lockAvailabilityFor(code) is LockAvailability.Available)
        }
    }

    @Test fun availability_nothingEnrolledNoHardwareOrUnsupported_isUnavailableWithAReason() {
        for (code in listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        )) {
            val a = lockAvailabilityFor(code)
            assertTrue("code $code", a is LockAvailability.Unavailable)
            assertTrue("code $code", (a as LockAvailability.Unavailable).reason.isNotBlank())
        }
    }

    // ---- push suppression while the lock screen is in front ----

    @Test fun pushIsRedundant_onlyForTheOpenChatInFrontAndUnlocked() {
        assertTrue(pushIsRedundant(foreground = true, gated = false, openChat = "c1", chatGuid = "c1", socketLive = true))
        assertFalse(pushIsRedundant(foreground = true, gated = false, openChat = "c1", chatGuid = "c2", socketLive = true))
        assertFalse(pushIsRedundant(foreground = true, gated = false, openChat = null, chatGuid = "c1", socketLive = true))
        assertFalse(pushIsRedundant(foreground = false, gated = false, openChat = "c1", chatGuid = "c1", socketLive = true))
    }

    @Test fun pushIsRedundant_neverWhileTheLockScreenIsUp() {
        // The VM behind the lock screen still names its last chat as open, but the
        // owner cannot see it, so the message must still reach the shade.
        assertFalse(pushIsRedundant(foreground = true, gated = true, openChat = "c1", chatGuid = "c1", socketLive = true))
        assertFalse(pushIsRedundant(foreground = false, gated = true, openChat = "c1", chatGuid = "c1", socketLive = true))
    }
}
