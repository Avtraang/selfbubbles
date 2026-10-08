package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Tunnel-only means every upload passes Cloudflare's limits (about 100 MB per
 * request body, about 100 s of origin silence). A failed send must say which.
 */
class SendFailureTest {
    @Test fun statusCodes_areClassified() {
        assertEquals(SendFailure.TOO_LARGE, sendFailureFor(413))
        for (code in listOf(524, 522, 504, 408)) {
            assertEquals(SendFailure.TIMED_OUT, sendFailureFor(code))
        }
        for (code in listOf(400, 401, 403, 404, 500, 502, 503)) {
            assertEquals(SendFailure.OTHER, sendFailureFor(code))
        }
    }

    @Test fun throwables_areClassified() {
        assertEquals(SendFailure.TIMED_OUT, sendFailureFor(SocketTimeoutException("timeout")))
        assertEquals(
            SendFailure.TOO_LARGE,
            sendFailureFor(SendFailedException(SendFailure.TOO_LARGE, "x")),
        )
        assertEquals(SendFailure.OTHER, sendFailureFor(IOException("reset")))
        assertEquals(SendFailure.OTHER, sendFailureFor(null as Throwable?))
    }

    @Test fun sizeLimit_leavesRoomForMultipartFraming() {
        assertFalse(exceedsTunnelLimit(null))
        assertFalse(exceedsTunnelLimit(-1))
        assertFalse(exceedsTunnelLimit(0))
        assertFalse(exceedsTunnelLimit(50L * 1024 * 1024))
        assertTrue(exceedsTunnelLimit(TUNNEL_MAX_BODY_BYTES))
        assertTrue(exceedsTunnelLimit(TUNNEL_MAX_BODY_BYTES - 1))
        assertTrue(exceedsTunnelLimit(500L * 1024 * 1024))
    }

    @Test fun messages_distinguishTheCause() {
        val generic = sendFailureMessage(SendFailure.OTHER)
        assertEquals("Couldn't send attachment", generic)
        assertNotEquals(generic, sendFailureMessage(SendFailure.TOO_LARGE))
        assertNotEquals(generic, sendFailureMessage(SendFailure.TIMED_OUT))
        assertTrue(sendFailureMessage(SendFailure.TOO_LARGE).contains("100 MB"))
    }
}
