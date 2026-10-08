package io.github.avtraang.selfbubbles

import java.nio.charset.Charset
import java.util.Base64

// Pure vCard reader behind the contact-card bubble (ContactCardBubble.kt). No
// Android types in this file, so everything here is covered by JVM unit tests
// (VCardTest). Reads vCard 2.1, 3.0 and 4.0 as phones actually send them and
// never throws on bad input: whatever could be read is returned.

/** The bubble does not download a card larger than this, and the parser reads no more of one. */
const val VCARD_MAX_INPUT_BYTES: Long = 1024L * 1024

/** Cards read from one file, entries kept per list, bytes kept of a photo. */
const val VCARD_MAX_CARDS = 10
const val VCARD_MAX_ENTRIES = 20
const val VCARD_MAX_PHOTO_BYTES = 2 * 1024 * 1024

const val CONTACT_CARD_ERROR_MESSAGE = "Can't read this contact"
const val CONTACT_CARD_LOADING_LABEL = "Contact card"
const val CONTACT_CARD_NO_APP_MESSAGE = "No contacts app"
const val CONTACT_CARD_RETRY_LABEL = "Tap to retry"

private const val VCARD_EXT = ".vcf"
private const val VCARD_FALLBACK_STEM = "contact"
private val VCARD_MIMES = setOf("text/vcard", "text/x-vcard", "text/directory")
private val VCARD_EXTENSIONS = listOf(".vcf", ".vcard")

/** A value with the label it is filed under ("Mobile", "Home", "Work", "Main", "Other"). */
data class Labeled(val label: String, val value: String)

/** One contact read from a vCard. Lists are capped at [VCARD_MAX_ENTRIES]; [photo] at [VCARD_MAX_PHOTO_BYTES]. */
class ContactCard(
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    val phones: List<Labeled>,
    val emails: List<Labeled>,
    val org: String?,
    val title: String?,
    val addresses: List<Labeled>,
    val urls: List<Labeled>,
    val note: String?,
    val birthday: String?,
    val photo: ByteArray?,
    val photoMime: String?,
)

/**
 * An attachment is a contact card when its mime type is text/vcard or
 * text/x-vcard (any case, parameters ignored) or its name ends with .vcf or
 * .vcard (any case).
 */
fun isContactCardAttachment(mime: String?, name: String?): Boolean {
    val type = mime?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type in VCARD_MIMES) return true
    val n = name?.trim()?.lowercase() ?: return false
    return VCARD_EXTENSIONS.any { n.endsWith(it) && n.length > it.length }
}

/** The cached copy's file name: cleaned, capped, always ending in .vcf. */
fun sanitizeContactFileName(raw: String?): String = sanitizeFileName(raw, VCARD_EXT, VCARD_FALLBACK_STEM)

/** Folder inside cacheDir/shared for one contact-card attachment. */
fun contactCardCacheDirName(url: String): String = attachmentCacheDirName("vcard", url)

/** The bubble's second line: the first phone, else the first email, else the organisation; null when there is none. */
fun contactSecondaryLine(card: ContactCard): String? =
    card.phones.firstOrNull()?.value ?: card.emails.firstOrNull()?.value ?: card.org

/**
 * Power-of-two factor that brings a [width] x [height] image down to about
 * [targetPx] on its longer side (never below it), for BitmapFactory's
 * inSampleSize. 1 for anything already small or for nonsense sizes.
 */
fun photoSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** The text of a vCard file: UTF-8, with a byte-order mark dropped. */
fun vcardText(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
    return if (text.startsWith('﻿')) text.substring(1) else text
}

/**
 * Every contact in [text]: at most [VCARD_MAX_CARDS], in file order. Handles
 * CRLF or LF, folded lines, quoted-printable (2.1) and base64 values, escaped
 * characters, bare and named parameters, Apple's item groups with X-ABLabel,
 * several cards in one file, and a file cut off anywhere. A card that ends up
 * with no name and no phone or email is left out.
 */
fun parseVCards(text: String): List<ContactCard> {
    val capped = if (text.length > VCARD_MAX_INPUT_BYTES) text.substring(0, VCARD_MAX_INPUT_BYTES.toInt()) else text
    val cards = ArrayList<ContactCard>()
    var current: CardBuilder? = null
    for (line in unfold(capped)) {
        if (cards.size >= VCARD_MAX_CARDS) break
        val prop = parseProperty(line) ?: continue
        when (prop.name) {
            "BEGIN" -> if (prop.rawValue.trim().equals("VCARD", ignoreCase = true)) {
                current?.build()?.let { cards += it }   // a BEGIN inside a card: the first one is kept as read
                current = CardBuilder()
            }
            "END" -> if (prop.rawValue.trim().equals("VCARD", ignoreCase = true)) {
                current?.build()?.let { cards += it }
                current = null
            }
            else -> current?.add(prop)
        }
    }
    if (cards.size < VCARD_MAX_CARDS) current?.build()?.let { cards += it }   // cut off before END
    return cards
}

// ---- lines ----

/**
 * Logical lines: a quoted-printable value ending in "=" continues on the next
 * physical line with that line kept whole (2.1 soft break, checked first: a
 * continuation may start with a space that is data); otherwise a physical line
 * starting with a space or tab continues the one before (the break and that
 * one character dropped).
 */
private fun unfold(text: String): List<String> {
    val out = ArrayList<String>()
    var current: LogicalLine? = null
    for (raw in text.split("\r\n", "\n", "\r")) {
        val cur = current
        if (cur != null) {
            if (cur.endsWithQpSoftBreak()) {
                cur.text.setLength(cur.text.length - 1)
                cur.text.append(raw)
                continue
            }
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) {
                cur.text.append(raw, 1, raw.length)
                continue
            }
            out += cur.text.toString()
        }
        current = LogicalLine(raw)
    }
    current?.let { out += it.text.toString() }
    return out
}

/**
 * One logical line being assembled. Whether its head (everything before the
 * first unquoted colon) declares quoted-printable is worked out once, scanning
 * each character a single time across continuations, so a line made of very
 * many soft breaks costs linear time rather than a rescan of the head per break.
 */
private class LogicalLine(raw: String) {
    val text = StringBuilder(raw)
    private var quotedPrintable: Boolean? = null   // null until the colon has been seen
    private var scanned = 0
    private var inQuotes = false

    fun endsWithQpSoftBreak(): Boolean {
        if (text.isEmpty() || text[text.length - 1] != '=') return false
        quotedPrintable?.let { return it }
        while (scanned < text.length) {
            val c = text[scanned]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) {
                val qp = text.substring(0, scanned).uppercase().contains("QUOTED-PRINTABLE")
                quotedPrintable = qp
                return qp
            }
            scanned++
        }
        return false   // no colon yet: still inside the head
    }
}

private fun indexOfUnquoted(s: CharSequence, ch: Char, from: Int = 0): Int {
    var quoted = false
    for (i in from until s.length) {
        val c = s[i]
        if (c == '"') quoted = !quoted
        else if (c == ch && !quoted) return i
    }
    return -1
}

private fun splitUnquoted(s: String, ch: Char): List<String> {
    val parts = ArrayList<String>()
    var start = 0
    while (true) {
        val i = indexOfUnquoted(s, ch, start)
        if (i < 0) break
        parts += s.substring(start, i)
        start = i + 1
    }
    parts += s.substring(start)
    return parts
}

// ---- properties ----

private class Property(
    val group: String?,
    val name: String,
    /** Parameter name (upper case) to its values (upper case). Bare 2.1 parameters are filed under TYPE. */
    val params: Map<String, List<String>>,
    val rawValue: String,
) {
    val types: List<String> get() = params["TYPE"].orEmpty()
    fun param(name: String): String? = params[name]?.firstOrNull()
    val encoding: String? get() = param("ENCODING")
    val isQuotedPrintable: Boolean get() = encoding == "QUOTED-PRINTABLE" || "QUOTED-PRINTABLE" in types
    val isBase64: Boolean get() = encoding == "B" || encoding == "BASE64" || "BASE64" in types
}

private fun parseProperty(line: String): Property? {
    val colon = indexOfUnquoted(line, ':')
    if (colon <= 0) return null
    val head = splitUnquoted(line.substring(0, colon), ';')
    var nameSeg = head[0].trim()
    if (nameSeg.isEmpty()) return null
    var group: String? = null
    val dot = nameSeg.lastIndexOf('.')
    if (dot >= 0) {
        group = nameSeg.substring(0, dot).trim().uppercase().ifEmpty { null }
        nameSeg = nameSeg.substring(dot + 1)
    }
    val name = nameSeg.trim().uppercase()
    if (name.isEmpty()) return null
    val params = HashMap<String, MutableList<String>>()
    for (seg in head.drop(1)) {
        val s = seg.trim()
        if (s.isEmpty()) continue
        val eq = s.indexOf('=')
        val key: String
        val values: List<String>
        if (eq < 0) {
            key = "TYPE"
            values = listOf(s)
        } else {
            key = s.substring(0, eq).trim().uppercase()
            values = splitUnquoted(s.substring(eq + 1), ',')
        }
        val list = params.getOrPut(key) { ArrayList() }
        for (v in values) {
            // 4.0 may quote a list: TYPE="voice,cell" is still two values.
            for (part in v.trim().removeSurrounding("\"").split(',')) {
                val clean = part.trim().uppercase()
                if (clean.isNotEmpty()) list += clean
            }
        }
    }
    return Property(group, name, params, line.substring(colon + 1))
}

// ---- values ----

private fun unescape(s: String): String {
    if (s.indexOf('\\') < 0) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n', 'N' -> out.append('\n')
                else -> out.append(n)   // \, \; \\ and anything else escaped
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** Splits on unescaped [sep]; a backslash escapes the next character. */
private fun splitEscaped(s: String, sep: Char): List<String> {
    val parts = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            cur.append(c).append(s[i + 1])
            i += 2
            continue
        }
        if (c == sep) {
            parts += cur.toString()
            cur.setLength(0)
        } else cur.append(c)
        i++
    }
    parts += cur.toString()
    return parts
}

private fun charsetFor(name: String?): Charset {
    if (name == null) return Charsets.UTF_8
    return when (name.replace("_", "-")) {
        "UTF-8", "UTF8" -> Charsets.UTF_8
        "ISO-8859-1", "ISO8859-1", "LATIN1", "LATIN-1", "L1" -> Charsets.ISO_8859_1
        "US-ASCII", "ASCII" -> Charsets.US_ASCII
        else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }
}

/** Quoted-printable to text in [charset]. A broken escape is kept as it was written. */
private fun decodeQuotedPrintable(s: String, charset: Charset): String {
    val bytes = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '=') {
            val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
            val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        // Not an escape (or a broken one): the character as its own bytes.
        val chunk = c.toString().toByteArray(charset)
        bytes.write(chunk, 0, chunk.size)
        i++
    }
    return String(bytes.toByteArray(), charset)
}

/** The property's text value: quoted-printable decoded when declared, otherwise as written (escapes left for the field reader). */
private fun textValue(p: Property): String =
    if (p.isQuotedPrintable) decodeQuotedPrintable(p.rawValue, charsetFor(p.param("CHARSET"))) else p.rawValue

/** Base64 (strict, whitespace ignored) to bytes, or null when it is not base64. */
private fun decodeBase64(s: String): ByteArray? {
    val compact = s.filterNot { it.isWhitespace() }
    if (compact.isEmpty()) return null
    return runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
}

private fun mimeForPhotoType(type: String?): String? {
    val t = type?.trim()?.lowercase() ?: return null
    if (t.isEmpty()) return null
    if (t.startsWith("image/")) return t
    return when (t) {
        "jpeg", "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "image/$t"
    }
}

private fun sniffImageMime(b: ByteArray): String? = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
    b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
    b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "image/webp"
    else -> null
}

// ---- labels ----

private fun phoneLabel(types: List<String>): String = when {
    "FAX" in types -> "Fax"
    "PAGER" in types -> "Pager"
    "CELL" in types || "MOBILE" in types || "IPHONE" in types -> "Mobile"
    "MAIN" in types -> "Main"
    "HOME" in types -> "Home"
    "WORK" in types -> "Work"
    else -> "Other"
}

private fun placeLabel(types: List<String>): String = when {
    "HOME" in types -> "Home"
    "WORK" in types -> "Work"
    else -> "Other"
}

/** Apple's "_$!<Home>!$_" becomes "Home"; any other text is a custom label as typed. */
private fun abLabel(raw: String): String? {
    val t = unescape(raw).trim()
    val inner = if (t.startsWith("_\$!<") && t.endsWith(">!\$_")) t.substring(4, t.length - 4).trim() else t
    return inner.ifEmpty { null }
}

/**
 * "19850412" or "1985-04-12T00:00:00Z" becomes "1985-04-12". A birthday without
 * a year becomes "--04-12", the form the Contacts app reads: from "--0412" (4.0),
 * or from a date whose year is [omitYear], the placeholder iOS writes with
 * X-APPLE-OMIT-YEAR (BDAY;X-APPLE-OMIT-YEAR=1604:1604-04-12). Anything else is
 * kept as written.
 */
private fun formatBirthday(raw: String, omitYear: String?): String? {
    var t = unescape(raw).trim().substringBefore('T')
    if (t.isEmpty()) return null
    if (t.length == 8 && t.all { it.isDigit() }) t = "${t.substring(0, 4)}-${t.substring(4, 6)}-${t.substring(6, 8)}"
    if (t.length == 6 && t.startsWith("--") && t.substring(2).all { it.isDigit() }) return "--${t.substring(2, 4)}-${t.substring(4, 6)}"
    if (omitYear != null && t.length == 10 && t.startsWith("$omitYear-")) return "--${t.substring(5)}"
    return t
}

/** A birthday as the Details dialog shows it: a year-less "--04-12" reads "04-12"; anything else as stored. */
fun contactBirthdayLabel(birthday: String): String =
    if (birthday.startsWith("--")) birthday.substring(2) else birthday

/**
 * The address a website row may open, or null when it must only be copied.
 * Only http and https are handed to the system (any case); a bare host or
 * host:port gets https. Every other scheme a stranger's card could carry
 * (file, content, intent, javascript, an app's own deep link) is refused.
 */
fun websiteLaunchUrl(raw: String): String? {
    val v = raw.trim()
    if (v.isEmpty() || v.any { it.isWhitespace() }) return null
    val colon = v.indexOf(':')
    val scheme = if (colon > 0) v.substring(0, colon) else null
    val looksLikeScheme = scheme != null && scheme[0].isLetter() &&
        scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    // "example.com:8080/x" or "localhost:3000" is a host with a port (at most five digits, then the
    // end or a path), not a scheme; "sms:5550100033" and "tel:..." are schemes and are refused.
    val hostWithPort = looksLikeScheme && (scheme!!.contains('.') || scheme.equals("localhost", ignoreCase = true)) &&
        HOST_PORT_TAIL.containsMatchIn(v.substring(colon + 1))
    if (!looksLikeScheme || hostWithPort) return "https://$v"
    return if (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) v else null
}

private val HOST_PORT_TAIL = Regex("^\\d{1,5}(?:$|[/?#])")

// ---- one card ----

private class Entry(val group: String?, var label: String, val value: String)

private class CardBuilder {
    private var fn: String? = null
    private var given: String? = null
    private var family: String? = null
    private var org: String? = null
    private var title: String? = null
    private var note: String? = null
    private var birthday: String? = null
    private var photo: ByteArray? = null
    private var photoMime: String? = null
    private val phones = ArrayList<Entry>()
    private val emails = ArrayList<Entry>()
    private val addresses = ArrayList<Entry>()
    private val urls = ArrayList<Entry>()
    private val groupLabels = HashMap<String, String>()

    private fun MutableList<Entry>.addCapped(e: Entry) {
        if (size < VCARD_MAX_ENTRIES) add(e)
    }

    fun add(p: Property) {
        when (p.name) {
            "FN" -> if (fn == null) fn = unescape(textValue(p)).trim().ifEmpty { null }
            "N" -> {
                val parts = splitEscaped(textValue(p), ';').map { unescape(it).trim() }
                if (family == null) family = parts.getOrNull(0)?.ifEmpty { null }
                if (given == null) given = parts.getOrNull(1)?.ifEmpty { null }
            }
            "ORG" -> if (org == null) {
                org = splitEscaped(textValue(p), ';').map { unescape(it).trim() }.firstOrNull { it.isNotEmpty() }
            }
            "TITLE" -> if (title == null) title = unescape(textValue(p)).trim().ifEmpty { null }
            "NOTE" -> if (note == null) note = unescape(textValue(p)).trim().ifEmpty { null }
            "BDAY" -> if (birthday == null) birthday = formatBirthday(textValue(p), p.param("X-APPLE-OMIT-YEAR"))
            "TEL" -> {
                var v = unescape(textValue(p)).trim()
                if (v.startsWith("tel:", ignoreCase = true)) v = v.substring(4).trim()
                if (v.isNotEmpty()) phones.addCapped(Entry(p.group, phoneLabel(p.types), v))
            }
            "EMAIL" -> {
                var v = unescape(textValue(p)).trim()
                if (v.startsWith("mailto:", ignoreCase = true)) v = v.substring(7).trim()
                if (v.isNotEmpty()) emails.addCapped(Entry(p.group, placeLabel(p.types), v))
            }
            "ADR" -> {
                val parts = splitEscaped(textValue(p), ';').map { unescape(it).replace('\n', ' ').trim() }
                val v = parts.filter { it.isNotEmpty() }.joinToString(", ")
                if (v.isNotEmpty()) addresses.addCapped(Entry(p.group, placeLabel(p.types), v))
            }
            "URL" -> {
                val v = unescape(textValue(p)).trim()
                if (v.isNotEmpty()) urls.addCapped(Entry(p.group, placeLabel(p.types), v))
            }
            "PHOTO" -> if (photo == null) readPhoto(p)
            "X-ABLABEL" -> {
                val g = p.group ?: return
                abLabel(p.rawValue)?.let { groupLabels.putIfAbsent(g, it) }
            }
        }
    }

    private fun readPhoto(p: Property) {
        val raw = p.rawValue.trim()
        var declared: String? = p.param("MEDIATYPE") ?: p.types.firstOrNull { it != "PREF" && it != "BASE64" }
        val bytes: ByteArray? = when {
            p.isBase64 -> decodeBase64(raw)
            raw.startsWith("data:", ignoreCase = true) -> {   // vCard 4.0: PHOTO:data:image/jpeg;base64,....
                val comma = raw.indexOf(',')
                if (comma < 0) null else {
                    val header = raw.substring(5, comma)
                    if (!header.lowercase().endsWith(";base64")) null else {
                        header.substringBefore(';').trim().ifEmpty { null }?.let { declared = it }
                        decodeBase64(raw.substring(comma + 1))
                    }
                }
            }
            else -> null   // a URL or an unknown encoding: nothing is fetched
        }
        if (bytes == null || bytes.isEmpty() || bytes.size > VCARD_MAX_PHOTO_BYTES) return
        photo = bytes
        photoMime = mimeForPhotoType(declared) ?: sniffImageMime(bytes)
    }

    private fun labeled(entries: List<Entry>): List<Labeled> = entries.map { e ->
        val custom = e.group?.let { groupLabels[it] }
        Labeled(custom ?: e.label, e.value)
    }

    fun build(): ContactCard? {
        val fromN = listOfNotNull(given, family).joinToString(" ").trim().ifEmpty { null }
        val name = fn ?: fromN ?: org ?: phones.firstOrNull()?.value ?: emails.firstOrNull()?.value ?: return null
        return ContactCard(
            displayName = name,
            givenName = given,
            familyName = family,
            phones = labeled(phones),
            emails = labeled(emails),
            org = org,
            title = title,
            addresses = labeled(addresses),
            urls = labeled(urls),
            note = note,
            birthday = birthday,
            photo = photo,
            photoMime = photoMime,
        )
    }
}
