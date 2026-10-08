package io.github.avtraang.selfbubbles

// Pure helpers for the inline voice-message player (AudioBubble.kt). No Android
// or media3 types in this file, so everything here is covered by JVM unit tests
// (AudioSupportTest).

private const val AUDIO_MIME_PREFIX = "audio/"

/** How often the player's position is read while it plays (about 4 Hz). */
const val AUDIO_POLL_MS: Long = 250L

/** What the bubble shows for a voice message, whatever the file is called. */
const val AUDIO_VOICE_LABEL = "Voice message"

/** What the bubble shows for an audio attachment without a usable name. */
const val AUDIO_FALLBACK_LABEL = "Audio"

/** The clock before the length is known, and for anything the player cannot time. */
const val AUDIO_CLOCK_UNKNOWN = "–:––"

const val AUDIO_ERROR_MESSAGE = "Can't play this voice message"

/** A recording the relay no longer has reads like every other missing attachment. */
const val AUDIO_EXPIRED_MESSAGE = ATTACHMENT_MISSING_MESSAGE

/** The relay answers this when the recording is no longer on the Mac (expired, or kept only in iCloud). */
const val AUDIO_EXPIRED_STATUS = ATTACHMENT_MISSING_STATUS

/** iPhone voice messages arrive under this name (plus an extension), in any case. */
private const val VOICE_MESSAGE_STEM = "audio message"

/** File name extensions the bubble treats as audio when the type does not say. */
private val AUDIO_EXTENSIONS: List<String> = listOf(
    ".m4a", ".mp3", ".caf", ".ogg", ".oga", ".opus", ".amr", ".aac", ".wav", ".flac",
)

/** The mime type with parameters and whitespace removed, lower-cased; null when there is none. */
private fun baseMime(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** The extension of [name] that is in [AUDIO_EXTENSIONS], or null. */
private fun audioExtension(name: String?): String? {
    val n = name?.trim()?.lowercase() ?: return null
    return AUDIO_EXTENSIONS.firstOrNull { n.endsWith(it) && n.length > it.length }
}

/**
 * An attachment is audio when its mime type starts with audio/ (any case,
 * parameters ignored) or its name ends with a known audio extension (any case).
 */
fun isAudioAttachment(mime: String?, name: String?): Boolean {
    val type = baseMime(mime)
    if (type != null && type.startsWith(AUDIO_MIME_PREFIX)) return true
    return audioExtension(name) != null
}

/**
 * A playback clock: "m:ss" (0:07, 12:34), "h:mm:ss" from an hour on. Anything
 * negative is unknown (media3 reports TIME_UNSET before the stream has been
 * read) and shows as [AUDIO_CLOCK_UNKNOWN].
 */
fun formatClock(ms: Long): String {
    if (ms < 0L) return AUDIO_CLOCK_UNKNOWN
    val totalSeconds = ms / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Where the slider sits: [positionMs] as a share of [durationMs], clamped to
 * 0..1. An unknown duration (zero or negative) puts it at the start.
 */
fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val p = positionMs.coerceIn(0L, durationMs)
    return (p.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** [name] without its final extension, trimmed; the whole name when there is no extension. */
private fun stem(name: String): String {
    val t = name.trim()
    val dot = t.lastIndexOf('.')
    return if (dot > 0) t.substring(0, dot).trim() else t
}

/**
 * The line above the slider: [AUDIO_VOICE_LABEL] for an iPhone voice message
 * ("Audio Message.m4a", "audio message.caf", any case), otherwise the file name
 * without its extension, or [AUDIO_FALLBACK_LABEL] when nothing usable is left.
 */
fun audioLabel(name: String?): String {
    val t = name?.trim() ?: return AUDIO_FALLBACK_LABEL
    if (t.isEmpty()) return AUDIO_FALLBACK_LABEL
    if (t.lowercase().startsWith(VOICE_MESSAGE_STEM)) return AUDIO_VOICE_LABEL
    val s = stem(t)
    if (s.lowercase().startsWith(VOICE_MESSAGE_STEM)) return AUDIO_VOICE_LABEL
    return s.ifEmpty { AUDIO_FALLBACK_LABEL }
}

/** True when the relay's answer means the recording is gone for good. */
fun isExpiredStatus(httpStatus: Int?): Boolean = httpStatus == AUDIO_EXPIRED_STATUS

/** The error line under the clock: [AUDIO_EXPIRED_MESSAGE] for a 404, else [AUDIO_ERROR_MESSAGE]. */
fun audioErrorMessage(httpStatus: Int?): String =
    if (isExpiredStatus(httpStatus)) AUDIO_EXPIRED_MESSAGE else AUDIO_ERROR_MESSAGE

/**
 * Whether a position/duration read from the player belongs to the message the
 * bubble is showing. The player keeps the previous item's timeline through
 * `stop()` and reports its clock in the listener callbacks that fire during a
 * switch, so a reading is used only while the player holds [currentGuid]
 * ([playerMediaId] is the media id of the item in the player, null when it is
 * empty) and is not idle (stopped, or after an error: nothing is loaded then).
 */
fun shouldSyncClock(currentGuid: String?, playerMediaId: String?, idle: Boolean): Boolean =
    !idle && currentGuid != null && playerMediaId == currentGuid

/**
 * A press that has moved this far ([dx], [dy] from where the finger went down)
 * is a drag, not a long press. Pure distance test, so the slider's long-press
 * detector can be checked on the JVM.
 */
fun pastTouchSlop(dx: Float, dy: Float, slop: Float): Boolean =
    dx * dx + dy * dy > slop * slop
