package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Voice Text launcher entry follows the Voice assistant switch through
 * PackageManager (VoiceComponent, VoiceActivity.kt): DEFAULT when on — not
 * ENABLED, so the manifest stays authoritative — and DISABLED when off, and
 * only a real change is written, so the owner's app never churns the component
 * on every launch. The decision only; PackageManager itself is not involved.
 */
class VoiceComponentTest {

    @Test fun on_wantsDefault_notEnabled() {
        assertEquals(VoiceComponent.State.DEFAULT, VoiceComponent.desired(featureOn = true))
    }

    @Test fun off_wantsDisabled() {
        assertEquals(VoiceComponent.State.DISABLED, VoiceComponent.desired(featureOn = false))
    }

    @Test fun alreadyThere_isANoOp() {
        assertNull(VoiceComponent.transition(VoiceComponent.State.DEFAULT, featureOn = true))
        assertNull(VoiceComponent.transition(VoiceComponent.State.DISABLED, featureOn = false))
    }

    @Test fun aFlip_writesTheNewState() {
        assertEquals(VoiceComponent.State.DISABLED, VoiceComponent.transition(VoiceComponent.State.DEFAULT, featureOn = false))
        assertEquals(VoiceComponent.State.DEFAULT, VoiceComponent.transition(VoiceComponent.State.DISABLED, featureOn = true))
    }

    @Test fun anyOtherPackageManagerState_isBroughtInLine() {
        // ENABLED, DISABLED_USER, DISABLED_UNTIL_USED: the caller maps them to null.
        assertEquals(VoiceComponent.State.DEFAULT, VoiceComponent.transition(null, featureOn = true))
        assertEquals(VoiceComponent.State.DISABLED, VoiceComponent.transition(null, featureOn = false))
    }
}
