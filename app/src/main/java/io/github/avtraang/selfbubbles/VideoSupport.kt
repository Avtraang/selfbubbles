package io.github.avtraang.selfbubbles

// Pure helpers for the built-in video player. No Android or media3 types in this
// file, so everything here is covered by JVM unit tests (VideoSupportTest).

private const val VIDEO_MIME_PREFIX = "video/"

/** Generic type for a video whose real type is unknown (used for MediaStore and the hand-off). */
const val VIDEO_MIME_FALLBACK = "video/*"

/** What the player shows for an attachment without a name. */
const val VIDEO_FALLBACK_NAME = "Video"

/** Seek step for the controller's rewind and fast-forward buttons. */
const val VIDEO_SEEK_INCREMENT_MS: Long = 10_000L

/**
 * "Open in another app" downloads the whole file into the cache first; this cap
 * keeps an oversized or endless body from filling the cache partition. Videos
 * run larger than PDFs (a few minutes of 4K from an iPhone is several hundred
 * MB), so it is twice the PDF hand-off cap.
 */
const val VIDEO_HANDOFF_MAX_BYTES: Long = 2048L * 1024 * 1024

/** Stem of the cached file name when the attachment's own name leaves nothing usable. */
private const val VIDEO_FALLBACK_STEM = "video"

const val VIDEO_OPEN_FAILED_MESSAGE = "Couldn't open attachment"
const val VIDEO_TOO_LARGE_MESSAGE = "Video is too large to open in another app"
const val VIDEO_PLAYBACK_FAILED_MESSAGE = "Can't play this video here"

/** Why playback stopped, with the line the player shows over the video. */
enum class VideoError(val message: String) {
    /** The relay no longer has the file: nothing to retry, save or hand to another app. */
    MISSING(ATTACHMENT_MISSING_MESSAGE),
    FAILED(VIDEO_PLAYBACK_FAILED_MESSAGE),
}

/**
 * The error a playback failure shows: [VideoError.MISSING] when the relay
 * answered [ATTACHMENT_MISSING_STATUS] ([httpStatus] is the code of the HTTP
 * error in the player's cause chain, null when there is none), else
 * [VideoError.FAILED].
 */
fun videoError(httpStatus: Int?): VideoError =
    if (httpStatus == ATTACHMENT_MISSING_STATUS) VideoError.MISSING else VideoError.FAILED

/** File name extensions the player treats as video, each with the type it implies. */
private val VIDEO_EXTENSIONS: Map<String, String> = mapOf(
    ".mov" to "video/quicktime",
    ".mp4" to "video/mp4",
    ".m4v" to "video/mp4",
    ".3gp" to "video/3gpp",
    ".webm" to "video/webm",
    ".mkv" to "video/x-matroska",
)

/** A relay attachment to play in the built-in player. */
data class VideoTarget(val url: String, val name: String?, val mime: String)

/** Where playback was, kept across a configuration change (rotation). */
data class VideoPlayback(val positionMs: Long, val playWhenReady: Boolean)

/** A fresh open: from the start, playing. */
val VIDEO_PLAYBACK_START = VideoPlayback(0L, true)

/** The mime type with parameters and whitespace removed, lower-cased; null when there is none. */
private fun baseMime(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** The extension of [name] that is in [VIDEO_EXTENSIONS], or null. */
private fun videoExtension(name: String?): String? {
    val n = name?.trim()?.lowercase() ?: return null
    return VIDEO_EXTENSIONS.keys.firstOrNull { n.endsWith(it) && n.length > it.length }
}

/**
 * An attachment is a video when its mime type starts with video/ (any case,
 * parameters ignored) or its name ends with a known video extension (any case).
 */
fun isVideoAttachment(mime: String?, name: String?): Boolean {
    val type = baseMime(mime)
    if (type != null && type.startsWith(VIDEO_MIME_PREFIX)) return true
    return videoExtension(name) != null
}

/**
 * The type to hand on with the video: the attachment's own video/ type when it
 * has one, else the type its extension implies, else [VIDEO_MIME_FALLBACK].
 */
fun videoMimeFor(mime: String?, name: String?): String {
    val type = baseMime(mime)
    if (type != null && type.startsWith(VIDEO_MIME_PREFIX)) return type
    val ext = videoExtension(name) ?: return VIDEO_MIME_FALLBACK
    return VIDEO_EXTENSIONS.getValue(ext)
}

/** The extension a video/ type implies (the first in [VIDEO_EXTENSIONS] with that type), or null. */
private fun videoExtensionForMime(mime: String?): String? {
    val type = baseMime(mime) ?: return null
    return VIDEO_EXTENSIONS.entries.firstOrNull { it.value == type }?.key
}

/**
 * The name the cached copy gets for other apps to show: [sanitizeFileName]
 * applied, so nothing in it can leave the attachment's own folder or disguise
 * itself. The extension is kept when the (cleaned) name ends in a known video
 * extension, in lower case; otherwise the one the type implies is appended, if
 * any, so "evil‮vom.4pm" with video/quicktime becomes "evilvom.4pm.mov".
 */
fun sanitizeVideoFileName(raw: String?, mime: String?): String {
    val cleaned = cleanFileName(raw)
    val ext = videoExtension(cleaned) ?: videoExtensionForMime(mime) ?: ""
    return sanitizeFileName(cleaned, ext, VIDEO_FALLBACK_STEM)
}

/**
 * Folder inside cacheDir/shared for one video, derived from its URL, so two
 * attachments both named IMG_0001.MOV (the iPhone default) never share a path
 * and a hand-off cannot replace the file behind another one's read grant.
 */
fun videoCacheDirName(url: String): String = attachmentCacheDirName("video", url)

/** The name shown in the player's top bar. */
fun videoDisplayName(name: String?): String = name?.trim()?.ifEmpty { null } ?: VIDEO_FALLBACK_NAME

/** What is kept of an open player across a configuration change. */
fun saveVideoTarget(target: VideoTarget?): ArrayList<String?>? =
    target?.let { arrayListOf(it.url, it.name, it.mime) }

/**
 * Inverse of [saveVideoTarget]. The URL is checked again with [isRelay], so a
 * restored player can never be pointed anywhere but the relay.
 */
fun restoreVideoTarget(saved: Any?, isRelay: (String) -> Boolean): VideoTarget? {
    val parts = saved as? List<*> ?: return null
    val url = parts.getOrNull(0) as? String ?: return null
    if (!isRelay(url)) return null
    val mime = parts.getOrNull(2) as? String ?: return null
    return VideoTarget(url, parts.getOrNull(1) as? String, mime)
}

fun saveVideoPlayback(p: VideoPlayback): ArrayList<Any?> = arrayListOf(p.positionMs, p.playWhenReady)

/** Inverse of [saveVideoPlayback]; anything malformed restores as a fresh start. */
fun restoreVideoPlayback(saved: Any?): VideoPlayback {
    val parts = saved as? List<*> ?: return VIDEO_PLAYBACK_START
    val position = (parts.getOrNull(0) as? Number)?.toLong() ?: return VIDEO_PLAYBACK_START
    val play = parts.getOrNull(1) as? Boolean ?: return VIDEO_PLAYBACK_START
    return VideoPlayback(clampVideoPosition(position, -1L), play)
}

/**
 * A position the player can be asked to seek to: never negative, and never past
 * the end when the duration is known ([durationMs] > 0; anything else means
 * unknown, as media3 reports it before the stream has been read).
 */
fun clampVideoPosition(positionMs: Long, durationMs: Long): Long {
    val floor = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0L) floor.coerceAtMost(durationMs) else floor
}
