package io.github.avtraang.selfbubbles

// Which build this is, as the last line of Settings says it (and of the first-run relay
// screen). Every build has the same versionName, so the line also carries what
// app/build.gradle.kts stamps into BuildConfig from git when the app is built:
// BUILD_COMMIT, the short commit of HEAD, followed by "+" and six hex digits when the
// build was made from sources that were not all committed (the digits are a digest of
// what differed, so two such builds read differently unless they are the same sources),
// and BUILD_DATE, that commit's date, yyyy-MM-dd. Each is "unknown" in a build made
// outside a git checkout or on a machine without git.
// Plain Kotlin (no Android, no Compose), so BuildStampTest pins the line.

/** What the build writes into a stamp field git could not fill. */
const val BUILD_STAMP_UNKNOWN = "unknown"

/** [value] when the build knew it; null for "unknown" and for nothing at all. */
private fun stamped(value: String): String? = value.trim().takeUnless { it.isEmpty() || it == BUILD_STAMP_UNKNOWN }

/**
 * The line: "Version 1.0 · build 0003b41 · 2026-10-07", or with uncommitted
 * sources "Version 1.0 · build 0003b41+3fa9c1 · 2026-10-07". A part the build
 * did not know is left out, so a build made outside a checkout says just
 * "Version 1.0". Nothing else ever goes into it: no address, no name, no path.
 */
fun versionLine(versionName: String, buildCommit: String, buildDate: String): String = listOfNotNull(
    "Version $versionName",
    stamped(buildCommit)?.let { "build $it" },
    stamped(buildDate),
).joinToString(" · ")
