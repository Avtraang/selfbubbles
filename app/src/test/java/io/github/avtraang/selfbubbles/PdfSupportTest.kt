package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure helpers behind the built-in PDF viewer (PdfSupport.kt). */
class PdfSupportTest {

    // ---- PDF detection ----

    @Test fun detect_mime_plainOddCaseAndParameters() {
        assertTrue(isPdfAttachment("application/pdf", null))
        assertTrue(isPdfAttachment("Application/PDF", "scan"))
        assertTrue(isPdfAttachment("application/pdf; charset=binary", "x.bin"))
        assertTrue(isPdfAttachment("  APPLICATION/pdf ;name=\"a.pdf\"", null))
    }

    @Test fun detect_nameOnly() {
        assertTrue(isPdfAttachment(null, "Lease.pdf"))
        assertTrue(isPdfAttachment("", "LEASE.PDF"))
        assertTrue(isPdfAttachment("application/octet-stream", "lease.Pdf"))
        assertTrue(isPdfAttachment("*/*", "lease.pdf  "))
    }

    @Test fun detect_neither() {
        assertFalse(isPdfAttachment("image/jpeg", "photo.jpg"))
        assertFalse(isPdfAttachment("application/zip", "pdf"))
        assertFalse(isPdfAttachment("application/pdfx", "notes.txt"))
        assertFalse(isPdfAttachment("text/application/pdf", "notes.txt"))
        assertFalse(isPdfAttachment("application/x-pdf-like", "mypdf"))
    }

    @Test fun detect_nullAndBlank() {
        assertFalse(isPdfAttachment(null, null))
        assertFalse(isPdfAttachment("", ""))
        assertFalse(isPdfAttachment("   ", "   "))
        assertFalse(isPdfAttachment(null, ""))
    }

    @Test fun detect_pdfDotExe_isNotAPdfByName() {
        assertFalse(isPdfAttachment(null, "invoice.pdf.exe"))
        assertFalse(isPdfAttachment("application/octet-stream", "invoice.pdf.exe"))
        assertFalse(isPdfAttachment("application/x-msdownload", "invoice.PDF.EXE"))
    }

    // ---- name sanitising ----

    private fun assertSafe(name: String) {
        assertTrue(name, name.endsWith(".pdf"))
        assertTrue(name, name.length <= PDF_NAME_MAX_CHARS)
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(name, name.contains('/'))
        assertFalse(name, name.contains('\\'))
        assertFalse(name, name.contains(".."))
        assertFalse(name, name.startsWith("."))
        assertFalse(name, name.any { it.isISOControl() })
        assertTrue(name, name.length > ".pdf".length)
    }

    @Test fun sanitize_traversal() {
        assertEquals("etcpasswd.pdf", sanitizePdfFileName("../../etc/passwd"))
        assertEquals("windowssystem32.pdf", sanitizePdfFileName("..\\..\\windows\\system32.pdf"))
        assertEquals("document.pdf", sanitizePdfFileName(".."))
        assertEquals("document.pdf", sanitizePdfFileName("../.."))
        assertEquals("document.pdf", sanitizePdfFileName("...."))
        // removing ".." must not leave a new ".." behind
        assertSafe(sanitizePdfFileName("a.../...b"))
        assertSafe(sanitizePdfFileName(". . / . ."))
        for (evil in listOf("../x.pdf", "/abs/path.pdf", "..././..././x", "....//x.pdf", "a/../../b.pdf")) {
            assertSafe(sanitizePdfFileName(evil))
        }
    }

    @Test fun sanitize_separatorsAndLeadingDots() {
        assertEquals("abc.pdf", sanitizePdfFileName("a/b\\c.pdf"))
        assertEquals("hidden.pdf", sanitizePdfFileName(".hidden.pdf"))
        assertEquals("hidden.pdf", sanitizePdfFileName("...hidden.pdf"))
        assertEquals("document.pdf", sanitizePdfFileName(".pdf"))
        assertEquals("a.b.pdf", sanitizePdfFileName("a.b.pdf"))
    }

    @Test fun sanitize_controlCharacters() {
        assertEquals("ab.pdf", sanitizePdfFileName("a\u0000b.pdf"))
        assertEquals("line1line2.pdf", sanitizePdfFileName("line1\r\nline2\t.pdf"))
        assertEquals("bell.pdf", sanitizePdfFileName("\u0007bell\u007F\u0085.pdf"))
    }

    @Test fun sanitize_veryLongNames() {
        val long = sanitizePdfFileName("x".repeat(5000) + ".pdf")
        assertEquals(PDF_NAME_MAX_CHARS, long.length)
        assertSafe(long)

        // 3-byte characters: the byte cap binds before the character cap
        val cjk = sanitizePdfFileName("契".repeat(400))
        assertSafe(cjk)
        assertTrue(cjk.startsWith("契"))

        // surrogate pairs are never cut in half
        val emoji = sanitizePdfFileName("📄".repeat(300) + ".pdf")
        assertSafe(emoji)
        val stem = emoji.removeSuffix(".pdf")
        assertFalse(stem.last().isHighSurrogate())
        assertEquals(0, stem.length % 2)
    }

    @Test fun sanitize_empty() {
        assertEquals("document.pdf", sanitizePdfFileName(null))
        assertEquals("document.pdf", sanitizePdfFileName(""))
        assertEquals("document.pdf", sanitizePdfFileName("   "))
        assertEquals("document.pdf", sanitizePdfFileName("/\\/\\"))
        assertEquals("document.pdf", sanitizePdfFileName("\u0001\u0002"))
    }

    @Test fun sanitize_extension() {
        assertEquals("Report.pdf", sanitizePdfFileName("Report.PDF"))
        assertEquals("Report.pdf", sanitizePdfFileName("Report.pdf"))
        assertEquals("Report.pdf", sanitizePdfFileName("Report"))
        assertEquals("Report.docx.pdf", sanitizePdfFileName("Report.docx"))
        assertEquals("invoice.pdf.exe.pdf", sanitizePdfFileName("invoice.pdf.exe"))
    }

    @Test fun sanitize_stripsDirectionControlCharacters() {
        // A right-to-left override can disguise an extension in a file listing.
        assertEquals("evilfdp.exe.pdf", sanitizePdfFileName("evil\u202Efdp.exe"))
        assertEquals("report.pdf", sanitizePdfFileName("re\u200Eport\u2066\u2069.pdf"))
        for (c in listOf('\u061C', '\u200E', '\u200F', '\u202A', '\u202E', '\u2066', '\u2069')) {
            assertFalse(sanitizePdfFileName("a${c}b.pdf").contains(c))
        }
    }

    @Test fun sanitize_unicodeNamesKept() {
        assertEquals("Verträge 2026 – Übersicht.pdf", sanitizePdfFileName("Verträge 2026 – Übersicht.pdf"))
        assertEquals("契約書.pdf", sanitizePdfFileName("契約書.pdf"))
        assertEquals("חוזה שכירות.pdf", sanitizePdfFileName("חוזה שכירות.PDF"))
        assertEquals("📄 scan.pdf", sanitizePdfFileName("📄 scan"))
    }

    // ---- render size ----

    private val letter = 612 to 792
    private val a4 = 595 to 842

    private fun assertWithinCaps(s: RenderSize) {
        assertTrue("$s", s.width >= 1 && s.height >= 1)
        assertTrue("$s", s.width <= PDF_MAX_BITMAP_SIDE && s.height <= PDF_MAX_BITMAP_SIDE)
        assertTrue("$s", s.pixels <= PDF_MAX_BITMAP_PIXELS)
    }

    /** The shorter side matches the longer side times the page's ratio, within one pixel. */
    private fun assertAspect(pw: Int, ph: Int, s: RenderSize) {
        if (pw >= ph) assertTrue("$pw x $ph -> $s", abs(s.height - s.width.toDouble() * ph / pw) <= 1.0)
        else assertTrue("$pw x $ph -> $s", abs(s.width - s.height.toDouble() * pw / ph) <= 1.0)
    }

    @Test fun render_letterAndA4_fitScreenWidthAt1x() {
        for (vw in listOf(720, 1080, 1440)) {
            val l = computeRenderSize(letter.first, letter.second, vw, 1f)
            assertEquals(vw, l.width)
            assertEquals(vw * 792 / 612, l.height)
            val a = computeRenderSize(a4.first, a4.second, vw, 1f)
            assertEquals(vw, a.width)
            assertEquals(vw * 842 / 595, a.height)
        }
    }

    @Test fun render_letterAndA4_acrossWidthsAndZooms() {
        for ((pw, ph) in listOf(letter, a4)) {
            for (vw in listOf(720, 1080, 1344, 1440)) {
                for (zoom in listOf(1f, 1.5f, 2f, 2.5f, 3.3f, 5f)) {
                    val s = computeRenderSize(pw, ph, vw, zoom)
                    assertWithinCaps(s)
                    assertAspect(pw, ph, s)
                    assertTrue(s.width <= (vw * zoom).toInt())
                }
            }
        }
        // 1080 px wide at 2x is under both caps, so it is exact
        assertEquals(RenderSize(2160, 2795), computeRenderSize(612, 792, 1080, 2f))
        // more zoom never yields a smaller bitmap
        var last = 0L
        for (zoom in listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)) {
            val px = computeRenderSize(595, 842, 1080, zoom).pixels
            assertTrue(px >= last)
            last = px
        }
    }

    @Test fun render_zoomIsClamped() {
        assertEquals(computeRenderSize(612, 792, 1080, 1f), computeRenderSize(612, 792, 1080, 0.2f))
        assertEquals(computeRenderSize(612, 792, 1080, 1f), computeRenderSize(612, 792, 1080, -3f))
        assertEquals(computeRenderSize(612, 792, 1080, 1f), computeRenderSize(612, 792, 1080, Float.NaN))
        assertEquals(computeRenderSize(612, 792, 1080, 5f), computeRenderSize(612, 792, 1080, 50f))
        assertEquals(
            computeRenderSize(612, 792, 1080, 5f),
            computeRenderSize(612, 792, 1080, Float.POSITIVE_INFINITY),
        )
    }

    @Test fun render_posterPage_200InchesWide() {
        val pw = 200 * 72            // 14,400 pt
        val ph = 36 * 72
        val at1 = computeRenderSize(pw, ph, 1080, 1f)
        assertEquals(RenderSize(1080, 194), at1)
        val at5 = computeRenderSize(pw, ph, 1080, 5f)
        assertEquals(PDF_MAX_BITMAP_SIDE, at5.width)   // 5400 wanted, side cap binds
        assertWithinCaps(at5)
        assertAspect(pw, ph, at5)

        // a one-inch-high ribbon of the same width still gets a real bitmap
        val ribbon = computeRenderSize(pw, 72, 1080, 1f)
        assertWithinCaps(ribbon)
        assertEquals(1080, ribbon.width)
        assertEquals(5, ribbon.height)
        // and an absurd one never collapses to zero
        val hair = computeRenderSize(1_000_000, 1, 1080, 1f)
        assertEquals(1, hair.height)
        assertWithinCaps(hair)
        val tall = computeRenderSize(1, 1_000_000, 1080, 5f)
        assertEquals(1, tall.width)
        assertWithinCaps(tall)
    }

    @Test fun render_zeroOrNegativePage_neverZeroDimension() {
        for ((pw, ph) in listOf(0 to 0, 0 to 792, 612 to 0, -612 to 792, 612 to -792, -1 to -1)) {
            val s = computeRenderSize(pw, ph, 1080, 2f)
            assertTrue("$pw x $ph -> $s", s.width >= 1 && s.height >= 1)
            assertWithinCaps(s)
        }
        for (vw in listOf(0, -1080)) {
            val s = computeRenderSize(612, 792, vw, 1f)
            assertTrue(s.width >= 1 && s.height >= 1)
        }
    }

    @Test fun render_sideCapBinds() {
        // 4:1 landscape at 5x on 1080: 5400 x 1350 wanted (7.3 MP), so only the side cap applies
        val s = computeRenderSize(2000, 500, 1080, 5f)
        assertEquals(RenderSize(4096, 1024), s)
        assertTrue(s.pixels < PDF_MAX_BITMAP_PIXELS)
        assertAspect(2000, 500, s)
    }

    @Test fun render_megapixelCapBinds() {
        // Letter at 5x on 1440: 7200 x 9318 wanted. The side cap alone would give
        // 3165 x 4096 = 12.96 MP, so the area cap is the one that decides.
        val s = computeRenderSize(612, 792, 1440, 5f)
        assertWithinCaps(s)
        assertTrue(s.height < PDF_MAX_BITMAP_SIDE)
        assertTrue("uses most of the budget: $s", s.pixels > PDF_MAX_BITMAP_PIXELS * 99 / 100)
        assertAspect(612, 792, s)
    }

    @Test fun render_bothCapsAtOnce_squarePage() {
        // a square page with a tiny area cap and a tiny side cap: each is honoured
        val s = computeRenderSize(1000, 1000, 1080, 5f, maxSide = 3000, maxPixels = 4_000_000)
        assertEquals(RenderSize(2000, 2000), s)
        val t = computeRenderSize(1000, 1000, 1080, 5f, maxSide = 1500, maxPixels = 4_000_000)
        assertEquals(RenderSize(1500, 1500), t)
    }

    @Test fun render_aspectAndCaps_sweep() {
        val pages = listOf(letter, a4, 792 to 612, 842 to 595, 1224 to 792, 300 to 3000, 3000 to 300, 14400 to 2592, 1 to 1)
        for ((pw, ph) in pages) for (vw in listOf(320, 720, 1080, 1440, 2160)) for (z in listOf(1f, 2.5f, 5f)) {
            for (shrink in listOf(1f, 0.5f)) {
                val s = computeRenderSize(pw, ph, vw, z, shrink)
                assertWithinCaps(s)
                assertAspect(pw, ph, s)
            }
        }
    }

    @Test fun render_shrinkLowersResolution() {
        val full = computeRenderSize(612, 792, 1080, 2f)
        val half = computeRenderSize(612, 792, 1080, 2f, shrink = 0.5f)
        assertEquals(RenderSize(1080, 1397), half)
        assertTrue(half.pixels * 3 < full.pixels)
        // nonsense shrink values are ignored rather than enlarging or zeroing the bitmap
        assertEquals(full, computeRenderSize(612, 792, 1080, 2f, shrink = 0f))
        assertEquals(full, computeRenderSize(612, 792, 1080, 2f, shrink = 7f))
        assertEquals(full, computeRenderSize(612, 792, 1080, 2f, shrink = Float.NaN))
    }

    // ---- cache policy ----

    @Test fun cache_evictsLeastRecentlyUsedByBytes() {
        val c = ByteBudgetLru<String>(100)
        assertTrue(c.put("a", 40).evicted.isEmpty())
        assertTrue(c.put("b", 40).evicted.isEmpty())
        assertTrue(c.touch("a"))                       // b is now the oldest
        val r = c.put("c", 40)
        assertTrue(r.accepted)
        assertEquals(listOf("b"), r.evicted)
        assertEquals(listOf("a", "c"), c.keys)
        assertEquals(80, c.totalBytes)
        assertFalse(c.touch("b"))
    }

    @Test fun cache_evictsAsManyAsNeeded_oldestFirst() {
        val c = ByteBudgetLru<Int>(100)
        for (i in 1..5) c.put(i, 20)
        assertEquals(100, c.totalBytes)
        val r = c.put(6, 50)
        assertEquals(listOf(1, 2, 3), r.evicted)
        assertEquals(listOf(4, 5, 6), c.keys)
        assertEquals(90, c.totalBytes)
    }

    @Test fun cache_neverExceedsBudget() {
        val budget = 1_000L
        val c = ByteBudgetLru<Int>(budget)
        val held = HashMap<Int, Long>()
        var seed = 12345L
        repeat(5_000) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val key = ((seed ushr 33) % 40).toInt()
            val bytes = ((seed ushr 20) % 1_300) - 50       // includes <= 0 and > budget
            when ((seed ushr 60).toInt() and 3) {
                0 -> { c.touch(key) }
                1 -> { c.remove(key); held.remove(key) }
                else -> {
                    val r = c.put(key, bytes)
                    held.remove(key)
                    r.evicted.forEach { assertTrue(held.remove(it) != null) }
                    if (r.accepted) held[key] = bytes
                }
            }
            assertTrue(c.totalBytes <= budget)
            assertTrue(c.totalBytes >= 0)
            assertEquals(held.values.sum(), c.totalBytes)
            assertEquals(held.keys, c.keys.toSet())
        }
    }

    @Test fun cache_entryLargerThanBudget_isNotCached() {
        val c = ByteBudgetLru<String>(100)
        c.put("a", 60)
        val r = c.put("huge", 101)
        assertFalse(r.accepted)
        assertTrue(r.evicted.isEmpty())                // nothing is thrown out to make room for it
        assertEquals(listOf("a"), c.keys)
        assertEquals(60, c.totalBytes)
        assertFalse(c.touch("huge"))

        assertTrue(c.put("exact", 100).accepted)       // exactly the budget fits, alone
        assertEquals(listOf("exact"), c.keys)
        assertFalse(c.put("zero", 0).accepted)
        assertFalse(c.put("negative", -5).accepted)
        assertFalse(ByteBudgetLru<String>(0).put("x", 1).accepted)
    }

    @Test fun cache_replacingAKey_countsItOnce() {
        val c = ByteBudgetLru<String>(100)
        c.put("a", 70)
        val r = c.put("a", 90)
        assertTrue(r.accepted)
        assertTrue(r.evicted.isEmpty())
        assertEquals(90, c.totalBytes)
        assertEquals(1, c.size)
        // replacing it with something too large drops the stale entry
        assertFalse(c.put("a", 500).accepted)
        assertEquals(0, c.totalBytes)
        assertEquals(0, c.size)
    }

    @Test fun cache_removeAndClear() {
        val c = ByteBudgetLru<String>(100)
        c.put("a", 30); c.put("b", 30)
        assertTrue(c.remove("a"))
        assertFalse(c.remove("a"))
        assertEquals(30, c.totalBytes)
        c.clear()
        assertEquals(0, c.totalBytes)
        assertEquals(0, c.size)
        assertTrue(c.put("c", 100).accepted)
    }

    // ---- cache budget and page size, tied together ----

    @Test fun cache_everyRenderedPageFitsTheBudget() {
        val mb = 1024L * 1024
        for (heap in listOf(128 * mb, 192 * mb, 256 * mb, 384 * mb, 512 * mb)) {
            val budget = pdfCacheBudgetBytes(heap)
            assertEquals(heap / 8, budget)
            val cap = pdfPagePixelCap(budget)
            assertTrue(cap <= PDF_MAX_BITMAP_PIXELS)
            val pages = listOf(letter, a4, 792 to 612, 1224 to 792, 300 to 3000, 14400 to 2592)
            for ((pw, ph) in pages) for (vw in listOf(720, 1080, 1440)) for (z in listOf(1f, 2.5f, 5f)) {
                val s = computeRenderSize(pw, ph, vw, z, maxPixels = cap)
                assertWithinCaps(s)
                assertAspect(pw, ph, s)
                assertTrue("heap $heap: $s", s.bytes <= budget)
                assertTrue("heap $heap: $s", ByteBudgetLru<String>(budget).put("page", s.bytes).accepted)
            }
        }
    }

    @Test fun cache_doubleTapZoomOnA256MbHeap_isCached() {
        // The case that used to miss every time: Letter at 2.5x, 1080 px wide, 32 MB budget.
        val budget = pdfCacheBudgetBytes(256L * 1024 * 1024)
        val uncapped = computeRenderSize(612, 792, 1080, PDF_DOUBLE_TAP_ZOOM)
        assertTrue(uncapped.bytes > budget)
        val s = computeRenderSize(612, 792, 1080, PDF_DOUBLE_TAP_ZOOM, maxPixels = pdfPagePixelCap(budget))
        assertTrue(ByteBudgetLru<Int>(budget).put(0, s.bytes).accepted)
        assertTrue("still well above 2x: $s", s.width > 1080 * 2)
        // with a roomy heap the 12 MP cap is the one that applies
        assertEquals(PDF_MAX_BITMAP_PIXELS, pdfPagePixelCap(pdfCacheBudgetBytes(1024L * 1024 * 1024)))
        assertEquals(1L, pdfPagePixelCap(0))
    }

    // ---- cache folder ----

    @Test fun cacheFolder_differsByUrl_sameName() {
        val a = pdfCacheDirName("https://relay.example/attachment/1")
        val b = pdfCacheDirName("https://relay.example/attachment/2")
        assertFalse(a == b)
        assertEquals(a, pdfCacheDirName("https://relay.example/attachment/1"))
    }

    @Test fun cacheFolder_isAPlainFolderName() {
        for (url in listOf("", "https://relay.example/a/../../b?x=/..\\", "../..", "x".repeat(10_000))) {
            val d = pdfCacheDirName(url)
            assertTrue(d, Regex("pdf-[0-9a-f]{16}").matches(d))
        }
    }

    // ---- download caps ----

    @Test fun handoffCap_isBoundedAndAboveTheViewerCap() {
        assertEquals(150L * 1024 * 1024, PDF_MAX_BYTES)
        assertEquals(1024L * 1024 * 1024, PDF_HANDOFF_MAX_BYTES)
        assertTrue(PDF_HANDOFF_MAX_BYTES > PDF_MAX_BYTES)
        assertTrue(PDF_HANDOFF_MAX_BYTES < Long.MAX_VALUE)
    }

    // ---- viewer state across rotation ----

    @Test fun savedTarget_roundTrips() {
        val relay: (String) -> Boolean = { it.startsWith("https://relay.example/") }
        val t = PdfTarget("https://relay.example/attachment/7", "Lease.pdf")
        assertEquals(t, restorePdfTarget(savePdfTarget(t), relay))
        val unnamed = PdfTarget("https://relay.example/attachment/8", null)
        assertEquals(unnamed, restorePdfTarget(savePdfTarget(unnamed), relay))
        assertEquals(null, savePdfTarget(null))
    }

    @Test fun savedTarget_offRelayOrMalformed_isDropped() {
        val relay: (String) -> Boolean = { it.startsWith("https://relay.example/") }
        assertEquals(null, restorePdfTarget(savePdfTarget(PdfTarget("https://evil.example/x.pdf", "x.pdf")), relay))
        assertEquals(null, restorePdfTarget(null, relay))
        assertEquals(null, restorePdfTarget("https://relay.example/a", relay))
        assertEquals(null, restorePdfTarget(emptyList<String>(), relay))
        assertEquals(null, restorePdfTarget(listOf(42, "x.pdf"), relay))
        assertEquals(
            PdfTarget("https://relay.example/a", null),
            restorePdfTarget(listOf("https://relay.example/a", 42), relay),
        )
    }

    // ---- zoom anchoring ----

    private val gap = 20f
    private val pad = 20f
    private val letterH = 1398f          // Letter, 1080 px wide

    /** Where the point that was under [focusY] ends up on screen after the zoom. */
    private fun focusAfter(before: List<PageSpan>, a: ZoomAnchor, focusY: Float, factor: Float): Float {
        val under = before.last { it.top <= focusY }
        val inPage = focusY - under.top
        return a.spans.first { it.index == under.index }.top + inPage * factor
    }

    @Test fun zoom_singlePageThatFitsTheScreen_keepsTheTappedPoint() {
        // One Letter page in a ~1900 px viewport: the list cannot scroll at 1x.
        val before = listOf(PageSpan(0, pad, letterH))
        val a = anchorZoom(before, gap, pad, focusY = 1000f, panY = 0f, factor = 2.5f)!!
        assertEquals(0, a.index)
        assertEquals(1470, a.scrollOffset)             // 980 px into the page becomes 2450
        assertEquals(1000f, focusAfter(before, a, 1000f, 2.5f), 0.5f)
        // the position describes the zoomed layout: page top = padding - offset
        assertEquals(pad - a.scrollOffset, a.spans[0].top, 0.5f)
        assertEquals(letterH * 2.5f, a.spans[0].size, 0.5f)
    }

    @Test fun zoom_lowerPartOfAPage_staysOnThatPage() {
        val before = listOf(PageSpan(0, pad, letterH), PageSpan(1, pad + letterH + gap, letterH))
        val a = anchorZoom(before, gap, pad, focusY = 1000f, panY = 0f, factor = 2.5f)!!
        assertEquals(0, a.index)                       // used to jump to the top of page 2
        assertEquals(1470, a.scrollOffset)
        assertEquals(1000f, focusAfter(before, a, 1000f, 2.5f), 0.5f)
        // the next page follows at the zoomed height plus the unscaled gap
        assertEquals(a.spans[0].top + letterH * 2.5f + gap, a.spans[1].top, 0.5f)
    }

    @Test fun zoom_namesThePageCrossingTheTopEdge() {
        // focus on page 4 while page 3 still covers the top of the screen
        val before = listOf(PageSpan(3, -1000f, letterH), PageSpan(4, 418f, letterH))
        val a = anchorZoom(before, gap, pad, focusY = 500f, panY = 0f, factor = 1.2f)!!
        assertEquals(500f, focusAfter(before, a, 500f, 1.2f), 0.5f)
        assertEquals(3, a.index)
        assertTrue(a.scrollOffset >= 0)
        assertEquals(pad - a.spans[0].top, a.scrollOffset.toFloat(), 0.5f)
        // zoomed in far enough, page 3 leaves the screen and page 4 is the first one
        val far = anchorZoom(before, gap, pad, focusY = 1700f, panY = 0f, factor = 5f)!!
        assertEquals(4, far.index)
        assertEquals(1700f, focusAfter(before, far, 1700f, 5f), 0.5f)
    }

    @Test fun zoom_out_neverLeavesAGapAboveTheFirstPage() {
        val before = listOf(PageSpan(0, -100f, letterH * 2.5f))
        val a = anchorZoom(before, gap, pad, focusY = 200f, panY = 0f, factor = 0.4f)!!
        assertEquals(0, a.index)
        assertEquals(0, a.scrollOffset)
        assertEquals(pad, a.spans[0].top, 0.5f)
    }

    @Test fun zoom_panOnly_movesTheContentWithTheFingers() {
        val before = listOf(PageSpan(2, -300f, letterH), PageSpan(3, -300f + letterH + gap, letterH))
        val a = anchorZoom(before, gap, pad, focusY = 500f, panY = -40f, factor = 1f)!!
        assertEquals(2, a.index)
        assertEquals(360, a.scrollOffset)              // 320 past the top, plus 40 more
        assertEquals(before[0].size, a.spans[0].size, 0f)
    }

    @Test fun zoom_focusInTheGap_gapKeepsItsSize() {
        val before = listOf(PageSpan(0, pad, 800f), PageSpan(1, pad + 800f + gap, 800f))
        val focus = pad + 800f + 10f                   // halfway through the gap
        val a = anchorZoom(before, gap, pad, focus, panY = 0f, factor = 2f)!!
        assertEquals(focus - 10f, a.spans[0].top + a.spans[0].size, 0.5f)
        assertEquals(focus + 10f, a.spans[1].top, 0.5f)
    }

    @Test fun zoom_twoStepsBeforeARelayout_equalOneStep() {
        // Two pinch events in one frame: the second builds on the first's layout.
        val before = listOf(PageSpan(1, -200f, letterH), PageSpan(2, -200f + letterH + gap, letterH))
        val one = anchorZoom(before, gap, pad, focusY = 700f, panY = 0f, factor = 1.2f)!!
        val two = anchorZoom(one.spans, gap, pad, focusY = 700f, panY = 0f, factor = 1.25f)!!
        val direct = anchorZoom(before, gap, pad, focusY = 700f, panY = 0f, factor = 1.5f)!!
        assertEquals(direct.index, two.index)
        assertEquals(direct.scrollOffset.toFloat(), two.scrollOffset.toFloat(), 1f)
        assertEquals(700f, focusAfter(before, two, 700f, 1.5f), 1f)
    }

    @Test fun zoom_nothingLaidOutOrBadFactor_isNull() {
        assertEquals(null, anchorZoom(emptyList(), gap, pad, 100f, 0f, 2f))
        val one = listOf(PageSpan(0, pad, letterH))
        assertEquals(null, anchorZoom(one, gap, pad, 100f, 0f, 0f))
        assertEquals(null, anchorZoom(one, gap, pad, 100f, 0f, Float.NaN))
        // a focus above everything laid out falls back to the first page
        assertEquals(0, anchorZoom(one, gap, pad, -500f, 0f, 2f)!!.index)
    }

    // ---- page indicator ----

    private val landscapeH = 834f                      // Letter landscape, 1080 px wide
    private fun column(firstIndex: Int, firstTop: Float, height: Float, count: Int) =
        List(count) { PageSpan(firstIndex + it, firstTop + it * (height + gap), height) }

    @Test fun pill_landscapePages_startAtOne() {
        // 1900 px viewport: pages 1 and 2 are fully on screen, page 3 partly.
        val spans = column(0, 0f, landscapeH, 3)
        assertEquals(0, currentPdfPage(spans, -pad, 1880f, atStart = true, atEnd = false))
        // 16:9 slides, three and a bit on screen
        assertEquals(0, currentPdfPage(column(0, 0f, 607f, 4), -pad, 1880f, atStart = true, atEnd = false))
    }

    @Test fun pill_landscapePages_reachTheLastPage() {
        val spans = column(7, -640f, landscapeH, 3)    // pages 8, 9, 10 of 10 with the list at its end
        assertEquals(9, currentPdfPage(spans, -pad, 1880f, atStart = false, atEnd = true))
    }

    @Test fun pill_inBetween_isThePageFillingMostOfTheScreen() {
        // page 1 half scrolled away, page 2 whole
        assertEquals(1, currentPdfPage(column(0, -400f, landscapeH, 4), -pad, 1880f, false, false))
        // two whole pages: the earlier one
        assertEquals(4, currentPdfPage(column(4, 100f, landscapeH, 2), -pad, 1880f, false, false))
        // portrait: the page covering most of the screen, whichever comes first
        assertEquals(2, currentPdfPage(column(2, -300f, letterH, 2), -pad, 1880f, false, false))
        assertEquals(3, currentPdfPage(column(2, -900f, letterH, 2), -pad, 1880f, false, false))
        // zoomed in: one page is taller than the screen
        assertEquals(5, currentPdfPage(listOf(PageSpan(5, -2000f, 3495f)), -pad, 1880f, false, false))
    }

    @Test fun pill_shortDocumentThatFitsOrNothingLaidOut() {
        // both ends at once: the first page wins
        assertEquals(0, currentPdfPage(column(0, 0f, landscapeH, 2), -pad, 1880f, atStart = true, atEnd = true))
        assertEquals(null, currentPdfPage(emptyList(), -pad, 1880f, atStart = true, atEnd = true))
    }
}
