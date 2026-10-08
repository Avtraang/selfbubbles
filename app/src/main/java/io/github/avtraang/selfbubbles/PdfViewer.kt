package io.github.avtraang.selfbubbles

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

private val PdfTargetSaver: Saver<PdfTarget?, Any> = Saver(
    save = { savePdfTarget(it) },
    restore = { saved -> restorePdfTarget(saved) { isRelayUrl(it) } },
)

/**
 * Which PDF the viewer is showing, if any. Saved with the screen's state, so
 * rotating the phone (or any other configuration change) keeps it open.
 */
@Composable
fun rememberPdfTarget(): MutableState<PdfTarget?> =
    rememberSaveable(stateSaver = PdfTargetSaver) { mutableStateOf(null) }

private enum class PdfFailure(val message: String) {
    PASSWORD("This PDF is password-protected, so it can't be shown here."),
    DAMAGED("This file is damaged or isn't a PDF."),
    EMPTY("This PDF has no pages."),
    DOWNLOAD("Couldn't download this PDF."),
    TOO_LARGE("This PDF is over 150 MB, which is too large to show here."),
    /** The relay no longer has the file: nothing to retry and nothing to hand to another app. */
    MISSING(ATTACHMENT_MISSING_MESSAGE),
}

private sealed interface PdfState {
    data object Loading : PdfState
    class Ready(val doc: PdfDoc, val file: File) : PdfState
    /** [file] is the downloaded copy when the download itself succeeded. */
    class Failed(val reason: PdfFailure, val file: File?) : PdfState
}

/** Page size in PDF points (1/72 inch). */
private data class PagePt(val width: Int, val height: Int)

private data class PageKey(val index: Int, val viewportWidthPx: Int, val zoomPercent: Int)

private class RenderedPage(val bitmap: Bitmap, val page: PagePt) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * Rendered pages, bounded by bytes (see [ByteBudgetLru]). Evicted bitmaps are
 * dropped, not recycled: a page still on screen may be drawing one.
 */
private class PageCache(budgetBytes: Long) {
    private val policy = ByteBudgetLru<PageKey>(budgetBytes)
    private val pages = HashMap<PageKey, RenderedPage>()

    @Synchronized fun get(key: PageKey): RenderedPage? = if (policy.touch(key)) pages[key] else null

    @Synchronized fun put(key: PageKey, page: RenderedPage) {
        val r = policy.put(key, page.bitmap.allocationByteCount.toLong())
        r.evicted.forEach { pages.remove(it) }
        if (r.accepted) pages[key] = page else pages.remove(key)
    }

    @Synchronized fun clear() {
        policy.clear()
        pages.clear()
    }
}

// Outlives the viewer on purpose: closing a document has to wait for a render
// that is still running, and the composition is gone by then.
private val pdfCloser = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * One open PDF. PdfRenderer allows a single open page and is not thread-safe,
 * so every call into it happens under [gate], off the main thread, and each
 * page is closed in a finally before the next can open.
 */
private class PdfDoc(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageCount: Int,
    val firstPage: PagePt,
) {
    private val gate = Mutex()
    private var closed = false
    private val cacheBudget = pdfCacheBudgetBytes(Runtime.getRuntime().maxMemory())
    private val cache = PageCache(cacheBudget)
    // One page never outgrows the cache, or it could not be kept at all.
    private val maxPagePixels = pdfPagePixelCap(cacheBudget)

    fun cached(key: PageKey): RenderedPage? = cache.get(key)

    /** Null when the document is closed or the page can't be drawn. Cancellable while it waits its turn. */
    suspend fun render(key: PageKey): RenderedPage? = withContext(Dispatchers.IO) {
        gate.withLock {
            if (closed) return@withLock null
            ensureActive()   // the page scrolled away while this waited
            cache.get(key)?.let { return@withLock it }
            try {
                renderLocked(key, shrink = 1f)
            } catch (e: OutOfMemoryError) {
                cache.clear()
                try {
                    renderLocked(key, shrink = 0.5f)
                } catch (again: OutOfMemoryError) {
                    null
                } catch (again: Exception) {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun renderLocked(key: PageKey, shrink: Float): RenderedPage {
        val page = renderer.openPage(key.index)
        try {
            val pt = PagePt(page.width, page.height)
            val size = computeRenderSize(
                pt.width, pt.height, key.viewportWidthPx, key.zoomPercent / 100f, shrink,
                maxPixels = maxPagePixels,
            )
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)   // PDF pages have no background of their own
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return RenderedPage(bitmap, pt).also { cache.put(key, it) }
        } finally {
            page.close()
        }
    }

    /** Waits for an in-flight render, then closes the renderer and the descriptor. Safe to call twice. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        gate.withLock {
            if (!closed) {
                closed = true
                cache.clear()
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }

    fun closeAsync() {
        pdfCloser.launch { close() }
    }
}

/**
 * Ties an open document to the viewer's lifetime even if the viewer goes away
 * while the document is still being opened.
 */
private class DocOwner {
    private var doc: PdfDoc? = null
    private var released = false

    /** False when the viewer is already gone; the caller must close [d] itself. */
    @Synchronized fun adopt(d: PdfDoc): Boolean {
        if (released) return false
        doc?.closeAsync()
        doc = d
        return true
    }

    @Synchronized fun drop() {
        doc?.closeAsync()
        doc = null
    }

    @Synchronized fun release() {
        released = true
        doc?.closeAsync()
        doc = null
    }
}

private suspend fun openPdf(file: File, owner: DocOwner): PdfState =
    // Not cancellable: a renderer opened here must end up either adopted or closed.
    withContext(NonCancellable + Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            if (count <= 0) {
                renderer.close()
                runCatching { pfd.close() }
                return@withContext PdfState.Failed(PdfFailure.EMPTY, file)
            }
            val first = renderer.openPage(0).let { p ->
                try { PagePt(p.width, p.height) } finally { p.close() }
            }
            val doc = PdfDoc(pfd, renderer, count, first)
            if (!owner.adopt(doc)) doc.close()
            PdfState.Ready(doc, file)
        } catch (e: Exception) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            val reason = when (e) {
                is SecurityException -> PdfFailure.PASSWORD
                is IOException -> PdfFailure.DAMAGED
                else -> PdfFailure.DAMAGED   // PdfRenderer also reports broken files as runtime errors
            }
            PdfState.Failed(reason, file)
        }
    }

/**
 * Built-in, read-only PDF viewer for a relay attachment: downloads it into the
 * shared cache, then draws its pages with the platform PdfRenderer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewer(target: PdfTarget, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The dialog window owns back while it is up, so this runs before any handler of the screen beneath.
        BackHandler(onBack = onClose)

        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val fileName = remember(target) { sanitizePdfFileName(target.name) }
        val cacheFolder = remember(target) { pdfCacheDirName(target.url) }
        // True once this viewer has the file. It outlives a rotation, so the viewer
        // that comes back picks up its copy instead of downloading the file again;
        // a viewer opened afresh always downloads.
        var fetched by rememberSaveable(target) { mutableStateOf(false) }
        val owner = remember { DocOwner() }
        var attempt by remember { mutableIntStateOf(0) }
        var state by remember { mutableStateOf<PdfState>(PdfState.Loading) }
        var busy by remember { mutableStateOf(false) }   // a save or an uncapped download is running

        DisposableEffect(Unit) { onDispose { owner.release() } }

        LaunchedEffect(target, attempt) {
            owner.drop()
            state = PdfState.Loading
            val got = Downloads.fetchToCache(
                ctx, target.url, fileName, PDF_MAX_BYTES, subDir = cacheFolder, reuse = fetched,
            )
            state = when (got) {
                is Downloads.CacheFetch.Ok -> {
                    fetched = true
                    openPdf(got.file, owner)
                }
                Downloads.CacheFetch.TooLarge -> PdfState.Failed(PdfFailure.TOO_LARGE, null)
                Downloads.CacheFetch.Missing -> PdfState.Failed(PdfFailure.MISSING, null)
                Downloads.CacheFetch.Failed -> PdfState.Failed(PdfFailure.DOWNLOAD, null)
            }
        }

        val current = state
        val file = when (current) {
            is PdfState.Ready -> current.file
            is PdfState.Failed -> current.file
            PdfState.Loading -> null
        }
        val tooLarge = current is PdfState.Failed && current.reason == PdfFailure.TOO_LARGE

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            TopAppBar(
                title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (current) {
                    PdfState.Loading -> CircularProgressIndicator(
                        strokeWidth = Dimens.progressStroke,
                        modifier = Modifier.semantics { contentDescription = "Loading PDF" },
                    )
                    is PdfState.Ready -> PdfPages(current.doc)
                    is PdfState.Failed -> Column(
                        Modifier.padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            current.reason.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        if (current.reason == PdfFailure.DOWNLOAD) {
                            TextButton(onClick = { attempt++ }) { Text("Retry") }
                        }
                    }
                }
            }

            if (file != null || tooLarge) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // MediaStore.Downloads starts at API 29; on 28 there is no Save button.
                        if (file != null && Build.VERSION.SDK_INT >= 29) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val ok = Downloads.savePdfToDownloads(ctx, file, fileName)
                                            Downloads.toastDownloads(ctx, ok)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Save to Downloads", textAlign = TextAlign.Center) }
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                if (file != null) {
                                    if (!Downloads.openPdfInAnotherApp(ctx, file)) couldNotOpen(ctx)
                                } else {
                                    // Too large for the built-in viewer: fetch it under the second,
                                    // much higher cap and hand it over.
                                    scope.launch {
                                        busy = true
                                        try {
                                            val got = Downloads.fetchToCache(
                                                ctx, target.url, fileName, PDF_HANDOFF_MAX_BYTES,
                                                subDir = cacheFolder,
                                            )
                                            if (got == Downloads.CacheFetch.Missing) {
                                                Toast.makeText(ctx, ATTACHMENT_MISSING_MESSAGE, Toast.LENGTH_SHORT).show()
                                            } else {
                                                val ok = got is Downloads.CacheFetch.Ok &&
                                                    Downloads.openPdfInAnotherApp(ctx, got.file)
                                                if (!ok) couldNotOpen(ctx)
                                            }
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open in another app", textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
}

private fun couldNotOpen(ctx: android.content.Context) {
    Toast.makeText(ctx, "Couldn't open attachment", Toast.LENGTH_SHORT).show()
}

/**
 * Zoom for the page list. The list is laid out [scale] times wider than the
 * viewport, so vertical scrolling and fling stay the list's own; [offsetX] is
 * the horizontal pan. [settled] follows [scale] only when a gesture ends, and
 * is what pages are rendered for.
 */
private class PdfZoom {
    var scale by mutableFloatStateOf(PDF_MIN_ZOOM)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var settled by mutableFloatStateOf(PDF_MIN_ZOOM)
        private set
    var viewportWidthPx = 0

    // The last position asked of the list, and the layout it was worked out
    // from. Until the list has been measured again its own layout is stale, so
    // a further step builds on this instead.
    private var pending: ZoomAnchor? = null
    private var pendingFrom: LazyListLayoutInfo? = null

    private fun maxOffsetX(s: Float) = (viewportWidthPx * (s - 1f)).coerceAtLeast(0f)

    /** Scales to [target] keeping the content under [focus] in place, then applies [pan]. */
    fun zoomTo(target: Float, focus: Offset, pan: Offset, list: LazyListState) {
        val next = target.coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM)
        val factor = next / scale
        val info = list.layoutInfo
        val paddingTop = info.beforeContentPadding.toFloat()
        val laidOut = pending?.takeIf { pendingFrom === info }?.spans
            ?: info.visibleItemsInfo.map { PageSpan(it.index, it.offset + paddingTop, it.size.toFloat()) }
        // The new position is stated in the zoomed layout and applied when the
        // list is next measured, at the new width. Scrolling by a delta here
        // would act on the old layout, where the list may have no room to move.
        val anchor = anchorZoom(laidOut, info.mainAxisItemSpacing.toFloat(), paddingTop, focus.y, pan.y, factor)
        scale = next
        offsetX = ((offsetX + focus.x) * factor - focus.x - pan.x).coerceIn(0f, maxOffsetX(next))
        if (anchor != null) {
            list.requestScrollToItem(anchor.index, anchor.scrollOffset)
            pending = anchor
            pendingFrom = info
        }
    }

    fun panBy(dx: Float) {
        offsetX = (offsetX - dx).coerceIn(0f, maxOffsetX(scale))
    }

    fun toggle(focus: Offset, list: LazyListState) {
        zoomTo(if (scale > PDF_MIN_ZOOM) PDF_MIN_ZOOM else PDF_DOUBLE_TAP_ZOOM, focus, Offset.Zero, list)
        settle()
    }

    /** A gesture ended: pages may now be re-rendered for the zoom it stopped at. */
    fun settle() {
        settled = (scale * 100).roundToInt() / 100f
    }
}

@Composable
private fun PdfPages(doc: PdfDoc) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewportWidthPx = constraints.maxWidth
        val listState = rememberLazyListState()
        val zoom = remember(doc) { PdfZoom() }
        SideEffect { zoom.viewportWidthPx = viewportWidthPx }
        val sizes = remember(doc) { mutableStateMapOf<Int, PagePt>() }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(doc) {
                    awaitEachGesture {
                        // Initial pass: a pinch is taken before the list can scroll with it.
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinched = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                pinched = true
                                zoom.zoomTo(
                                    zoom.scale * event.calculateZoom(),
                                    event.calculateCentroid(useCurrent = false),
                                    event.calculatePan(),
                                    listState,
                                )
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (fingers == 1 && zoom.scale > PDF_MIN_ZOOM) {
                                // One finger while zoomed: sideways here, up and down is the list's.
                                zoom.panBy(event.calculatePan().x)
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinched) zoom.settle()
                    }
                }
                .pointerInput(doc) {
                    detectTapGestures(onDoubleTap = { zoom.toggle(it, listState) })
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val width = (constraints.maxWidth * zoom.scale).roundToInt()
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(-zoom.offsetX.roundToInt(), 0)
                        }
                    }
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PdfPage(doc, index, viewportWidthPx, zoom.settled, sizes)
                }
            }
        }

        val page by remember(doc) {
            derivedStateOf {
                val info = listState.layoutInfo
                val index = currentPdfPage(
                    info.visibleItemsInfo.map { PageSpan(it.index, it.offset.toFloat(), it.size.toFloat()) },
                    info.viewportStartOffset.toFloat(),
                    info.viewportEndOffset.toFloat(),
                    atStart = !listState.canScrollBackward,
                    atEnd = !listState.canScrollForward,
                ) ?: listState.firstVisibleItemIndex
                index + 1
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
            contentColor = MessagesTheme.colors.onScrim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.md)
                .semantics { contentDescription = "Page $page of ${doc.pageCount}" },
        ) {
            Text(
                "$page / ${doc.pageCount}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
    }
}

/**
 * One page: white paper at the page's aspect ratio, filled in once rendered.
 * The render belongs to this item's composition, so it is cancelled when the
 * page scrolls away; a new zoom keeps the old bitmap up until the sharper one
 * is ready.
 */
@Composable
private fun PdfPage(
    doc: PdfDoc,
    index: Int,
    viewportWidthPx: Int,
    zoom: Float,
    sizes: SnapshotStateMap<Int, PagePt>,
) {
    val key = PageKey(index, viewportWidthPx, (zoom * 100).roundToInt())
    var shown by remember(doc, index) { mutableStateOf(doc.cached(key)?.image) }
    LaunchedEffect(doc, key) {
        val page = doc.cached(key) ?: doc.render(key)
        if (page != null) {
            shown = page.image
            sizes[index] = page.page
        }
    }

    val pt = sizes[index] ?: doc.firstPage
    val ratio = if (pt.width > 0 && pt.height > 0) {
        (pt.width.toFloat() / pt.height).coerceIn(MIN_PAGE_RATIO, MAX_PAGE_RATIO)
    } else LETTER_RATIO
    val label = "Page ${index + 1} of ${doc.pageCount}"
    val paper = Modifier.fillMaxWidth().aspectRatio(ratio).background(MessagesTheme.colors.pdfPage)

    val image = shown
    if (image != null) {
        Image(bitmap = image, contentDescription = label, contentScale = ContentScale.FillBounds, modifier = paper)
    } else {
        Box(paper.semantics { contentDescription = label })
    }
}

private const val LETTER_RATIO = 612f / 792f
private const val MIN_PAGE_RATIO = 0.05f   // keeps a degenerate page from becoming an endless strip
private const val MAX_PAGE_RATIO = 20f
