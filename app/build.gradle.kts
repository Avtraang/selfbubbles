import com.android.build.api.variant.BuildConfigField
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.gms.google-services") apply false
}

// Firebase push is OPTIONAL. The google-services plugin turns the UNTRACKED
// app/google-services.json (your own Firebase project's client file) into the
// resources Firebase initialises from at process start, and it fails the build
// when that file is missing — so it is applied only when the file is there.
// Without it the firebase-messaging library still compiles and Firebase simply
// never initialises: Push (PushService.kt) sees that and skips registration,
// and new messages reach the app over its WebSocket only.
val googleServicesFile = project.file("google-services.json")
// The plugin also accepts the file under app/src/<buildType>/, app/src/<flavor>/
// and app/src/<flavor>/<buildType>/ (Firebase's per-variant layout), so a file
// there counts too; app/google-services.json stays the canonical place.
val googleServicesFiles = listOfNotNull(googleServicesFile.takeIf { it.isFile }) +
    fileTree("src") { include("*/google-services.json", "*/*/google-services.json") }.files.sorted()
val hasGoogleServices = googleServicesFiles.isNotEmpty()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn(
        "WARNING: google-services.json not found — building without Firebase push; the app will rely on its WebSocket only " +
            "(to enable push, put your Firebase project's client file at ${googleServicesFile.path} and rebuild; it is git-ignored)."
    )
}

// Secrets and relay addresses live in the UNTRACKED secrets.properties at the
// repo root (copy secrets.properties.example and fill it in). They are read
// here at configuration time and each key becomes a BuildConfig String field,
// so the Kotlin sources never contain the values themselves.
//
// The file and every key in it are OPTIONAL: a missing file or key becomes an
// empty BuildConfig string (with one warning per key below) and the app asks
// for the value in its Relay settings instead (RelayConfigStore). A file with
// all keys filled in — the owner's — bakes them in exactly as before.
val secretKeys = listOf(
    "IMSG_TOKEN",
    "CF_ACCESS_CLIENT_ID",
    "CF_ACCESS_CLIENT_SECRET",
    "RELAY_REMOTE_BASE",
    "RELAY_REMOTE_WS",
    "FAMILY_MAP_URL",
)

val secretsFile = rootProject.file("secrets.properties")

val secrets = Properties().also { props ->
    if (secretsFile.isFile) secretsFile.reader(Charsets.UTF_8).use { props.load(it) }
}

/** Each key's value, "" when the file or the key is absent. */
val secretValues: Map<String, String> = secretKeys.associateWith { secrets.getProperty(it)?.trim().orEmpty() }

if (!secretsFile.isFile) {
    logger.warn(
        "WARNING: ${secretsFile.path} not found — no relay settings are baked into this build " +
            "(copy ${rootProject.file("secrets.properties.example").path} to that path to bake yours in; " +
            "the file is git-ignored)."
    )
}
for ((key, value) in secretValues) {
    if (value.isEmpty()) {
        val fallback =
            if (key == "RELAY_REMOTE_WS" && secretValues.getValue("RELAY_REMOTE_BASE").isNotEmpty())
                "the app derives wss://<RELAY_REMOTE_BASE host>/ws from RELAY_REMOTE_BASE instead"
            else "the app takes the value from its Relay settings instead"
        logger.warn("WARNING: secrets.properties: $key is not set — BuildConfig.$key will be empty; $fallback.")
    }
}

// Optional feature switches (Features.kt). FEATURES is not in secretKeys: its
// absence means something different from its being empty, so it is read on its
// own and exposed as two fields — BuildConfig.FEATURES (the list as written, ""
// when absent) and BuildConfig.FEATURES_PRESENT (true when the key exists, even
// as a bare "FEATURES="). The rule, applied by BuildFeatureDefault (Features.kt):
//   present → authoritative: exactly the names listed start ON (an unknown name
//             is warned about here by name and ignored);
//   absent  → all four start ON when RELAY_REMOTE_BASE is set (the owner's
//             unchanged file), all OFF otherwise — and only that build takes
//             its first values from the relay's /health, once.
// Every switch can be changed later in the app's Settings > Features. Keep this
// name list in step with the Feature enum in Features.kt.
val featureNames = listOf("facetime", "map", "translate", "voice")
val featuresRaw: String? = secrets.getProperty("FEATURES")?.trim()
val featuresPresent = featuresRaw != null
if (featuresRaw == null) {
    val relayBuiltIn = secretValues.getValue("RELAY_REMOTE_BASE").isNotEmpty()
    val outcome =
        if (relayBuiltIn) "all four features (${featureNames.joinToString(", ")}) start ON because RELAY_REMOTE_BASE is set"
        else "all four features (${featureNames.joinToString(", ")}) start OFF because RELAY_REMOTE_BASE is empty too"
    logger.warn(
        "WARNING: secrets.properties: FEATURES is not set — $outcome; " +
            "a FEATURES=<comma-separated names> line (even empty) makes the list authoritative. " +
            "Each switch can be changed in the app's Settings > Features."
    )
} else {
    val unknown = featuresRaw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && it !in featureNames }
    if (unknown.isNotEmpty()) {
        logger.warn(
            "WARNING: secrets.properties: FEATURES names unknown features (${unknown.joinToString(", ")}) — ignored; " +
                "the known names are ${featureNames.joinToString(", ")}."
        )
    }
}

// Optional launcher label. APP_LABEL is not in secretKeys either: an absent key
// is the documented default ("SelfBubbles"), not something to warn about, and
// the value becomes the string resource R.string.app_name (the launcher label,
// the chat-list title and the "Unlock …" prompt) rather than a BuildConfig
// field. A label may be any text, so the ASCII/URL guards below do not apply
// to it; only an empty value is rejected (default), a line break or other
// control character becomes a space (a label is one line), and an over-long
// one is capped so it still fits under a launcher icon.
val appLabelDefault = "SelfBubbles"
val appLabelMaxChars = 30

/**
 * The first [max] user-perceived characters of [text] (grapheme clusters, so an accented
 * letter, a flag or a ZWJ emoji is never cut in half), or all of [text] when it is shorter.
 */
fun firstGraphemes(text: String, max: Int): String {
    val boundaries = BreakIterator.getCharacterInstance().also { it.setText(text) }
    var end = 0
    repeat(max) {
        val next = boundaries.next()
        if (next == BreakIterator.DONE) return text
        end = next
    }
    return text.substring(0, end)
}

val appLabel: String = run {
    val raw: String? = secrets.getProperty("APP_LABEL")
    // Properties.load decodes \n, \t and \uXXXX escapes, so the value can carry
    // control characters, which would end up verbatim in the (quoted) resource.
    val oneLine = raw.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("")
    if (oneLine != raw.orEmpty()) {
        logger.warn(
            "WARNING: secrets.properties: APP_LABEL contains a line break or another control character — " +
                "each is replaced with a space (a label is one line)."
        )
    }
    val label = oneLine.trim()
    val capped = firstGraphemes(label, appLabelMaxChars)
    when {
        raw == null -> {
            logger.lifecycle("APP_LABEL not set — the app is labelled \"$appLabelDefault\"")
            appLabelDefault
        }
        label.isEmpty() -> {
            logger.warn("WARNING: secrets.properties: APP_LABEL is empty — ignored; the app is labelled \"$appLabelDefault\".")
            appLabelDefault
        }
        capped.length < label.length -> {
            logger.warn(
                "WARNING: secrets.properties: APP_LABEL is longer than $appLabelMaxChars characters — " +
                    "only its first $appLabelMaxChars are used as the label."
            )
            capped.trimEnd()
        }
        else -> label
    }
}

// Optional application id: the id the app is installed under (what `adb`, the
// launcher, a FileProvider authority and a Firebase client file know it by).
// APPLICATION_ID is not in secretKeys either: an absent key is the documented
// default, not something to warn about, and the value becomes
// defaultConfig.applicationId rather than a BuildConfig field. It is separate
// from the Kotlin package and `namespace` (io.github.avtraang.selfbubbles),
// which are the same in every build. Set it to keep installing over an app that
// is already on a phone under another id (Android treats a different id as a
// different app, with its own data, settings and notification channels), or to
// match the package name your own Firebase client file was created for. A
// present value must be a valid Android application id, or the build fails
// naming the key; an empty value (APPLICATION_ID=) is present and not valid,
// so it fails too rather than silently installing a second app.
val applicationIdDefault = "io.github.avtraang.selfbubbles"

/** At least two dot-separated segments, each starting with a letter, only letters, digits and _. */
val applicationIdPattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

val resolvedApplicationId: String = run {
    val raw: String? = secrets.getProperty("APPLICATION_ID")?.trim()
    when {
        raw == null -> {
            logger.lifecycle("APPLICATION_ID not set — the app installs as \"$applicationIdDefault\"")
            applicationIdDefault
        }
        !applicationIdPattern.matches(raw) -> throw GradleException(
            "secrets.properties: APPLICATION_ID must be an Android application id: at least two segments separated by dots, " +
                "each starting with a letter and made of letters, digits and underscores only (for example com.example.messages). " +
                "Fix the value, or remove the line to install as \"$applicationIdDefault\"."
        )
        else -> raw
    }
}

// Safety net against installing as a second, empty app beside the one already on
// a phone: a Firebase client file is keyed on the application id, so when a
// google-services.json is present (the hasGoogleServices check at the top) and
// none of the files found has a client for the id resolved above, the id is
// wrong for this checkout — typically APPLICATION_ID is missing from
// secrets.properties on a machine whose client file was created for another id.
// Fail here, at configuration time and with the remedy. (With per-variant files
// the plugin's own "No matching client found for package name" still covers a
// variant whose file lacks the id.) The file is read as text and only searched;
// nothing from it is printed. The search is for the entry the plugin itself
// matches a client by — client_info.android_client_info.package_name — so the
// same key under an oauth_client's android_info does not count as a client.
if (hasGoogleServices) {
    val clientEntry = Regex(
        "\"android_client_info\"\\s*:\\s*\\{[^{}]*?\"package_name\"\\s*:\\s*\"" +
            Regex.escape(resolvedApplicationId) + "\""
    )
    if (googleServicesFiles.none { clientEntry.containsMatchIn(it.readText(Charsets.UTF_8)) }) {
        throw GradleException(
            "google-services.json has no client for application id $resolvedApplicationId — " +
                "set APPLICATION_ID in secrets.properties to the id that file was created for, " +
                "or use your own Firebase client file"
        )
    }
}

// The app is tunnel-only: the relay token and the Cloudflare Access credentials
// are sent to exactly one origin, so that origin must be TLS and the WebSocket
// must live on it. Fail the build here rather than ship an app that would talk
// cleartext or send credentials to a second host. Runs only on the values that
// are present (an absent URL is validated by RelayConfigValidator when the
// owner types one; keep the two rule sets in step). Messages name the key only,
// never the value.
run {
    fun relayUri(key: String, scheme: String): URI? {
        val raw = secretValues.getValue(key)
        if (raw.isEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || !scheme.equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrEmpty()) {
            throw GradleException(
                "secrets.properties: $key must be a $scheme:// URL with a host " +
                    "(the app only ever uses the HTTPS tunnel; cleartext is not allowed)."
            )
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null) {
            throw GradleException("secrets.properties: $key must not contain user info or a #fragment.")
        }
        if (uri.rawQuery != null) {
            throw GradleException(
                "secrets.properties: $key must not carry a query string " +
                    "(credentials travel in headers, never in a URL)."
            )
        }
        return uri
    }
    // The token and the Cloudflare Access pair travel as HTTP headers, and OkHttp
    // refuses a header value outside printable ASCII with an exception that
    // quotes the value (RelayConfigValidator.isHeaderSafe is the same rule).
    for (key in listOf("IMSG_TOKEN", "CF_ACCESS_CLIENT_ID", "CF_ACCESS_CLIENT_SECRET")) {
        if (secretValues.getValue(key).any { it !in ' '..'~' }) {
            throw GradleException(
                "secrets.properties: $key must be plain ASCII text (no accents, emoji or invisible characters; " +
                    "it is sent as an HTTP header)."
            )
        }
    }
    val relayBase = relayUri("RELAY_REMOTE_BASE", "https")
    val relayWs = relayUri("RELAY_REMOTE_WS", "wss")
    fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    if (relayBase != null && relayWs != null &&
        (!relayBase.host.equals(relayWs.host, ignoreCase = true) || port(relayBase) != port(relayWs))
    ) {
        throw GradleException(
            "secrets.properties: RELAY_REMOTE_BASE and RELAY_REMOTE_WS must point at the same host and port " +
                "(credentials are only sent to the RELAY_REMOTE_BASE origin)."
        )
    }
}

/** Render [value] as a Java string literal (BuildConfig is generated Java source). */
fun javaStringLiteral(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

/**
 * Render [value] as the text of an Android `<string>` resource, quoted so aapt2 keeps it
 * verbatim: inside "…" an apostrophe, surrounding spaces and a leading @ or ? are literal
 * and only \ and " need escaping. AGP XML-escapes the text (&, <) itself when it writes
 * gradleResValues.xml.
 */
fun androidStringResource(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(ch)
        }
    }
    append('"')
}

// The build stamp: which sources a build was made from, shown on the last line of
// the app's Settings ("Version 1.0 · build 0003b41+3fa9c1 · 2026-10-07";
// versionLine, BuildStamp.kt). Every build has the same versionName, so without
// it nobody can tell which build is on a phone. Two BuildConfig fields:
//   BUILD_COMMIT  the short commit of HEAD. When the working tree is not that
//                 commit's, a "+" and six hex digits follow: the start of a
//                 SHA-256 over what differs (below). Builds go to a phone from
//                 an uncommitted tree as a matter of course, and a bare "+"
//                 would read the same for every one of them between two commits.
//   BUILD_DATE    the commit's date, yyyy-MM-dd, as the commit records it.
// Both are "unknown" when the project root is not itself the top of a git
// checkout (a source tarball; or a copy inside some other repository, whose
// commit would be the wrong one) or when git cannot be run. Never the wall
// clock: two builds of the same sources are the same build, and a time of day in
// BuildConfig would make each differ from the last and recompile for nothing.
// The digest moves only when a file's content does.
//
// What counts as differing from the commit: a tracked file that is not as HEAD
// has it (staged or not), and a file git does not track and does not ignore,
// which is what a source file is until its first commit. The digest is over the
// patch of the first (`git diff HEAD`) and the path and content of each of the
// second. Ignored files are never listed and never read, and .gitignore names
// every place a secret lives in this project (secrets.properties, the Firebase
// client file, *.bak, built packages); a change to one of those is therefore
// not in the stamp either.
//
// A ValueSource wired into BuildConfig lazily (androidComponents, below): git is
// asked only when a BuildConfig is about to be generated, so a new commit or an
// edit regenerates that one file and leaves the configuration cache valid. It is
// not providers.exec because of the machine without git: isIgnoreExitValue
// covers a git that fails, but one that cannot be started throws, and with the
// configuration cache on that fails the build even when the script catches it
// (tried on Gradle 9.4.1). Here that case is one more "unknown".
//
// The class touches nothing else in this script (Gradle instantiates it on its
// own, and stores it with the task graph), and git's output is used only where
// it has the expected shape, so nothing but hex digits, a "+" and a date can
// reach the app.
abstract class GitBuildStamp : ValueSource<String, GitBuildStamp.Params> {
    interface Params : ValueSourceParameters {
        /** The project root. */
        val root: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    /** Runs `git <args>` in the root with its output going to [out]; false when git cannot be started or exits non-zero. */
    private fun git(out: OutputStream, vararg args: String): Boolean = try {
        exec.exec {
            commandLine(listOf("git") + args)
            workingDir = parameters.root.get().asFile
            // Read-only: without this `git status` and `git diff` may rewrite .git/index to refresh its cache.
            environment("GIT_OPTIONAL_LOCKS", "0")
            standardOutput = out
            errorOutput = ByteArrayOutputStream()   // "fatal: not a git repository" is an answer, not build output
            isIgnoreExitValue = true
        }.exitValue == 0
    } catch (e: Exception) {
        false
    }

    /** What `git <args>` prints; null when it could not say. */
    private fun git(vararg args: String): String? {
        val out = ByteArrayOutputStream()
        return if (git(out, *args)) String(out.toByteArray(), Charsets.UTF_8) else null
    }

    /**
     * What follows the commit: nothing when the working tree is that commit's,
     * "+" and six hex digits of a SHA-256 over what differs when it is not, and
     * a bare "+" when that could not be worked out (never nothing: a build
     * that may differ from its commit does not get to look like the commit).
     */
    private fun uncommitted(): String = try {
        val root = parameters.root.get().asFile.toPath()
        val sha = MessageDigest.getInstance("SHA-256")
        // Tracked files, staged or not, as one patch. Straight into the digest: a patch can be large.
        var patched = 0L
        val intoDigest = object : OutputStream() {
            override fun write(b: Int) { sha.update(b.toByte()); patched++ }
            override fun write(b: ByteArray, off: Int, len: Int) { sha.update(b, off, len); patched += len }
        }
        // Untracked and not ignored: git lists them (NUL-separated, so no name needs quoting) and never opens an ignored one.
        val others = git("ls-files", "--others", "--exclude-standard", "-z")
        if (!git(intoDigest, "diff", "--binary", "--no-ext-diff", "--no-textconv", "--no-color", "HEAD", "--") || others == null) {
            "+"
        } else {
            val paths = others.split('\u0000').filter { it.isNotEmpty() }.sorted()
            if (patched == 0L && paths.isEmpty()) {
                ""
            } else {
                val buffer = ByteArray(1 shl 16)
                for (path in paths) {
                    sha.update(path.toByteArray(Charsets.UTF_8))
                    sha.update(0.toByte())
                    val file = root.resolve(path)
                    when {
                        // Where a link points, not what is there: it may point out of the project.
                        Files.isSymbolicLink(file) -> sha.update(Files.readSymbolicLink(file).toString().toByteArray(Charsets.UTF_8))
                        Files.isRegularFile(file) -> Files.newInputStream(file).use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                sha.update(buffer, 0, n)
                            }
                        }
                        // Anything else (a checkout inside the checkout is listed as a directory) counts by its name.
                    }
                    sha.update(0.toByte())
                }
                "+" + sha.digest().take(3).joinToString("") { "%02x".format(it) }
            }
        }
    } catch (e: Exception) {
        "+"
    }

    /** "<commit>|<date>", each part "unknown" when git could not say. */
    override fun obtain(): String {
        val unknown = "unknown|unknown"
        if (!File(parameters.root.get().asFile, ".git").exists()) return unknown
        // The last line: a `log.showSignature` in the builder's git config prints the signature check first.
        val head = git("log", "-1", "--abbrev=7", "--date=short", "--format=%h %cd")
            ?.lines()?.lastOrNull { it.isNotBlank() }?.trim() ?: return unknown
        val match = Regex("([0-9a-f]{7,40}) (\\d{4}-\\d{2}-\\d{2})").matchEntire(head) ?: return unknown
        return match.groupValues[1] + uncommitted() + "|" + match.groupValues[2]
    }
}

val buildStamp: Provider<String> = providers.of(GitBuildStamp::class) {
    parameters { root.set(rootProject.layout.projectDirectory) }
}

androidComponents {
    onVariants { variant ->
        // buildConfigFields is null only with the BuildConfig feature off, which this app cannot be built with.
        val fields = variant.buildConfigFields ?: return@onVariants
        // The two lambdas use nothing of this script (they are stored with the task graph),
        // and the values need no escaping: obtain() above lets through [0-9a-f+-] and "unknown".
        fields.put(
            "BUILD_COMMIT",
            buildStamp.map { BuildConfigField("String", "\"" + it.substringBefore('|') + "\"", "short git commit; +xxxxxx = a digest of what was not committed") },
        )
        fields.put(
            "BUILD_DATE",
            buildStamp.map { BuildConfigField("String", "\"" + it.substringAfter('|') + "\"", "date of that commit, yyyy-MM-dd") },
        )
    }
}

android {
    namespace = "io.github.avtraang.selfbubbles"
    compileSdk = 37

    defaultConfig {
        // APPLICATION_ID from secrets.properties, or its default (see above); the namespace never changes.
        applicationId = resolvedApplicationId
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // One BuildConfig.<KEY> per entry in secretKeys (see top of file); "" when unset.
        for ((key, value) in secretValues) {
            buildConfigField("String", key, javaStringLiteral(value))
        }
        // The feature list and whether the key existed at all (see the FEATURES comment above).
        buildConfigField("String", "FEATURES", javaStringLiteral(featuresRaw.orEmpty()))
        buildConfigField("boolean", "FEATURES_PRESENT", featuresPresent.toString())
        // Whether the google-services plugin ran, i.e. Firebase push is built in. Push
        // (PushService.kt) shows it in Settings; FirebaseApp.getApps() is the runtime check.
        buildConfigField("boolean", "PUSH_BUILT_IN", hasGoogleServices.toString())
        // The launcher label and every in-app R.string.app_name: APP_LABEL or its default (see above).
        resValue("string", "app_name", androidStringResource(appLabel))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true   // app_name above; AGP leaves this feature off by default
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // added for the relay client
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Images and animated GIFs in chats (Coil).
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // Material icons (the core set only; ui/theme/Icons.kt maps the rest).
    implementation("androidx.compose.material:material-icons-core")

    // App lock (AppLock.kt / SettingsScreen.kt): fingerprint, face or device PIN
    // before the message history shows. BiometricPrompt needs a FragmentActivity,
    // which this library brings in transitively (androidx.fragment).
    implementation("androidx.biometric:biometric:1.1.0")

    // Built-in video player (VideoPlayer.kt). The OkHttp data source streams through
    // the app's shared `http` client, so the relay credentials are attached and
    // stripped by the same interceptors as every other request.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")

    // Firebase push (PushService.kt). Compiles in every build; it only ever
    // initialises when google-services.json was present (see the top of this file).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
