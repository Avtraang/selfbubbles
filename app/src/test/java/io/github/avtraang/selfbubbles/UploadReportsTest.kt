package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * An attachment whose upload failed, or whose upload the process did not live
 * to see the end of, used to leave a short toast at best (audit A3-F5): with
 * the screen off or the app closed mid-upload, nothing ever said that the file
 * had not gone out. These are the rules behind what is said now (Uploads.kt
 * is the Android side): a record on the phone before each upload starts, a
 * notification for a failure nobody is looking at, and one at the next start
 * for every upload the last process left in flight.
 */
class UploadReportsTest {
    private val chat = "iMessage;-;+15550100200"
    private val other = "iMessage;-;+15550100300"

    private fun upload(id: Long, guid: String = chat, title: String = "Alice Anders") = PendingUpload(id, guid, title, 1_000L + id)

    // ---- what is stored ----

    @Test fun whatIsStored_comesBackAsItWas() {
        val list = listOf(upload(1), upload(2, other, ""))
        assertEquals(list, uploadsDecode(uploadsEncode(list)))
    }

    @Test fun nothingStored_orSomethingElseStored_isNoUploads() {
        for (stored in listOf(null, "", "   ", "not json", "{}", "[1, 2]", "[{\"id\": \"x\"}]")) {
            assertEquals(stored, emptyList<PendingUpload>(), uploadsDecode(stored))
        }
    }

    @Test fun oneRecordThatCannotBeRead_costsThatOneOnly() {
        val stored = "[{\"id\":1,\"chatGuid\":\"$chat\"},{\"oops\":true},{\"id\":3,\"chatGuid\":\"\"},{\"id\":4,\"chatGuid\":\"$other\",\"chatTitle\":\"Bob\"}]"
        assertEquals(listOf(1L, 4L), uploadsDecode(stored).map { it.id })
    }

    @Test fun theRecordNamesTheChat_andNothingOfTheFile() {
        // The file is the owner's photo or document: neither its name nor its address is written down.
        val stored = uploadsEncode(listOf(upload(7)))
        assertEquals(setOf("id", "chatGuid", "chatTitle", "startedAtMillis"), Regex("\"([A-Za-z]+)\":").findAll(stored).map { it.groupValues[1] }.toSet())
    }

    // ---- uploads the last process left in flight ----

    @Test fun nothingLeftInFlight_saysNothing() {
        assertEquals(emptyList<UploadNotice>(), interruptedUploadNotices(emptyList()))
    }

    @Test fun anUploadLeftInFlight_isReportedForItsChat() {
        val notice = interruptedUploadNotices(listOf(upload(1))).single()
        assertEquals(chat, notice.chatGuid)
        assertEquals("Alice Anders", notice.title)
        assertEquals("An attachment may not have been sent — check the chat before sending it again", notice.text)
    }

    @Test fun severalLeftInFlight_areOneNoticePerChat_withTheirNumber() {
        val notices = interruptedUploadNotices(listOf(upload(1), upload(2, other, ""), upload(3), upload(4)))
        assertEquals(listOf(chat, other), notices.map { it.chatGuid })
        assertEquals("3 attachments may not have been sent — check the chat before sending them again", notices[0].text)
        assertEquals("Attachment may not have been sent", notices[1].title)       // a chat with no name: the title says what happened
        assertTrue(notices[1].text.startsWith("An attachment may not"))
    }

    // ---- a failure while the process is alive ----

    @Test fun aFailureInTheChatTheOwnerIsLookingAt_isAToast() {
        val alert = attachmentAlert(chat, "Alice Anders", ConnectException("refused"), appInFront = true, locked = false, openChat = chat)
        assertEquals(UnsentAlert.Toast("Couldn't send attachment"), alert)
    }

    @Test fun aFailureNobodyIsLookingAt_isANotification() {
        val error = SocketException("Connection reset")
        val expected = UnsentAlert.Notification("Alice Anders", ATTACHMENT_UNCONFIRMED_MESSAGE)
        // In the background, behind the lock screen, and in front but on another chat or screen: a toast
        // there is gone in seconds and nothing else would keep the failure.
        assertEquals(expected, attachmentAlert(chat, "Alice Anders", error, appInFront = false, locked = false, openChat = chat))
        assertEquals(expected, attachmentAlert(chat, "Alice Anders", error, appInFront = true, locked = true, openChat = chat))
        assertEquals(expected, attachmentAlert(chat, "Alice Anders", error, appInFront = true, locked = false, openChat = other))
        assertEquals(expected, attachmentAlert(chat, "Alice Anders", error, appInFront = true, locked = false, openChat = null))
    }

    @Test fun aNotificationForAChatWithNoName_saysWhatHappenedInItsTitle() {
        fun title(error: Throwable) =
            (attachmentAlert(chat, "", error, appInFront = false, locked = false, openChat = null) as UnsentAlert.Notification).title
        assertEquals("Attachment not sent", title(ConnectException("refused")))
        assertEquals("Attachment may not have been sent", title(SocketException("Connection reset")))
        assertEquals("Attachment may not have been sent", title(SocketTimeoutException("timeout")))
    }

    // ---- a timeout is not "not sent" (audit A3-F4) ----

    @Test fun anAttachmentThatTimedOut_mayStillBeSent_andSaysSo() {
        // The app, or the tunnel, stopped waiting; the relay goes on sending the file.
        for (error in listOf(SocketTimeoutException("timeout"), SendFailedException(SendFailure.TIMED_OUT, "send_attachment HTTP 524", 524))) {
            assertEquals(ATTACHMENT_TIMED_OUT_MESSAGE, attachmentFailureMessage(error))
        }
        assertTrue(ATTACHMENT_TIMED_OUT_MESSAGE.contains("may still be sent"))
        assertTrue(ATTACHMENT_TIMED_OUT_MESSAGE.contains("check the chat"))
    }
}
