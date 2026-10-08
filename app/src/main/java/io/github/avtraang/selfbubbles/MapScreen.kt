package io.github.avtraang.selfbubbles

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.Request
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme

// Page loaded in the Map tab; the FAMILY_MAP_URL key of secrets.properties by
// default, overridable in Settings (RelayConfigStore). A getter over the live
// snapshot, like BASE in Imsg.kt.
val FAMILY_MAP_URL: String get() = RelayConfigStore.current.mapUrl

// Host of FAMILY_MAP_URL, so WebView error reporting follows wherever the map
// page lives instead of naming a host here.
private val FAMILY_MAP_HOST: String get() = Uri.parse(FAMILY_MAP_URL).host.orEmpty().lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onBack: () -> Unit) {
    var diag by remember { mutableStateOf("Starting Android WebView…") }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Family Map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(webViewGeneration) {
                MapWebView(
                    onDiag = { diag = it },
                    onRendererGone = { webViewGeneration += 1 },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapWebView(
    onDiag: (String) -> Unit,
    onRendererGone: () -> Unit,
) {
    val latestOnDiag by rememberUpdatedState(onDiag)
    val latestOnRendererGone by rememberUpdatedState(onRendererGone)
    // The app's dark setting, passed to the page as an explicit scheme.
    val darkTheme = isSystemInDarkTheme()
    // The map page is dark, so the WebView under it keeps the dark palette's
    // Gray 1 in both themes (what the hard-coded rgb(28, 28, 30) was).
    MessagesTheme(darkTheme = true) {
    val webBackground = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val debuggable = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            WebView.setWebContentsDebuggingEnabled(debuggable)

            WebView(ctx).apply {
                val report: (String) -> Unit = { message ->
                    val safe = sanitizeDiagnostic(message)
                    Log.d(MAP_LOG_TAG, safe)
                    post { latestOnDiag(safe) }
                }

                setBackgroundColor(webBackground)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.allowFileAccess = false
                settings.allowContentAccess = false

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        val message = sanitizeDiagnostic(m.message())
                        val source = safeUrl(m.sourceId())
                        Log.d(
                            MAP_LOG_TAG,
                            "Console ${m.messageLevel()}: $message @$source:${m.lineNumber()}",
                        )

                        if (
                            message.startsWith("[family-map]") ||
                            m.messageLevel() == ConsoleMessage.MessageLevel.WARNING ||
                            m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        ) {
                            report("JS ${m.messageLevel()}: $message")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    /**
                     * A page on the relay itself (its own /map route, which fetches
                     * /locations on the relay origin) needs the relay headers, which
                     * a WebView cannot send. Those requests — GET, exact relay origin,
                     * nothing else (RelayWebAssets.shouldProxy) — go through the shared
                     * OkHttp client, whose interceptors attach the credentials for that
                     * origin only. Everything else, the owner's map on another host and
                     * every CDN included, returns null and loads as it always did.
                     */
                    override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                        if (!RelayWebAssets.shouldProxy(req.url.toString(), req.method, RelayConfigStore.current)) {
                            return null
                        }
                        return RelayWebAssets.fetch(req.url.toString(), req.requestHeaders) { report(it) }
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        report("Loading ${safeUrl(url)}")
                    }

                    override fun onPageCommitVisible(v: WebView, url: String?) {
                        report("Page visible: ${safeUrl(url)}")
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        report("Page loaded: ${safeUrl(url)} · waiting for MapKit")
                        listOf(750L, 3_000L, 10_000L).forEach { delayMs ->
                            v.postDelayed(
                                {
                                    if (v.isAttachedToWindow) probeMapPage(v, report)
                                },
                                delayMs,
                            )
                        }
                    }

                    override fun onReceivedError(
                        v: WebView, req: WebResourceRequest, err: WebResourceError,
                    ) {
                        if (shouldReport(req)) {
                            val target = safeUrl(req.url.toString())
                            report(
                                "${if (req.isForMainFrame) "PAGE" else "RESOURCE"} " +
                                    "ERR ${err.errorCode}: ${err.description} — $target",
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        v: WebView, req: WebResourceRequest,
                        resp: WebResourceResponse,
                    ) {
                        if (shouldReport(req)) {
                            val target = safeUrl(req.url.toString())
                            report(
                                "${if (req.isForMainFrame) "PAGE" else "RESOURCE"} " +
                                    "HTTP ${resp.statusCode} — $target",
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        v: WebView,
                        handler: SslErrorHandler,
                        error: SslError,
                    ) {
                        handler.cancel()
                        report("TLS error ${error.primaryError} — ${safeUrl(error.url)}")
                    }

                    override fun onRenderProcessGone(
                        v: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        report(
                            "WebView renderer exited " +
                                "(crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}); " +
                                "restarting…",
                        )
                        v.post { latestOnRendererGone() }
                        return true
                    }
                }

                val webViewVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
                report("WebView $webViewVersion · loading map")
                loadUrl(mapUrlWithCacheBuster(darkTheme))
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        },
    )
    }  // close MessagesTheme(darkTheme = true)
}

private const val MAP_LOG_TAG = "FamilyMap"
private const val MAP_PAGE_REVISION = "20260716-1"

/**
 * Header injection for a map page served by the relay itself. [shouldProxy] is
 * the whole decision and is pure (RelayWebAssetsTest): true only for a GET
 * whose URL is the exact relay origin — scheme, host and port, through
 * [RelayConfig.isRelayUrl] — so the owner's map on another host, a look-alike
 * host, the relay on another port, any CDN and any non-GET all stay with the
 * WebView untouched. [fetch] then performs the request with the shared [http]
 * client (its interceptors attach the relay headers for that origin and strip
 * them anywhere else) and never returns null: a failure becomes an error page,
 * so the WebView shows it instead of retrying the URL without credentials.
 */
object RelayWebAssets {
    private const val GET = "GET"

    /**
     * The shared [http] client's pools and both credential interceptors, but
     * never following a redirect: a WebResourceResponse cannot carry one, and a
     * followed hop would hand another host's body to the WebView as content of
     * the relay origin. A 3xx is an error here (like RelayProbe's client).
     */
    private val proxyHttp by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * Request headers the WebView sends that are not forwarded upstream:
     * Accept-Encoding, because OkHttp negotiates its own compression and decodes
     * it transparently (the WebView's would hand it an encoded body), and the
     * conditional headers, because a 304 cannot be carried in a
     * WebResourceResponse either — upstream must always answer in full.
     */
    private val droppedRequestHeaders = listOf("Accept-Encoding", "If-None-Match", "If-Modified-Since")

    fun shouldProxy(url: String, method: String, config: RelayConfig): Boolean =
        method == GET && config.isRelayUrl(url)

    /** Whether a WebView request header is copied onto the proxied request (pure; RelayWebAssetsTest). */
    fun forwardsRequestHeader(name: String): Boolean =
        droppedRequestHeaders.none { it.equals(name, ignoreCase = true) }

    /** Runs on the WebView's IO thread (shouldInterceptRequest is never called on the UI thread). */
    fun fetch(url: String, requestHeaders: Map<String, String>?, report: (String) -> Unit): WebResourceResponse {
        return try {
            val b = Request.Builder().url(url)
            requestHeaders?.forEach { (name, value) ->
                if (forwardsRequestHeader(name)) b.header(name, value)
            }
            val r = proxyHttp.newCall(b.build()).execute()
            val body = r.body
            // Redirects are not followed (proxyHttp) and cannot be returned: an error page instead.
            if (body == null || r.code in 300..399 || r.code < 100 || r.code > 599) {
                r.close()
                report("RESOURCE PROXY HTTP ${r.code} — ${safeUrl(url)}")
                return errorResponse()
            }
            val type = body.contentType()
            val mime = type?.let { "${it.type}/${it.subtype}" }
            val charset = type?.charset()?.name()
            val headers = r.headers.toMultimap()
                .filterKeys { !it.equals("Content-Length", ignoreCase = true) && !it.equals("Content-Encoding", ignoreCase = true) }
                .mapValues { (_, v) -> v.joinToString(", ") }
            val reason = r.message.ifBlank { if (r.code in 200..299) "OK" else "Error" }
            // The stream closes the response when the WebView is done reading it.
            WebResourceResponse(mime, charset, r.code, reason, headers, body.byteStream())
        } catch (e: Exception) {
            // The exception's own text is never repeated: OkHttp's header errors quote the value.
            report("RESOURCE PROXY ERR ${e.javaClass.simpleName} — ${safeUrl(url)}")
            errorResponse()
        }
    }

    private fun errorResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 502, "Bad Gateway", emptyMap(),
        "The relay request failed.".byteInputStream(),
    )
}

/**
 * The page reads `scheme=dark|light` from its fragment parameters and applies
 * it to the MapKit map ahead of the WebView's own prefers-color-scheme, so the
 * map follows the app's dark setting even where the WebView reports light.
 */
internal fun mapUrlWithCacheBuster(darkTheme: Boolean): String {
    val hashIndex = FAMILY_MAP_URL.indexOf('#')
    val base = if (hashIndex >= 0) FAMILY_MAP_URL.substring(0, hashIndex) else FAMILY_MAP_URL
    val fragment = if (hashIndex >= 0) FAMILY_MAP_URL.substring(hashIndex) else ""
    val separator = if ('?' in base) '&' else '?'
    val scheme = if (darkTheme) "dark" else "light"
    val fragmentWithScheme = when {
        fragment.isEmpty() -> "#scheme=$scheme"
        fragment.length == 1 -> "#scheme=$scheme"          // a bare "#"
        else -> "$fragment&scheme=$scheme"
    }
    return "$base${separator}app_map_rev=$MAP_PAGE_REVISION$fragmentWithScheme"
}

private fun shouldReport(request: WebResourceRequest): Boolean {
    if (request.isForMainFrame) return true
    val host = request.url.host.orEmpty().lowercase()
    return (FAMILY_MAP_HOST.isNotEmpty() && host == FAMILY_MAP_HOST) ||
        host.endsWith(".apple.com") ||
        host == "apple.com" ||
        host.endsWith(".apple-mapkit.com") ||
        host == "apple-mapkit.com"
}

private fun probeMapPage(webView: WebView, report: (String) -> Unit) {
    val script = """
        (() => {
            const map = document.getElementById("map");
            const rect = map ? map.getBoundingClientRect() : null;
            const status = window.__mapStatus ||
                document.documentElement.dataset.mapStatus || "no page status";
            return [
                window.__mapReady === true ? "ready" : "not ready",
                "mapkit=" + typeof window.mapkit,
                "dom=" + document.readyState,
                "title=" + (document.title || "untitled").slice(0, 80),
                "map=" + (rect ? Math.round(rect.width) + "x" + Math.round(rect.height) : "missing"),
                "surface=" + (window.__mapSurfaceStatus || "unknown"),
                status
            ].join(" · ");
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { rawResult ->
        report("Probe: ${decodeJavascriptResult(rawResult)}")
    }
}

private fun decodeJavascriptResult(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return "no result"
    return raw
        .removeSurrounding("\"")
        .replace("\\n", " ")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

// safeUrl() and sanitizeDiagnostic(), which every line above goes through, are in MapDiagnostics.kt.
