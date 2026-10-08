package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * What the app writes to logcat about the WebSocket and about failures
 * (wsFrameLogLine, wsFailureLogLine, failureLabel in Imsg.kt): the kind of
 * thing and its size, never message text, a chat identifier, a host or a URL.
 */
class LogLinesTest {

    private val secretText = "see you at 6, bring the keys"
    private val chatGuid = "iMessage;-;+15555550123"
    private val frame =
        """{"type":"message","data":{"rowid":41,"guid":"g-41","text":"$secretText","sender":"Sam Example","chat_guid":"$chatGuid"}}"""

    @Test fun aFailedSendLine_isWhatKind_theClassNames_andTheStatus_only() {
        val refused = SendFailedException(SendFailure.OTHER, "send HTTP 502 for $chatGuid", 502)
        assertEquals("text send failed (SendFailedException) http=502", sendFailureLogLine("text send", refused))
        assertEquals(
            "attachment send failed (UnknownHostException)",
            sendFailureLogLine("attachment send", UnknownHostException("relay.example.test")),
        )
        val tooLarge = SendFailedException(SendFailure.TOO_LARGE, "attachment is 500 bytes")
        assertEquals("attachment send failed (SendFailedException)", sendFailureLogLine("attachment send", tooLarge))
        for (line in listOf(sendFailureLogLine("text send", refused), sendFailureLogLine("create chat", IOException(secretText)))) {
            assertFalse(line.contains(secretText))
            assertFalse(line.contains("5555550123"))
            assertFalse(line.contains("relay.example"))
        }
    }

    @Test fun aFailedReactionLine_isTheClassNames_only() {
        // ChatVM.react: the request of a tapback that failed is caught and logged, with nothing of the chat or the message.
        val line = sendFailureLogLine("reaction", java.net.SocketTimeoutException("timeout for $chatGuid"))
        assertEquals("reaction failed (SocketTimeoutException)", line)
        assertFalse(line.contains("5555550123"))
    }

    @Test fun aFrameLine_isItsTypeAndLength_only() {
        val line = wsFrameLogLine("message", frame.length)
        assertEquals("frame type=message len=${frame.length}", line)
        assertFalse(line.contains(secretText))
        assertFalse(line.contains("5555550123"))
        assertFalse(line.contains("Sam"))
    }

    @Test fun knownEnvelopeTypes_areNamed() {
        assertEquals("frame type=update len=10", wsFrameLogLine("update", 10))
        assertEquals("frame type=facetime len=0", wsFrameLogLine("facetime", 0))
        assertEquals("frame type=read_receipt len=7", wsFrameLogLine("read_receipt", 7))
    }

    @Test fun aFrameThatDidNotParse_isUnparsed() {
        assertEquals("frame type=unparsed len=${secretText.length}", wsFrameLogLine(null, secretText.length))
    }

    @Test fun theTypeOfAFrameTheAppDoesNotModel_isStillNamed() {
        // A FaceTime event's data is not a message, so the message envelope does not decode it.
        val ring = """{"type":"facetime","data":{"event":"incoming","uuid":"0000-1111","caller":"+15555550123"}}"""
        assertEquals("facetime", wsFrameType(ring))
        assertEquals("message", wsFrameType(frame))
        assertEquals("frame type=facetime len=${ring.length}", wsFrameLogLine(wsFrameType(ring), ring.length))
    }

    @Test fun aFrameWithoutAStringType_hasNone() {
        for (text in listOf("", "not json", "[1,2]", "{}", """{"type":7}""", """{"type":null}""", """{"type":{"a":1}}""", secretText)) {
            assertEquals(text, null, wsFrameType(text))
        }
        // Free text in the type field is read here but never reaches the log line.
        val odd = """{"type":"$secretText"}"""
        assertEquals("frame type=other len=${odd.length}", wsFrameLogLine(wsFrameType(odd), odd.length))
    }

    @Test fun aTypeThatIsNotAPlainIdentifier_isNeverRepeated() {
        for (type in listOf(secretText, chatGuid, "", "Message", "a b", "x".repeat(25), "type\nframe: injected", "+15555550123")) {
            assertEquals("frame type=other len=3", wsFrameLogLine(type, 3))
        }
    }

    @Test fun aFailureLabel_namesClasses_neverTheirText() {
        val t = IOException("cannot open content://media/external/file/12?name=$chatGuid", IllegalStateException(secretText))
        val label = failureLabel(t)
        assertEquals("IOException <- IllegalStateException", label)
        assertFalse(label.contains("content://"))
        assertFalse(label.contains("5555550123"))
        assertFalse(label.contains(secretText))
    }

    @Test fun aFailureLabel_isBounded_andSurvivesACauseLoop() {
        val deep = generateSequence(RuntimeException("level 0") as Throwable) { RuntimeException("deeper", it) }.elementAt(9)
        assertEquals(List(4) { "RuntimeException" }.joinToString(" <- "), failureLabel(deep))
        assertEquals("UnknownHostException", failureLabel(UnknownHostException("relay.example.test")))
        // A throwable that names itself as its cause is walked once.
        class SelfCaused : Exception("loop") { override val cause: Throwable get() = this }
        assertFalse(failureLabel(SelfCaused()).contains(" <- "))
    }

    @Test fun aSocketFailureLine_isClassAndStatus_withoutTheHost() {
        val line = wsFailureLogLine(UnknownHostException("Unable to resolve host \"relay.example.test\""), null)
        assertEquals("failure: UnknownHostException", line)
        assertFalse(line.contains("example"))
        assertEquals(
            "failure: ProtocolException http=403",
            wsFailureLogLine(java.net.ProtocolException("Expected HTTP 101 response but was '403 Forbidden'"), 403),
        )
        assertEquals("failure: SSLHandshakeException", wsFailureLogLine(SSLHandshakeException("chain for relay.example.test"), null))
    }
}
