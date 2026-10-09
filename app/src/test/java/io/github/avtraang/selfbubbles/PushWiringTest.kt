package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules in PushDelivery.kt and ThreadsLoad.kt are plain functions with
 * tests of their own. What calls them is Android code that cannot be built on
 * the JVM (an AndroidViewModel, a FirebaseMessagingService, a notification
 * builder), so the calls themselves are pinned here in the source, the way
 * ChatVMConstructionTest pins the order of ChatVM.
 *
 * An independent review (2026-10-09) showed why: with the call in
 * socketOpened deleted, or the alert flag of the picture update flipped, every
 * other test still passed, and the app was back to one silent registration
 * per start (audit A2-F5) or to two alerts per photo.
 */
class PushWiringTest {

    private fun source(name: String): String {
        val path = "src/main/java/io/github/avtraang/selfbubbles/$name"     // unit tests run in the app module's directory
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, path), File(it, "app/$path")) }
            .first { it.isFile }
            .readText()
    }

    /** The body of a member declared at one indent: from [signature] to the first line that closes it. */
    private fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is not in the source", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("$signature has no end", end > start)
        return source.substring(start, end)
    }

    private fun assertInOrder(body: String, vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = body.indexOf(part, from)
            assertTrue("\"$part\" is missing, or comes before what should precede it", at >= 0)
            from = at + part.length
        }
    }

    // ---- registering for push (A2-F5) ----

    @Test fun everyConnectionToTheRelay_registersForPush() {
        val body = member(source("ChatVM.kt"), "private fun socketOpened()")
        assertTrue(body, "if (isConfigured) Push.registerWithRelay(getApplication(), viewModelScope)" in body)
    }

    @Test fun anAttemptThatDidNotWork_isLogged_fromBothPlacesThatRegister() {
        val service = source("PushService.kt")
        assertTrue("pushRegistrationLogLine(runCatching { Api.registerPush(token) })?.let { Log.w(TAG, it) }" in member(service, "suspend fun handToRelay(token: String)"))
        assertTrue("scope.launch { handToRelay(tok) }" in member(service, "fun registerWithRelay(ctx: Context, scope: CoroutineScope)"))
        assertTrue("Push.handToRelay(token)" in member(service, "override fun onNewToken(token: String)"))
        assertEquals("only handToRelay talks to the relay about the token", 1, Regex("""Api\.registerPush\(""").findAll(service).count())
    }

    @Test fun whatAnsweredARegistration_isLookedAt() {
        val body = member(source("Imsg.kt"), "suspend fun registerPush(token: String): PushRegistrationAnswer")
        assertTrue(body, "PushRegistrationAnswer(it.code, isRelayOk(" in body)
    }

    // ---- the notification before its picture (A4-F7) ----

    @Test fun theNotificationIsPostedThroughTheRule_andThePictureUpdateDoesNotAlert() {
        val show = member(source("PushService.kt"), "    fun show(")
        assertInOrder(show, "lines.add(line)", "fun post(alert: Boolean, held: List<Line>)", ".setOnlyAlertOnce(!alert)", "postThenDecorate(")
        assertTrue("the message itself is posted from the lines this push started with", "post(alert, lines)" in show)
        assertTrue("fetchImage(ctx, BASE + imageUrl, givenUpOn)" in show)
        assertTrue("withinDeadline((NOTIF_IMAGE_TIMEOUT_SECONDS + 1) * 1000)" in show)
        assertEquals("nothing fetches a picture before the notification is up", 1, Regex("""fetchImage\(ctx,""").findAll(show).count())
    }

    @Test fun thePictureIsFetchedWithALimitOnTimeAndSize() {
        val service = source("PushService.kt")
        assertTrue("callTimeout(NOTIF_IMAGE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)" in service)
        val fetch = member(service, "private fun fetchImage(ctx: Context, url: String, givenUpOn: () -> Boolean)")
        assertInOrder(fetch, "notifImageHttp.newCall(", "copyCapped(r.body!!.byteStream(), o, NOTIF_IMAGE_MAX_BYTES)", "if (!ok || givenUpOn())", "f.delete()")
    }

    // ---- the list in the right order (G4-F4) ----

    @Test fun aListIsShownOnlyAfterBothChecks_andItsLoadIsRemembered() {
        val body = member(source("ChatVM.kt"), "private fun showThreads(load: Int, loadedFrom: String, list: List<Thread>)")
        assertInOrder(
            body,
            "if (!threadsLoadApplies(loadedFrom, RelayConfigStore.current.base)) return",
            "if (!threadListApplies(load, threadsShownLoad)) return",
            "threadsShownLoad = load",
            "threads.value = list",
        )
    }

    @Test fun bothLoadsOfTheList_passTheirOwnNumber() {
        val vm = source("ChatVM.kt")
        assertEquals(2, Regex("""val load = threadsLoadStarted\(\)""").findAll(vm).count())
        assertEquals(2, Regex("""\.onSuccess \{ showThreads\(load, from, it\) \}""").findAll(vm).count())
        assertEquals("nothing else puts a loaded list on screen", 1, Regex("""threads\.value = list""").findAll(vm).count())
    }
}
