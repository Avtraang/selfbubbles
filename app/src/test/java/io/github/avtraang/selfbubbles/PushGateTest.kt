package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PushService's routing of a push by its `kind` under the FaceTime switch
 * (PushGate, PushService.kt): a FaceTime ring is dropped — no notification —
 * while the feature is off, an ended call still cancels a ring posted before
 * the flip (cancel only, nothing else), and message pushes are never affected.
 */
class PushGateTest {

    private fun route(kind: String?, ftEvent: String? = null, on: Boolean) = PushGate.route(kind, ftEvent, on)

    @Test fun facetime_withTheFeatureOn_isHandled_whateverTheEvent() {
        assertEquals(PushGate.Route.FACETIME, route("facetime", "incoming", on = true))
        assertEquals(PushGate.Route.FACETIME, route("facetime", "ended", on = true))
        assertEquals(PushGate.Route.FACETIME, route("facetime", null, on = true))
    }

    @Test fun incomingRing_withTheFeatureOff_isDropped() {
        assertEquals(PushGate.Route.DROPPED, route("facetime", "incoming", on = false))
        assertEquals(PushGate.Route.DROPPED, route("facetime", null, on = false))
        assertEquals(PushGate.Route.DROPPED, route("facetime", "", on = false))
        assertEquals(PushGate.Route.DROPPED, route("facetime", "Ended", on = false))   // the relay's spelling only
    }

    @Test fun endedCall_withTheFeatureOff_onlyCancelsTheRing() {
        assertEquals(PushGate.Route.FACETIME_CANCEL, route("facetime", "ended", on = false))
    }

    @Test fun messages_areRoutedWhateverTheSwitch() {
        assertEquals(PushGate.Route.MESSAGE, route(null, on = false))
        assertEquals(PushGate.Route.MESSAGE, route(null, on = true))
        assertEquals(PushGate.Route.MESSAGE, route("", on = false))
        assertEquals(PushGate.Route.MESSAGE, route("message", on = false))
        assertEquals(PushGate.Route.MESSAGE, route("message", "ended", on = false))   // ft_event means nothing off a FaceTime push
    }

    @Test fun aScheduledTextsPush_hasItsOwnRoute_whateverTheFaceTimeSwitch() {
        // The relay's word that a scheduled text was sent or failed (SendLater.kt): never a message notification.
        assertEquals(PushGate.Route.SCHEDULED, route("scheduled", on = false))
        assertEquals(PushGate.Route.SCHEDULED, route("scheduled", on = true))
        assertEquals(PushGate.Route.SCHEDULED, route("scheduled", "ended", on = false))   // ft_event means nothing here either
        assertEquals(PushGate.Route.MESSAGE, route("Scheduled", on = true))               // the relay's spelling only
    }

    @Test fun kindIsMatchedExactly_asTheRelaySendsIt() {
        // Today's check is `kind == "facetime"`; a differently cased value never was a FaceTime push.
        assertEquals(PushGate.Route.MESSAGE, route("FaceTime", on = false))
        assertEquals(PushGate.Route.MESSAGE, route("facetime ", on = true))
    }
}
