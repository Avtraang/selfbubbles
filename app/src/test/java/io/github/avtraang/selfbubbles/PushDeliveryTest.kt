package io.github.avtraang.selfbubbles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Notifications that arrive late or not at all (hardening audit 2026-10-08).
 *
 * A4-F7: the notification for a message with a picture was put up only after
 * the whole picture had been downloaded, inside the push handler, with no limit
 * on time or size. On a weak link the photo delayed its own notification, and
 * every notification behind it.
 *
 * A2-F5: the push token went to the relay in one attempt whose answer nobody
 * read. An attempt that failed was silent, and nothing tried again until the
 * app was next started.
 */
class PushDeliveryTest {

    // ---- the text first, the picture after (A4-F7) ----

    private class Shade {
        val events = mutableListOf<String>()
        fun post(picture: String?, alert: Boolean) { events += "post(${picture ?: "text"}, ${if (alert) "alert" else "silent"})" }
    }

    @Test fun theNotificationIsUp_beforeThePictureIsAskedFor() {
        val shade = Shade()
        postThenDecorate(hasPicture = true, post = shade::post, fetch = { shade.events += "fetch"; "picture" }, stillShown = { true })
        assertEquals(listOf("post(text, alert)", "fetch", "post(picture, silent)"), shade.events)
    }

    @Test fun thePictureIsAddedWithoutASecondAlert() {
        val shade = Shade()
        postThenDecorate(true, shade::post, fetch = { "picture" }, stillShown = { true })
        assertEquals(1, shade.events.count { it.endsWith("alert)") })
        assertEquals("post(picture, silent)", shade.events.last())
    }

    @Test fun aPictureThatCannotBeFetched_leavesTheTextNotificationAsItIs() {
        val shade = Shade()
        postThenDecorate(true, shade::post, fetch = { null }, stillShown = { true })
        assertEquals(listOf("post(text, alert)"), shade.events)
    }

    @Test fun aFetchThatFails_leavesTheTextNotificationToo() {
        val shade = Shade()
        postThenDecorate<String>(true, shade::post, fetch = { throw IOException("synthetic: timed out") }, stillShown = { true })
        assertEquals(listOf("post(text, alert)"), shade.events)
    }

    @Test fun aNotificationReadOrClearedDuringTheFetch_isNotPutBack() {
        val shade = Shade()
        postThenDecorate(true, shade::post, fetch = { "picture" }, stillShown = { false })
        assertEquals(listOf("post(text, alert)"), shade.events)
    }

    @Test fun aMessageWithoutAPicture_fetchesNothing() {
        val shade = Shade()
        var fetched = false
        postThenDecorate(false, shade::post, fetch = { fetched = true; "picture" }, stillShown = { true })
        assertEquals(listOf("post(text, alert)"), shade.events)
        assertFalse(fetched)
    }

    // ---- the picture has a size limit (A4-F7) ----

    private fun copy(size: Int, cap: Long): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = copyCapped(ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() }), out, cap)
        return ok to out.toByteArray()
    }

    @Test fun aPictureWithinTheLimit_isCopiedWhole() {
        val (ok, bytes) = copy(size = 20_000, cap = 20_000)
        assertTrue(ok)
        assertArrayEquals(ByteArray(20_000) { (it % 251).toByte() }, bytes)
    }

    @Test fun aPictureOverTheLimit_isGivenUp_andNoMoreThanTheLimitIsRead() {
        val (ok, bytes) = copy(size = 50_000, cap = 20_000)
        assertFalse(ok)
        assertTrue("wrote ${bytes.size}", bytes.size <= 20_000)
    }

    @Test fun anEmptyBody_isACopyThatWorked() {
        val (ok, bytes) = copy(size = 0, cap = 20_000)
        assertTrue(ok)
        assertEquals(0, bytes.size)
    }

    @Test fun theLimitsAreOnesAPhotoFitsAndAPushHandlerSurvives() {
        assertTrue(NOTIF_IMAGE_MAX_BYTES >= 5L * 1024 * 1024)                 // an ordinary phone photo
        assertTrue(NOTIF_IMAGE_TIMEOUT_SECONDS in 3L..10L)                     // well inside Firebase's window for a handler
    }

    // ---- registering for push says when it did not work (A2-F5) ----

    private fun answered(code: Int, body: String) = Result.success(PushRegistrationAnswer(code, isRelayOk(body)))

    @Test fun aRegistrationTheRelayAccepted_saysNothing() {
        assertNull(pushRegistrationLogLine(answered(200, """{"ok": true, "count": 2}""")))
        assertNull(pushRegistrationLogLine(answered(200, """{"count":1,"ok":true}""")))
    }

    @Test fun aRegistrationTheRelayRefused_saysTheStatus() {
        assertEquals("push registration refused by the relay (HTTP 401)", pushRegistrationLogLine(answered(401, """{"detail":"Unauthorized"}""")))
        assertEquals("push registration refused by the relay (HTTP 503)", pushRegistrationLogLine(answered(503, "")))
    }

    @Test fun aSignInPageServedInTheRelaysPlace_isNotAnAcceptedRegistration() {
        // The shared client follows redirects: a sign-in page in front of the relay arrives as a 200.
        val page = "<!DOCTYPE html><html><head><title>Sign in</title></head><body>ok: true</body></html>"
        assertEquals("push registration answered by something that is not the relay (HTTP 200)", pushRegistrationLogLine(answered(200, page)))
        assertEquals("push registration answered by something that is not the relay (HTTP 200)", pushRegistrationLogLine(answered(200, "")))
        assertEquals("push registration answered by something that is not the relay (HTTP 204)", pushRegistrationLogLine(answered(204, "")))
    }

    @Test fun onlyTheRelaysOwnOk_countsAsItsAnswer() {
        assertTrue(isRelayOk("""{"ok": true, "count": 3}"""))
        assertFalse(isRelayOk("""{"ok": false}"""))
        assertFalse(isRelayOk("""{"ok": "true"}"""))                             // a string is not the relay's boolean
        assertFalse(isRelayOk("""{"count": 3}"""))
        assertFalse(isRelayOk("""[{"ok": true}]"""))
        assertFalse(isRelayOk("true"))
        assertFalse(isRelayOk("not json"))
    }

    @Test fun aRegistrationThatDidNotReachTheRelay_saysTheKindOfFailureAndNothingElse() {
        val secret = "synthetic-registration-value"
        val line = pushRegistrationLogLine(Result.failure<PushRegistrationAnswer>(IOException("failed to connect to relay.invalid with $secret")))!!
        assertTrue(line, line.startsWith("push registration failed ("))
        assertTrue(line, "IOException" in line)
        assertFalse(line, secret in line)
        assertFalse(line, "relay.invalid" in line)
    }

    @Test fun anAttemptThatWasCalledOff_isNotReportedAsAFailure() {
        assertNull(pushRegistrationLogLine(Result.failure<PushRegistrationAnswer>(kotlinx.coroutines.CancellationException("synthetic: scope gone"))))
    }

    @Test fun aNotificationJustPostedIsLookedForMoreThanOnce_butNotForLong() {
        assertTrue(NOTIF_LISTED_CHECKS >= 2)
        assertTrue(NOTIF_LISTED_CHECKS * NOTIF_LISTED_PAUSE_MILLIS <= 1_000L)
    }

    // ---- the wait for a picture has an end of its own (review of 2026-10-09) ----

    @Test fun workThatIsDoneInTime_givesItsResult() {
        assertEquals("picture", withinDeadline(2_000) { "picture" })
    }

    @Test fun workThatTakesTooLong_isNotWaitedFor_andIsToldSo() {
        val release = java.util.concurrent.CountDownLatch(1)
        val toldSo = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = java.util.concurrent.CountDownLatch(1)
        val started = System.nanoTime()
        val got = withinDeadline<String>(100) { givenUpOn ->
            release.await()                                                   // a name lookup that stalls
            toldSo.set(givenUpOn())
            finished.countDown()
            "too late"
        }
        val waitedMillis = (System.nanoTime() - started) / 1_000_000
        assertNull(got)
        assertTrue("waited $waitedMillis ms", waitedMillis < 1_500)
        release.countDown()                                                   // the work is left to finish
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(toldSo.get())                                              // and knows nobody wants its result
    }

    @Test fun workThatFails_isNull_notACrash() {
        assertNull(withinDeadline<String>(2_000) { throw IOException("synthetic") })
    }

    @Test fun workThatIsInTime_isNotToldItWasGivenUpOn() {
        assertEquals(false, withinDeadline(2_000) { givenUpOn -> givenUpOn() })
    }
}
