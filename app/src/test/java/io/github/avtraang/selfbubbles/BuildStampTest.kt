package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last line of Settings (versionLine, BuildStamp.kt): which build this is.
 * Every build has the same versionName, so the commit the build was made from
 * (BuildConfig.BUILD_COMMIT and BUILD_DATE, stamped by app/build.gradle.kts),
 * with a digest of whatever was not committed, is what tells two builds apart.
 */
class BuildStampTest {

    @Test fun theLine_namesTheVersion_theCommit_andItsDate() {
        assertEquals("Version 1.0 · build abc1234 · 2026-01-02", versionLine("1.0", "abc1234", "2026-01-02"))
        // Built from sources that were not all committed: the commit alone does not say what is in the
        // build, so a digest of what differed follows it, and two such builds do not read the same.
        assertEquals("Version 1.0 · build abc1234+3fa9c1 · 2026-01-02", versionLine("1.0", "abc1234+3fa9c1", "2026-01-02"))
        assertEquals("Version 1.0 · build abc1234+0b77de · 2026-01-02", versionLine("1.0", "abc1234+0b77de", "2026-01-02"))
        // The build could not work the digest out: it still does not pass for the commit.
        assertEquals("Version 1.0 · build abc1234+ · 2026-01-02", versionLine("1.0", "abc1234+", "2026-01-02"))
        assertEquals("Version 2.3.1 · build 0123456789ab · 2027-12-31", versionLine("2.3.1", "0123456789ab", "2027-12-31"))
    }

    @Test fun aBuildMadeOutsideACheckout_saysJustTheVersion() {
        assertEquals("unknown", BUILD_STAMP_UNKNOWN)
        assertEquals("Version 1.0", versionLine("1.0", "unknown", "unknown"))
        assertEquals("Version 1.0", versionLine("1.0", "", ""))
        assertEquals("Version 1.0", versionLine("1.0", "  ", " unknown "))
    }

    @Test fun aPartTheBuildDidNotKnow_isLeftOut_notPrintedAsUnknown() {
        assertEquals("Version 1.0 · build abc1234", versionLine("1.0", "abc1234", "unknown"))
        assertEquals("Version 1.0 · 2026-01-02", versionLine("1.0", "unknown", "2026-01-02"))
        for (commit in listOf("abc1234", "abc1234+3fa9c1", "abc1234+", "unknown", "")) for (date in listOf("2026-01-02", "unknown", "")) {
            val line = versionLine("1.0", commit, date)
            assertFalse(line, "unknown" in line)
            assertFalse(line, line.endsWith(" ") || line.endsWith("·"))
            assertTrue(line, line.startsWith("Version 1.0"))
        }
    }

    @Test fun thisBuildsOwnStamp_hasTheShapeTheBuildScriptPromises() {
        // Whatever git said when these tests were built: a short hex id, then for uncommitted sources a "+"
        // and six hex digits (a bare "+" when the digest could not be worked out), and a date; or "unknown".
        val commit = BuildConfig.BUILD_COMMIT
        val date = BuildConfig.BUILD_DATE
        assertTrue(commit, Regex("unknown|[0-9a-f]{7,40}(\\+([0-9a-f]{6})?)?").matches(commit))
        assertTrue(date, Regex("unknown|\\d{4}-\\d{2}-\\d{2}").matches(date))
        // One git answer feeds both, so they are known together or not at all.
        assertEquals(commit == BUILD_STAMP_UNKNOWN, date == BUILD_STAMP_UNKNOWN)
        // And so the line can only ever hold the version, a hex id and a date: no address, no name, no path.
        val line = versionLine(BuildConfig.VERSION_NAME, commit, date)
        assertTrue(line, Regex("Version [0-9A-Za-z.+-]+( · build [0-9a-f]{7,40}(\\+([0-9a-f]{6})?)? · \\d{4}-\\d{2}-\\d{2})?").matches(line))
    }
}
