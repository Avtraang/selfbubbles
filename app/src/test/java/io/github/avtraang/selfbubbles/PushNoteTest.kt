package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings > Push notifications line (Push.settingsNote, PushService.kt).
 * Two facts feed it: whether google-services.json was there at build time
 * (BuildConfig.PUSH_BUILT_IN) and whether Firebase actually started in this
 * process. A build without the file says so; a build with the file whose
 * Firebase did not start says that instead of blaming the file.
 */
class PushNoteTest {

    @Test fun builtInAndRunning_namesFirebaseAndTheFile() {
        val note = Push.settingsNote(builtIn = true, initialised = true)
        assertTrue(note, note.startsWith("Push notifications: Firebase"))
        assertTrue(note, "google-services.json" in note)
        assertTrue(note, "not built in" !in note)
        assertTrue(note, "not active" !in note)
    }

    @Test fun notBuiltIn_saysSo_andHowToGetIt() {
        val note = Push.settingsNote(builtIn = false, initialised = false)
        assertTrue(note, note.startsWith("Push: not built in"))
        assertTrue(note, "no google-services.json" in note)
        assertTrue(note, "WebSocket" in note)
        assertTrue(note, "rebuild" in note)
    }

    @Test fun builtInButNotRunning_saysNotActive_notThatTheFileIsMissing() {
        val note = Push.settingsNote(builtIn = true, initialised = false)
        assertTrue(note, note.startsWith("Push: not active"))
        assertTrue(note, "includes google-services.json" in note)
        assertTrue(note, "no google-services.json" !in note)
        assertTrue(note, "not built in" !in note)
        assertTrue(note, "WebSocket" in note)
        assertTrue(note, "rebuild" in note)
    }

    @Test fun notBuiltIn_ignoresTheRuntimeFlag() {
        // Without the plugin Firebase cannot have started; the note never claims it did.
        assertEquals(Push.settingsNote(builtIn = false, initialised = false), Push.settingsNote(builtIn = false, initialised = true))
    }

    @Test fun theThreeLinesDiffer_andAreStable() {
        val lines = listOf(
            Push.settingsNote(builtIn = true, initialised = true),
            Push.settingsNote(builtIn = true, initialised = false),
            Push.settingsNote(builtIn = false, initialised = false),
        )
        assertEquals(lines.size, lines.toSet().size)
        for ((b, i) in listOf(true to true, true to false, false to false)) {
            assertEquals(Push.settingsNote(b, i), Push.settingsNote(b, i))
        }
        assertNotEquals(lines[0], lines[1])
    }
}
