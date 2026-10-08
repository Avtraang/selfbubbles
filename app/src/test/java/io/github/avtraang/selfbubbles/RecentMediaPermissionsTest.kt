package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permissions the composer's recent-photos grid asks for, by Android
 * version (recentMediaPermissions, AttachmentPanel.kt): Android 13 and newer
 * are unchanged, and older versions ask for the one permission that exists there.
 */
class RecentMediaPermissionsTest {

    private val perType = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
    private val storage = listOf("android.permission.READ_EXTERNAL_STORAGE")

    @Test fun android13AndNewer_askForThePerTypePermissions_asBefore() {
        for (sdk in listOf(33, 34, 35, 36, 37)) assertEquals("API $sdk", perType, recentMediaPermissions(sdk))
    }

    @Test fun android12AndOlder_askForStorage() {
        for (sdk in listOf(28, 29, 30, 31, 32)) assertEquals("API $sdk", storage, recentMediaPermissions(sdk))
    }

    @Test fun theBoundary_isApi33_whereTheManifestCapEnds() {
        // AndroidManifest.xml declares READ_EXTERNAL_STORAGE with maxSdkVersion 32.
        assertEquals(storage, recentMediaPermissions(32))
        assertEquals(perType, recentMediaPermissions(33))
    }
}
