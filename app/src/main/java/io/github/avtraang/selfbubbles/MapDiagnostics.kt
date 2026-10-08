package io.github.avtraang.selfbubbles

// What the family map screen may repeat of a URL or of a line the map page printed
// (MapScreen.kt writes both to logcat under the tag FamilyMap, and shows the last one
// on the screen while the map loads). Plain Kotlin (no Android, no Compose), so
// MapDiagnosticsTest pins every rule here.

/** The `user:password@` part of a URL, which a log line never repeats. */
private val URL_USERINFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]*@")

/**
 * A URL as a diagnostic may name it: scheme, host and path. The query string
 * and the fragment are cut (a map page's URL carries its key there), and so is
 * any `user:password@` in front of the host.
 */
internal fun safeUrl(raw: String?): String {
    if (raw.isNullOrBlank()) return "(unknown)"
    return raw.substringBefore('#').substringBefore('?').replace(URL_USERINFO, "$1").take(240)
}

private val BEARER = Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]+")
private val JWT = Regex("\\b[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
/** An absolute URL of the kinds a page loads or connects to: http(s) and WebSocket. */
private val ABSOLUTE_URL = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"']+")
/** A relative URL with a query string: the path is kept. */
private val PATH_WITH_QUERY = Regex("(/[^\\s\"'?]*)\\?[^\\s\"']*")
/** `name=value` where the name says the value is a credential. */
private val SECRET_PAIR =
    Regex("(?i)\\b([a-z0-9_.-]*(?:token|key|secret|password|passwd|auth|signature|session))=[^\\s&\"']+")

/**
 * A line the map page printed (a console message, a status) as the app may
 * repeat it. Bearer tokens and JWT-shaped strings are masked; every absolute
 * URL (http, https, ws, wss) is cut down by [safeUrl]; a relative URL loses
 * its query string; and what is left of a `token=…`, `key=…` or similar pair
 * is masked. The rest is the page's own words, capped at 500 characters.
 */
internal fun sanitizeDiagnostic(message: String): String {
    val withoutBearer = message.replace(BEARER, "Bearer [redacted]")
    val withoutJwt = withoutBearer.replace(JWT, "[JWT redacted]")
    val withoutUrlQueries = ABSOLUTE_URL.replace(withoutJwt) { match -> safeUrl(match.value) }
    val withoutPathQueries = withoutUrlQueries.replace(PATH_WITH_QUERY, "$1")
    return withoutPathQueries.replace(SECRET_PAIR, "$1=[redacted]").take(500)
}
