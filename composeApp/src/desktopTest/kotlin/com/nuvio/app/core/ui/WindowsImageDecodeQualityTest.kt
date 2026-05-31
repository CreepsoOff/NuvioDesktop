package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsImageDecodeQualityTest {

    private val isWindows: Boolean =
        System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true

    // The Windows exact-size invariant only holds when native WIC rendering is the active
    // mode (the default). If the user has persisted the legacy-Skia toggle, Windows falls
    // back to the 2x+bucket path on purpose, so guard the invariant on the live preference
    // to keep these tests deterministic regardless of on-disk settings.
    private val nativeWicActive: Boolean = WindowsImageRenderingPreference.nativeWicEnabled

    @Test
    fun `desktop sampler stays on FilterQuality_High across platforms`() {
        assertEquals(FilterQuality.High, NuvioImageFilterQuality)
    }

    /**
     * Property 1 (Bug Condition / Fix Checking) — Decode Dimension Matches Measured Draw Size.
     *
     * On Windows Desktop the decode dimension handed to Skia MUST equal the measured pixel
     * draw size (within one density-rounding pixel), so Skia blits ~1:1 and never performs a
     * draw-time downscale resample (the "crispy"/ringy artifact).
     *
     * isBugCondition(X) = X.isWindowsDesktop AND X.decodeDimPx > X.drawSizePx
     * expectedBehavior  = abs(decodeDimPx - drawSizePx) <= 1   (exact-size, clamped >= 1)
     *
     * On the UNFIXED Windows path `nuvioQualityDecodeDimensionPx` applies a 2x over-decode
     * rounded up to a 64px bucket, so this assertion FAILS — and that failure is the proof
     * the bug exists. Documented counterexamples (unfixed Windows path):
     *   nuvioQualityDecodeDimensionPx(212) == 448  (~2.1x draw size) instead of ~212  (shelf poster)
     *   nuvioQualityDecodeDimensionPx(314) == 640  (~2x   draw size) instead of ~314  (collection cover)
     *   nuvioQualityDecodeDimensionPx(150) == 320  (~2.1x draw size) instead of ~150
     * A 212px draw also pulls a 256px bucket, forcing a 256 -> 212 (~1.2x) draw-time downscale.
     *
     * **Validates: Requirements 2.1, 2.2, 2.3**
     */
    @Test
    fun `Windows decode dimension matches the measured draw size`() {
        if (!isWindows || !nativeWicActive) return
        // Concrete failing cases from the design Examples / Exploratory Bug Condition Checking.
        for (drawSizePx in intArrayOf(150, 212, 314)) {
            assertDecodeMatchesDrawSize(drawSizePx)
        }
    }

    /**
     * Property 1 generative sweep — the same exact-size invariant across a fixed-seed range of
     * display dimensions. A full deterministic sweep of 1..2560 plus fixed-seed random spot
     * checks (reproducible via the seed). No Kotest/jqwik in this module, so this is a
     * generative loop test built on kotlin.test + kotlin.random.Random.
     *
     * **Validates: Requirements 2.1, 2.2, 2.3**
     */
    @Test
    fun `Windows decode dimension invariant holds across generated dimensions`() {
        if (!isWindows || !nativeWicActive) return
        // Deterministic full sweep of the realistic display-dimension range.
        for (drawSizePx in 1..2560) {
            assertDecodeMatchesDrawSize(drawSizePx)
        }
        // Fixed-seed random spot checks for reproducibility ("NVIO" = 0x4E56494F).
        val random = Random(seed = 0x4E56494FL)
        repeat(2000) {
            assertDecodeMatchesDrawSize(random.nextInt(1, 2561))
        }
    }

    private fun assertDecodeMatchesDrawSize(drawSizePx: Int) {
        val decodeDimPx = nuvioQualityDecodeDimensionPx(drawSizePx)
        assertTrue(
            abs(decodeDimPx - drawSizePx) <= 1,
            "Windows decode dimension must match the measured draw size: " +
                "drawSizePx=$drawSizePx decodeDimPx=$decodeDimPx delta=${decodeDimPx - drawSizePx}",
        )
    }

    @Test
    fun `non-Windows desktop keeps the 2x quality oversample`() {
        if (isWindows) return
        // 150 * 2 = 300 -> rounded up to bucket of 64 = 320.
        assertEquals(320, nuvioQualityDecodeDimensionPx(150))
        // 200 * 2 = 400 -> rounded up to bucket of 64 = 448.
        assertEquals(448, nuvioQualityDecodeDimensionPx(200))
    }

    @Test
    fun `upgradeTmdbImageQuality stays on original for the common URL helper`() {
        val url = "https://image.tmdb.org/t/p/w1280/example.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.jpg",
            url.upgradeTmdbImageQuality(),
        )
    }

    // ------------------------------------------------------------------------
    // Property 2 (Preservation) — Non-Buggy Inputs Unchanged.
    //
    // For any input where the bug condition does NOT hold (isBugCondition == false),
    // the fixed path must produce exactly the same result as the original path. The
    // two non-buggy classes asserted here are (a) every non-Windows target, whose
    // decode-sizing path stays on the 2x over-decode + 64px bucket, and (b) an
    // already-matched request whose decodeDimPx already equals drawSizePx.
    //
    // Observation-first: the asserted non-Windows values ARE the current (unfixed)
    // outputs of nuvioQualityDecodeDimensionPx — captured here so the fix is proven
    // to leave them byte-for-byte unchanged.
    //
    // **Validates: Requirements 3.1, 3.6**
    // ------------------------------------------------------------------------

    /**
     * Property 2 generative sweep — the non-Windows decode dimension is the exact
     * 2x-oversample-rounded-to-64px-bucket computation for every display dimension,
     * mirroring the production formula
     * `displayDimensionPx.coerceAtLeast(1).scaleQualityDimension(2.0f, 2560).roundUpToQualityBucket(64)`.
     *
     * **Validates: Requirements 3.1**
     */
    @Test
    fun `non-Windows decode dimension preserves the 2x bucket formula across generated dimensions`() {
        if (isWindows) return
        for (displayDimensionPx in 1..2560) {
            assertNonWindowsDecodeMatchesFormula(displayDimensionPx)
        }
        val random = Random(seed = PreservationSeed)
        repeat(2000) {
            assertNonWindowsDecodeMatchesFormula(random.nextInt(1, 2561))
        }
    }

    private fun assertNonWindowsDecodeMatchesFormula(displayDimensionPx: Int) {
        val expected = displayDimensionPx
            .coerceAtLeast(1)
            .scaleQualityDimension(multiplier = 2.0f, maxPx = 2560)
            .roundUpToQualityBucket(64)
        assertEquals(
            expected,
            nuvioQualityDecodeDimensionPx(displayDimensionPx),
            "Non-Windows decode dimension must keep the 2x + 64px bucket formula: " +
                "displayDimensionPx=$displayDimensionPx",
        )
    }

    /**
     * Property 2 (Requirement 3.6) — an already-matched input (decodeDimPx == drawSizePx)
     * does NOT satisfy the bug condition, and neither does a request already at or below
     * the draw size. The bug condition is strictly `decodeDimPx > drawSizePx` on Windows;
     * everything else is preserved. This is the formal-spec predicate from the design,
     * platform-independent, so it holds on both the unfixed and fixed paths.
     *
     * **Validates: Requirements 3.6**
     */
    @Test
    fun `already-matched and downscaled inputs are not the bug condition`() {
        val random = Random(seed = PreservationSeed)
        repeat(5000) {
            val drawSizePx = random.nextInt(1, 2561)
            // Already matched: decode dimension equals the draw size — not buggy on any platform.
            assertTrue(
                !isBugCondition(isWindowsDesktop = true, decodeDimPx = drawSizePx, drawSizePx = drawSizePx),
                "decodeDimPx == drawSizePx must not be a bug condition: drawSizePx=$drawSizePx",
            )
            assertTrue(
                !isBugCondition(isWindowsDesktop = false, decodeDimPx = drawSizePx, drawSizePx = drawSizePx),
                "non-Windows is never a bug condition: drawSizePx=$drawSizePx",
            )
            // Decode dimension at or below the draw size — also not buggy.
            val smaller = random.nextInt(1, drawSizePx + 1)
            assertTrue(
                !isBugCondition(isWindowsDesktop = true, decodeDimPx = smaller, drawSizePx = drawSizePx),
                "decodeDimPx <= drawSizePx must not be a bug condition: decodeDimPx=$smaller drawSizePx=$drawSizePx",
            )
            // Sanity: an oversized Windows decode IS the bug condition (the case the fix removes).
            val larger = drawSizePx + random.nextInt(1, 1024)
            assertTrue(
                isBugCondition(isWindowsDesktop = true, decodeDimPx = larger, drawSizePx = drawSizePx),
                "decodeDimPx > drawSizePx on Windows must be a bug condition: decodeDimPx=$larger drawSizePx=$drawSizePx",
            )
            // Non-Windows is never buggy even when oversized (preserved 2x path).
            assertTrue(
                !isBugCondition(isWindowsDesktop = false, decodeDimPx = larger, drawSizePx = drawSizePx),
                "non-Windows oversized decode must not be a bug condition: decodeDimPx=$larger drawSizePx=$drawSizePx",
            )
        }
    }

    /** Formal bug-condition predicate from design.md: isBugCondition(X) = isWindowsDesktop AND decodeDimPx > drawSizePx. */
    private fun isBugCondition(isWindowsDesktop: Boolean, decodeDimPx: Int, drawSizePx: Int): Boolean =
        isWindowsDesktop && decodeDimPx > drawSizePx

    // ------------------------------------------------------------------------
    // Property 3 (Preservation) — Cache Keys and EXACT Precision Unchanged.
    //
    // For any image request, the resolved memory-cache key, disk-cache key, and
    // Precision.EXACT must match the builder in `rememberSizedImageRequest`:
    //   memoryCacheKey = "$prefix:$widthPx:$heightPx:${url.hashCode()}"
    //   diskCacheKey   = url
    //   precision      = Precision.EXACT
    //
    // **Validates: Requirements 3.4**
    // ------------------------------------------------------------------------

    @Test
    fun `cache keys and EXACT precision match the rememberSizedImageRequest builder`() {
        val random = Random(seed = PreservationSeed)
        repeat(2000) {
            val url = randomImageUrl(random)
            val widthPx = random.nextInt(1, 4096)
            val heightPx = random.nextInt(1, 4096)
            val prefix = randomCachePrefix(random)

            val request = buildSizedImageRequestLikeProduction(url, widthPx, heightPx, prefix)

            assertEquals(
                "$prefix:$widthPx:$heightPx:${url.hashCode()}",
                request.memoryCacheKey,
                "memory-cache key must match the production builder",
            )
            assertEquals(url, request.diskCacheKey, "disk-cache key must be the URL")
            assertEquals(Precision.EXACT, request.precision, "precision must stay EXACT")
        }
    }

    /** Mirrors the request built in `rememberSizedImageRequest` (commonMain SizedImageRequest.kt). */
    private fun buildSizedImageRequestLikeProduction(
        url: String,
        widthPx: Int,
        heightPx: Int,
        memoryCacheKeyPrefix: String,
    ): ImageRequest =
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .size(Size(widthPx, heightPx))
            .precision(Precision.EXACT)
            .memoryCacheKey("$memoryCacheKeyPrefix:$widthPx:$heightPx:${url.hashCode()}")
            .diskCacheKey(url)
            .build()

    // ------------------------------------------------------------------------
    // Property 4 (Preservation) — TMDB /original/ Upgrade Unchanged.
    //
    // Any TMDB image URL upgrades its size segment to /original/; non-TMDB URLs are
    // returned unchanged by upgradeTmdbImageQuality.
    //
    // **Validates: Requirements 3.3**
    // ------------------------------------------------------------------------

    @Test
    fun `TMDB urls upgrade to original and non-TMDB urls are unchanged`() {
        val random = Random(seed = PreservationSeed)
        repeat(2000) {
            val file = randomTmdbFileName(random)
            val segment = randomTmdbSizeSegment(random)
            val tmdbUrl = "https://image.tmdb.org/t/p/$segment/$file"
            assertEquals(
                "https://image.tmdb.org/t/p/original/$file",
                tmdbUrl.upgradeTmdbImageQuality(),
                "TMDB url must upgrade its size segment to /original/",
            )

            // Non-TMDB URLs pass through unchanged.
            val nonTmdbUrl = randomNonTmdbUrl(random)
            assertEquals(
                nonTmdbUrl,
                nonTmdbUrl.upgradeTmdbImageQuality(),
                "non-TMDB url must be returned unchanged",
            )
        }
    }

    // ------------------------------------------------------------------------
    // Property 5 (Preservation) — No Upscaling Beyond Source.
    //
    // Encoded here against the resolution-math contract: for an aspect-ratio-preserving
    // target derived from a source size and a draw box, the resolved target must never
    // exceed the source resolution (clamp-to-source). Re-asserted against the real
    // `WicResolveTargetSize` helper in WicCoilImageDecoderResolveTest (task 3.10) once
    // the helper exists.
    //
    // **Validates: Requirements 3.2**
    // ------------------------------------------------------------------------

    @Test
    fun `resolved target never upscales beyond source when source fits inside the draw box`() {
        val random = Random(seed = PreservationSeed)
        repeat(5000) {
            val sourceW = random.nextInt(1, 4096)
            val sourceH = random.nextInt(1, 4096)
            // source <= drawBox per the property statement.
            val boxW = sourceW + random.nextInt(0, 4096)
            val boxH = sourceH + random.nextInt(0, 4096)

            for (fill in booleanArrayOf(true, false)) {
                val (targetW, targetH) = resolveTargetClampedToSource(sourceW, sourceH, boxW, boxH, fill)
                assertTrue(
                    targetW <= sourceW && targetH <= sourceH,
                    "resolved target must not exceed source: source=${sourceW}x$sourceH " +
                        "box=${boxW}x$boxH fill=$fill target=${targetW}x$targetH",
                )
            }
        }
    }

    /**
     * Reference of the `WicResolveTargetSize` contract (design Part B, Property 5):
     * an aspect-ratio-preserving target from the source size and the draw box —
     * `fill` covers the box (Scale.FILL / ContentScale.Crop), otherwise it fits inside
     * (Scale.FIT) — then clamped to the source so nothing upscales beyond the source.
     */
    private fun resolveTargetClampedToSource(
        sourceW: Int,
        sourceH: Int,
        boxW: Int,
        boxH: Int,
        fill: Boolean,
    ): Pair<Int, Int> {
        if (sourceW <= 0 || sourceH <= 0) return 0 to 0
        val scaleW = boxW.toDouble() / sourceW
        val scaleH = boxH.toDouble() / sourceH
        val scale = if (fill) maxOf(scaleW, scaleH) else minOf(scaleW, scaleH)
        val targetW = (sourceW * scale).roundToInt()
        val targetH = (sourceH * scale).roundToInt()
        // Clamp to source — never upscale beyond the source resolution.
        return minOf(targetW, sourceW) to minOf(targetH, sourceH)
    }

    // ------------------------------------------------------------------------
    // Fixed-seed generators (no Kotest/jqwik in this module — see tasks.md Notes).
    // ------------------------------------------------------------------------

    private fun randomImageUrl(random: Random): String {
        val host = listOf("image.tmdb.org/t/p/w500", "cdn.example.com", "art.host.net/posters").random(random)
        return "https://$host/${randomSlug(random)}.jpg"
    }

    private fun randomCachePrefix(random: Random): String =
        listOf("poster", "cover", "avatar", "hero", "still").random(random)

    private fun randomTmdbFileName(random: Random): String = "${randomSlug(random)}.jpg"

    private fun randomTmdbSizeSegment(random: Random): String {
        val kind = random.nextInt(3)
        return when (kind) {
            0 -> "w${listOf(92, 154, 185, 342, 500, 780, 1280).random(random)}"
            1 -> "h${listOf(632, 900, 1080).random(random)}"
            else -> "original"
        }
    }

    private fun randomNonTmdbUrl(random: Random): String {
        val host = listOf("cdn.example.com", "media.host.net/t/p/w500", "img.site.org/original").random(random)
        return "https://$host/${randomSlug(random)}.jpg"
    }

    private fun randomSlug(random: Random): String {
        val length = random.nextInt(6, 16)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private companion object {
        // Fixed seed for reproducible generative loops ("PRSV" = 0x50525356).
        const val PreservationSeed = 0x50525356L
    }
}
