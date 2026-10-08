package io.github.avtraang.selfbubbles

import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Pure helpers for the built-in PDF viewer. No Android types in this file, so
// everything here is covered by JVM unit tests (PdfSupportTest).

const val PDF_MIME = "application/pdf"

/** Above this the viewer does not download the file and shows its "too large" state. */
const val PDF_MAX_BYTES: Long = 150L * 1024 * 1024

/**
 * "Open in another app" from the too-large state still has to download the
 * file; this second, generous cap keeps an oversized or endless body from
 * filling the cache partition.
 */
const val PDF_HANDOFF_MAX_BYTES: Long = 1024L * 1024 * 1024

const val PDF_MIN_ZOOM = 1f
const val PDF_MAX_ZOOM = 5f
const val PDF_DOUBLE_TAP_ZOOM = 2.5f

/** No rendered page bitmap has a side longer than this. */
const val PDF_MAX_BITMAP_SIDE = 4096

/** No rendered page bitmap holds more pixels than this (48 MB as ARGB_8888). */
const val PDF_MAX_BITMAP_PIXELS: Long = 12_000_000L

const val PDF_NAME_MAX_CHARS = 120
private const val NAME_MAX_BYTES = 240   // file systems cap a name at 255 bytes, not characters
private const val PDF_EXT = ".pdf"
private const val PDF_FALLBACK_STEM = "document"
private const val RENDER_EPSILON = 1e-6

/** A relay attachment to show in the built-in viewer. */
data class PdfTarget(val url: String, val name: String?)

/** What is kept of an open viewer across a configuration change (rotation). */
fun savePdfTarget(target: PdfTarget?): ArrayList<String?>? =
    target?.let { arrayListOf(it.url, it.name) }

/**
 * Inverse of [savePdfTarget]. The URL is checked again with [isRelay], so a
 * restored viewer can never be pointed anywhere but the relay.
 */
fun restorePdfTarget(saved: Any?, isRelay: (String) -> Boolean): PdfTarget? {
    val parts = saved as? List<*> ?: return null
    val url = parts.getOrNull(0) as? String ?: return null
    if (!isRelay(url)) return null
    return PdfTarget(url, parts.getOrNull(1) as? String)
}

/**
 * Folder inside cacheDir/shared for one attachment, derived from its URL. Two
 * attachments with the same file name therefore never share a path, while the
 * file itself keeps its readable (sanitised) name for other apps to show.
 */
fun pdfCacheDirName(url: String): String = attachmentCacheDirName("pdf", url)

/**
 * A plain folder name for one attachment: [prefix], a dash and the first eight
 * bytes of the SHA-256 of its URL in hex. Shared by every in-app viewer that
 * caches a file for another app.
 */
fun attachmentCacheDirName(prefix: String, url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return prefix + "-" + digest.take(8).joinToString("") { "%02x".format(it) }
}

/** Rendered pages may hold about one eighth of the app's max heap. */
fun pdfCacheBudgetBytes(maxHeapBytes: Long): Long = (maxHeapBytes / 8).coerceAtLeast(0)

/**
 * Pixel cap for one rendered page given the cache budget: never more than
 * [PDF_MAX_BITMAP_PIXELS], and never so many that the bitmap alone would be
 * larger than the whole budget (which the cache would refuse to hold).
 */
fun pdfPagePixelCap(cacheBudgetBytes: Long): Long =
    min(PDF_MAX_BITMAP_PIXELS, cacheBudgetBytes / 4).coerceAtLeast(1)

/**
 * An attachment is a PDF when its mime type is application/pdf (any case,
 * parameters ignored) or its name ends with .pdf (any case).
 */
fun isPdfAttachment(mime: String?, name: String?): Boolean {
    val type = mime?.substringBefore(';')?.trim()
    if (type != null && type.equals(PDF_MIME, ignoreCase = true)) return true
    return name?.trim()?.endsWith(PDF_EXT, ignoreCase = true) == true
}

/**
 * Unicode direction-control characters. Left in a file name they can make
 * "evil\u202Efdp.exe" display as "evilexe.pdf"; a name never needs them.
 */
private fun Char.isBidiControl(): Boolean =
    this == '\u061C' || this == '\u200E' || this == '\u200F' ||
        this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069'

/**
 * The one name cleaner for the cached file and the saved copy. Removes path
 * separators, backslashes, control characters, ".." and leading dots; caps the
 * length at [PDF_NAME_MAX_CHARS]; never returns an empty name; always ends in
 * ".pdf".
 */
fun sanitizePdfFileName(raw: String?): String = sanitizeFileName(raw, PDF_EXT, PDF_FALLBACK_STEM)

/**
 * The first half of [sanitizeFileName]: [raw] without path separators,
 * backslashes, control and direction-control characters or "..", trimmed. Not
 * capped and with its extension untouched, so a caller can still read the
 * extension off it.
 */
fun cleanFileName(raw: String?): String {
    var s = (raw ?: "").filterNot { it == '/' || it == '\\' || it.isISOControl() || it.isBidiControl() }
    while (s.contains("..")) s = s.replace("..", "")
    return s.trim()
}

/**
 * A safe plain file name ending in [ext] (which includes its dot, or is empty
 * for no extension): [cleanFileName] applied, [ext] taken off the end in any
 * case and put back in the given case, leading and trailing dots removed, the
 * length capped at [maxChars] characters and [NAME_MAX_BYTES] bytes; never empty
 * ([fallbackStem] stands in).
 */
fun sanitizeFileName(raw: String?, ext: String, fallbackStem: String, maxChars: Int = PDF_NAME_MAX_CHARS): String {
    var s = cleanFileName(raw)
    if (ext.isNotEmpty() && s.endsWith(ext, ignoreCase = true)) s = s.dropLast(ext.length)   // put back below
    s = s.trim { it == '.' || it.isWhitespace() }

    s = s.take((maxChars - ext.length).coerceAtLeast(0))
    while (s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size > NAME_MAX_BYTES - ext.length) {
        s = s.dropLast(1)
    }
    // Never leave half of a surrogate pair at the cut.
    if (s.isNotEmpty() && s.last().isHighSurrogate()) s = s.dropLast(1)
    s = s.trimEnd { it == '.' || it.isWhitespace() }

    return s.ifEmpty { fallbackStem } + ext
}

data class RenderSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val bytes: Long get() = pixels * 4   // ARGB_8888
}

/**
 * Bitmap size for one page: the page fitted to [viewportWidthPx] and multiplied
 * by [zoom] (clamped to 1x..5x), then scaled down as a whole, keeping the
 * aspect ratio, until no side exceeds [maxSide] and the area does not exceed
 * [maxPixels]. [shrink] (0..1] lowers the resolution further for the retry after
 * an OutOfMemoryError. Never returns a zero or negative dimension, whatever the
 * input.
 */
fun computeRenderSize(
    pageWidthPt: Int,
    pageHeightPt: Int,
    viewportWidthPx: Int,
    zoom: Float,
    shrink: Float = 1f,
    maxSide: Int = PDF_MAX_BITMAP_SIDE,
    maxPixels: Long = PDF_MAX_BITMAP_PIXELS,
): RenderSize {
    if (pageWidthPt <= 0 || pageHeightPt <= 0 || viewportWidthPx <= 0 || maxSide <= 0 || maxPixels <= 0) {
        return RenderSize(1, 1)
    }
    val z = (if (zoom.isNaN()) PDF_MIN_ZOOM else zoom).coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM).toDouble()
    val k = (if (shrink.isNaN() || shrink <= 0f) 1f else shrink).coerceAtMost(1f).toDouble()

    val wantW = viewportWidthPx * z * k
    val wantH = wantW * pageHeightPt / pageWidthPt
    val fit = min(
        1.0,
        min(min(maxSide / wantW, maxSide / wantH), sqrt(maxPixels / (wantW * wantH))),
    )
    // floor, so rounding can never push a side or the area over a cap
    // (the epsilon keeps an exact result such as 4096.0 from landing on 4095.999…)
    var w = floor(wantW * fit + RENDER_EPSILON).toInt().coerceIn(1, maxSide)
    var h = floor(wantH * fit + RENDER_EPSILON).toInt().coerceIn(1, maxSide)
    while (w.toLong() * h > maxPixels && (w > 1 || h > 1)) {
        if (w >= h) w-- else h--
    }
    return RenderSize(w, h)
}

/**
 * Bookkeeping for a cache bounded by bytes rather than entries: which keys are
 * held, how large each is, and which to drop (least recently used first) to
 * stay inside [budgetBytes]. Holds no values itself; the caller keeps them and
 * removes whatever [put] says was evicted. Not thread-safe.
 */
class ByteBudgetLru<K>(val budgetBytes: Long) {
    private val sizes = LinkedHashMap<K, Long>(16, 0.75f, true)   // access order

    var totalBytes: Long = 0
        private set

    val size: Int get() = sizes.size

    /** Keys from least to most recently used. */
    val keys: List<K> get() = sizes.keys.toList()

    class PutResult<K>(val accepted: Boolean, val evicted: List<K>)

    /** True (and marks the key most recently used) when the key is held. */
    fun touch(key: K): Boolean = sizes[key] != null

    /**
     * Records [key] at [bytes]. An entry larger than the whole budget (or with a
     * non-positive size) is refused and, if the key was held, dropped. Otherwise
     * older entries are evicted until the total fits.
     */
    fun put(key: K, bytes: Long): PutResult<K> {
        remove(key)
        if (bytes <= 0 || bytes > budgetBytes) return PutResult(false, emptyList())
        val evicted = ArrayList<K>()
        val oldest = sizes.entries.iterator()
        while (totalBytes + bytes > budgetBytes && oldest.hasNext()) {
            val e = oldest.next()
            totalBytes -= e.value
            evicted += e.key
            oldest.remove()
        }
        sizes[key] = bytes
        totalBytes += bytes
        return PutResult(true, evicted)
    }

    fun remove(key: K): Boolean {
        val old = sizes.remove(key) ?: return false
        totalBytes -= old
        return true
    }

    fun clear() {
        sizes.clear()
        totalBytes = 0
    }
}

/** One laid-out page of the list: where its top edge is and how tall it is, in pixels. */
data class PageSpan(val index: Int, val top: Float, val size: Float)

/**
 * Where the list has to be after a zoom step: item [index] scrolled
 * [scrollOffset] pixels past the top of the content area. [spans] is the
 * layout that results, for a further step that arrives before the list has
 * been measured again.
 */
class ZoomAnchor(val index: Int, val scrollOffset: Int, val spans: List<PageSpan>)

/**
 * Scroll position that keeps the point under [focusY] in place while every
 * page grows by [factor], then moves the content by [panY]. [spans] are the
 * pages currently laid out (consecutive, top to bottom, tops in the same
 * coordinates as [focusY]), [gap] the fixed space between pages, which does not
 * scale, and [paddingTop] where the first page sits when the list is at its
 * start.
 *
 * The answer is a position in the layout as it will be AFTER the zoom, so it
 * does not depend on how far the list can scroll before it: a page that fits
 * the screen at 1x still ends up with the tapped point under the finger.
 * Null when nothing is laid out.
 */
fun anchorZoom(
    spans: List<PageSpan>,
    gap: Float,
    paddingTop: Float,
    focusY: Float,
    panY: Float,
    factor: Float,
): ZoomAnchor? {
    if (spans.isEmpty() || factor.isNaN() || factor <= 0f) return null
    val at = spans.indexOfLast { it.top <= focusY }.coerceAtLeast(0)
    val under = spans[at]
    val fromTop = focusY - under.top
    val inPage = fromTop.coerceIn(0f, under.size)   // the rest is gap or padding, which keeps its size

    val tops = FloatArray(spans.size)
    tops[at] = focusY + panY - (inPage * factor + (fromTop - inPage))
    for (i in at + 1 until spans.size) tops[i] = tops[i - 1] + spans[i - 1].size * factor + gap
    for (i in at - 1 downTo 0) tops[i] = tops[i + 1] - gap - spans[i].size * factor

    // The first page of the document cannot sit below the start of the list.
    if (spans[0].index == 0 && tops[0] > paddingTop) {
        val shift = paddingTop - tops[0]
        for (i in tops.indices) tops[i] += shift
    }

    val zoomed = spans.mapIndexed { i, s -> PageSpan(s.index, tops[i], s.size * factor) }
    // Name the position by the page that crosses the top edge, as the list itself does.
    val first = zoomed.firstOrNull { it.top + it.size + gap > paddingTop } ?: zoomed.last()
    return ZoomAnchor(first.index, (paddingTop - first.top).roundToInt(), zoomed)
}

/**
 * Index of the page the "page / total" pill names: the first page while the
 * list is at its start, the last one once it is at its end, and in between the
 * page that fills most of the viewport (the earlier one on a tie). Null when
 * nothing is laid out. [spans] and the viewport edges share one coordinate
 * system.
 */
fun currentPdfPage(
    spans: List<PageSpan>,
    viewportTop: Float,
    viewportBottom: Float,
    atStart: Boolean,
    atEnd: Boolean,
): Int? {
    if (spans.isEmpty()) return null
    if (atStart) return spans.first().index
    if (atEnd) return spans.last().index
    var best = spans.first()
    var bestVisible = Float.NEGATIVE_INFINITY
    for (s in spans) {
        val visible = min(s.top + s.size, viewportBottom) - maxOf(s.top, viewportTop)
        if (visible > bestVisible) {
            best = s
            bestVisible = visible
        }
    }
    return best.index
}
