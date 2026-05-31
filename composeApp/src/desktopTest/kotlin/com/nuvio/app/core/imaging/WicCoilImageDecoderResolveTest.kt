package com.nuvio.app.core.imaging

import coil3.size.Scale
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit + generative ("property-based") tests for the pure decode-resolution and header-sniffing
 * logic of [WicCoilImageDecoder]:
 *
 *  - [WicResolveTargetSize] — the aspect-ratio-preserving, clamp-to-source target resolver.
 *  - [sniffImageFormat] / [SniffedImageFormat.isWicHandled] — the magic-header routing that decides
 *    which encoded formats go through WIC vs. fall through to the Skia decoder.
 *  - [NuvioImageBridge.isAvailable] — the availability guard that drives the WIC fallback.
 *
 * This module pulls in only `kotlin.test` (no Kotest/jqwik — see tasks.md Notes), so the
 * property-based cases are implemented as deterministic generative loops over a fixed-seed
 * [Random] plus exhaustive concrete cases. Seed "WICR" = 0x57494352 makes every failure
 * reproducible.
 */
class WicCoilImageDecoderResolveTest {

    // ------------------------------------------------------------------------
    // Design Property 5 (Preservation) — No Upscaling Beyond Source.
    //
    // For ANY source size and request box (including box larger than source, smaller,
    // equal, and unconstrained axes passed as <= 0), the resolved target must never
    // exceed the source resolution, for BOTH Scale.FILL and Scale.FIT.
    //
    // **Validates: Requirements 3.2**
    // ------------------------------------------------------------------------

    @Test
    fun `WicResolveTargetSize never upscales beyond source across generated inputs`() {
        val random = Random(seed = ResolveSeed)
        repeat(20_000) {
            val sourceWidth = random.nextInt(1, 6001)
            val sourceHeight = random.nextInt(1, 6001)
            // Box range deliberately spans negatives/zero (unconstrained axes), smaller-than-source,
            // equal, and larger-than-source (the would-be upscale that must be clamped).
            val boxWidth = random.nextInt(-16, 8001)
            val boxHeight = random.nextInt(-16, 8001)

            for (scale in arrayOf(Scale.FILL, Scale.FIT)) {
                val target = WicResolveTargetSize(sourceWidth, sourceHeight, boxWidth, boxHeight, scale)
                assertTrue(
                    target.width <= sourceWidth && target.height <= sourceHeight,
                    "target must not exceed source: source=${sourceWidth}x$sourceHeight " +
                        "box=${boxWidth}x$boxHeight scale=$scale target=${target.width}x${target.height}",
                )
                // A non-degenerate source always resolves to a positive (>= 1) target.
                assertTrue(
                    target.width >= 1 && target.height >= 1,
                    "target must be at least 1px on each axis: target=${target.width}x${target.height}",
                )
            }
        }
    }

    /** Explicit edge: a box strictly larger than the source is clamped to the source (no upscale). */
    @Test
    fun `WicResolveTargetSize clamps a larger box to the source resolution`() {
        // Design Example: a 300px source drawn in a 420px box must stay at 300px.
        for (scale in arrayOf(Scale.FILL, Scale.FIT)) {
            assertEquals(
                WicTargetSize(300, 300),
                WicResolveTargetSize(sourceWidth = 300, sourceHeight = 300, targetWidth = 420, targetHeight = 420, scale = scale),
                "a 300x300 source in a 420x420 box must clamp to 300x300 (scale=$scale)",
            )
        }
    }

    /** Explicit edge: a non-positive source short-circuits to an empty target. */
    @Test
    fun `WicResolveTargetSize returns empty for non-positive source`() {
        assertEquals(WicTargetSize(0, 0), WicResolveTargetSize(0, 100, 50, 50, Scale.FIT))
        assertEquals(WicTargetSize(0, 0), WicResolveTargetSize(100, 0, 50, 50, Scale.FILL))
        assertEquals(WicTargetSize(0, 0), WicResolveTargetSize(-5, -5, 50, 50, Scale.FIT))
    }

    // ------------------------------------------------------------------------
    // Aspect-ratio preservation — Scale.FIT fits inside the box, Scale.FILL covers it.
    // ------------------------------------------------------------------------

    /**
     * Representative hand-picked cases proving FIT fits inside the box and FILL covers it,
     * with the aspect ratio preserved. These exercise poster (2:3), square, and landscape
     * (4:3) sources against square and matching boxes.
     */
    @Test
    fun `WicResolveTargetSize fit and fill behave correctly for representative cases`() {
        // 2:3 poster, box matches aspect -> identical for FIT and FILL.
        assertEquals(WicTargetSize(200, 300), WicResolveTargetSize(1000, 1500, 200, 300, Scale.FIT))
        assertEquals(WicTargetSize(200, 300), WicResolveTargetSize(1000, 1500, 200, 300, Scale.FILL))

        // 2:3 poster into a square box.
        // FIT fits inside (width-bound): factor = min(0.2, 0.1333) = 0.1333 -> 133x200.
        assertEquals(WicTargetSize(133, 200), WicResolveTargetSize(1000, 1500, 200, 200, Scale.FIT))
        // FILL covers (height-bound): factor = max(0.2, 0.1333) = 0.2 -> 200x300.
        assertEquals(WicTargetSize(200, 300), WicResolveTargetSize(1000, 1500, 200, 200, Scale.FILL))

        // 4:3 landscape into a square box.
        // FIT: factor = min(0.5, 0.6667) = 0.5 -> 400x300 (fits inside 400x400).
        assertEquals(WicTargetSize(400, 300), WicResolveTargetSize(800, 600, 400, 400, Scale.FIT))
        // FILL: factor = max(0.5, 0.6667) = 0.6667 -> 533x400 (covers 400x400).
        assertEquals(WicTargetSize(533, 400), WicResolveTargetSize(800, 600, 400, 400, Scale.FILL))

        // Single unconstrained axis (height <= 0): scale is driven by the width alone.
        assertEquals(WicTargetSize(250, 125), WicResolveTargetSize(1000, 500, 250, 0, Scale.FIT))
        // Neither axis constrained: target equals the source.
        assertEquals(WicTargetSize(1000, 500), WicResolveTargetSize(1000, 500, 0, 0, Scale.FILL))
    }

    /**
     * Generative FIT: the resolved target always fits inside the request box on both axes
     * (within rounding), for any positive box. Clamp-to-source only ever shrinks it further.
     */
    @Test
    fun `WicResolveTargetSize FIT fits inside the box across generated inputs`() {
        val random = Random(seed = ResolveSeed)
        repeat(10_000) {
            val sourceWidth = random.nextInt(1, 6001)
            val sourceHeight = random.nextInt(1, 6001)
            val boxWidth = random.nextInt(1, 8001)
            val boxHeight = random.nextInt(1, 8001)

            val target = WicResolveTargetSize(sourceWidth, sourceHeight, boxWidth, boxHeight, Scale.FIT)
            assertTrue(
                target.width <= boxWidth && target.height <= boxHeight,
                "FIT target must fit inside the box: source=${sourceWidth}x$sourceHeight " +
                    "box=${boxWidth}x$boxHeight target=${target.width}x${target.height}",
            )
        }
    }

    /**
     * Generative FILL: when the box fits within the source (a real downscale, so clamp-to-source
     * does not interfere), the resolved target covers the box on both axes (within one rounding
     * pixel) — the FILL/Crop contract.
     */
    @Test
    fun `WicResolveTargetSize FILL covers the box when downscaling`() {
        val random = Random(seed = ResolveSeed)
        repeat(10_000) {
            val sourceWidth = random.nextInt(64, 6001)
            val sourceHeight = random.nextInt(64, 6001)
            // box <= source on both axes -> factor <= 1.0, so no clamp-to-source distortion.
            val boxWidth = random.nextInt(1, sourceWidth + 1)
            val boxHeight = random.nextInt(1, sourceHeight + 1)

            val target = WicResolveTargetSize(sourceWidth, sourceHeight, boxWidth, boxHeight, Scale.FILL)
            assertTrue(
                target.width >= boxWidth - 1 && target.height >= boxHeight - 1,
                "FILL target must cover the box (within 1px): source=${sourceWidth}x$sourceHeight " +
                    "box=${boxWidth}x$boxHeight target=${target.width}x${target.height}",
            )
        }
    }

    /**
     * Generative aspect-ratio preservation: both axes are scaled by a single factor, so the
     * per-axis scale factors agree within the rounding bound (0.5px on each axis). This is the
     * "+/- 1px rounding relative to the source aspect ratio" guarantee, expressed exactly.
     */
    @Test
    fun `WicResolveTargetSize preserves the source aspect ratio across generated inputs`() {
        val random = Random(seed = ResolveSeed)
        repeat(10_000) {
            val sourceWidth = random.nextInt(50, 6001)
            val sourceHeight = random.nextInt(50, 6001)
            val boxWidth = random.nextInt(20, 8001)
            val boxHeight = random.nextInt(20, 8001)

            for (scale in arrayOf(Scale.FILL, Scale.FIT)) {
                val target = WicResolveTargetSize(sourceWidth, sourceHeight, boxWidth, boxHeight, scale)
                // Skip the documented 1px floor (a tiny axis clamped up to 1 is not an aspect violation).
                if (target.width <= 1 || target.height <= 1) continue

                val scaleW = target.width.toDouble() / sourceWidth
                val scaleH = target.height.toDouble() / sourceHeight
                // round() introduces at most 0.5px error per axis -> 0.5/source on each scale factor.
                val tolerance = 0.5 / sourceWidth + 0.5 / sourceHeight + 1e-9
                assertTrue(
                    abs(scaleW - scaleH) <= tolerance,
                    "aspect ratio must be preserved: source=${sourceWidth}x$sourceHeight " +
                        "box=${boxWidth}x$boxHeight scale=$scale target=${target.width}x${target.height} " +
                        "scaleW=$scaleW scaleH=$scaleH tolerance=$tolerance",
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // Header sniff routing — WIC-handled still formats vs. fall-through formats.
    // ------------------------------------------------------------------------

    /** JPEG / PNG / BMP / TIFF / ICO / HEIF route through WIC (isWicHandled == true). */
    @Test
    fun `sniffImageFormat routes WIC-handled still formats`() {
        assertSniff(SniffedImageFormat.JPEG, wicHandled = true, bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10))
        assertSniff(SniffedImageFormat.PNG, wicHandled = true, bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertSniff(SniffedImageFormat.BMP, wicHandled = true, bytes(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00))
        // TIFF little-endian (II*\0) and big-endian (MM\0*).
        assertSniff(SniffedImageFormat.TIFF, wicHandled = true, bytes(0x49, 0x49, 0x2A, 0x00, 0x08))
        assertSniff(SniffedImageFormat.TIFF, wicHandled = true, bytes(0x4D, 0x4D, 0x00, 0x2A, 0x00))
        assertSniff(SniffedImageFormat.ICO, wicHandled = true, bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00))
        // HEIF: 4-byte box size + "ftyp" (offset 4) + brand (offset 8).
        assertSniff(SniffedImageFormat.HEIF, wicHandled = true, heif("heic"))
        assertSniff(SniffedImageFormat.HEIF, wicHandled = true, heif("mif1"))
    }

    /** GIF / WebP / SVG fall through (isWicHandled == false) so animation and vectors are preserved. */
    @Test
    fun `sniffImageFormat falls through for animated and vector formats`() {
        assertSniff(SniffedImageFormat.GIF, wicHandled = false, bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) // "GIF89a"
        assertSniff(SniffedImageFormat.GIF, wicHandled = false, bytes(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) // "GIF87a"
        // WebP: "RIFF" + 4-byte size + "WEBP".
        assertSniff(SniffedImageFormat.WEBP, wicHandled = false, webp())
        // SVG variants: bare "<", "<?xml", "<svg", and BOM + leading whitespace.
        assertSniff(SniffedImageFormat.SVG, wicHandled = false, ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
        assertSniff(SniffedImageFormat.SVG, wicHandled = false, ascii("<?xml version=\"1.0\"?><svg/>"))
        assertSniff(SniffedImageFormat.SVG, wicHandled = false, ascii("<"))
        assertSniff(SniffedImageFormat.SVG, wicHandled = false, ascii("   \n\t  <svg/>"))
        // UTF-8 BOM (EF BB BF) + whitespace + '<'.
        assertSniff(SniffedImageFormat.SVG, wicHandled = false, bytes(0xEF, 0xBB, 0xBF, ' '.code, '\n'.code, '<'.code, 's'.code))
    }

    /** Near-miss and empty headers are UNKNOWN and fall through. */
    @Test
    fun `sniffImageFormat returns UNKNOWN for near-miss and empty headers`() {
        // "RIFF" without the "WEBP" fourcc (e.g. a WAV/AVI container) is not a WebP.
        assertSniff(SniffedImageFormat.UNKNOWN, wicHandled = false, bytes(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x20))
        // Truncated JPEG magic (only FF D8) is not enough to match.
        assertSniff(SniffedImageFormat.UNKNOWN, wicHandled = false, bytes(0xFF, 0xD8))
        // "ftyp" present but with an unrecognized brand -> not HEIF.
        assertSniff(SniffedImageFormat.UNKNOWN, wicHandled = false, heif("qt  "))
        // Empty header.
        assertSniff(SniffedImageFormat.UNKNOWN, wicHandled = false, ByteArray(0))
    }

    /** Generative garbage: random short buffers that match no magic are always UNKNOWN. */
    @Test
    fun `sniffImageFormat returns UNKNOWN for generated garbage`() {
        val random = Random(seed = ResolveSeed)
        repeat(5_000) {
            // Length < 12 so the HEIF "ftyp"-at-offset-4 rule can never match, and a leading 0xAB
            // (not whitespace, not '<', and not the first byte of any recognized magic) guarantees
            // every other rule misses too -> deterministically UNKNOWN.
            val length = random.nextInt(1, 12)
            val buffer = ByteArray(length) { random.nextInt(0, 256).toByte() }
            buffer[0] = 0xAB.toByte()
            assertSniff(SniffedImageFormat.UNKNOWN, wicHandled = false, buffer)
        }
    }

    // ------------------------------------------------------------------------
    // NuvioImageBridge.isAvailable == false fallback path.
    // ------------------------------------------------------------------------

    /**
     * In the desktopTest JVM there is no `NuvioImageBridge.dll` on `java.library.path` (and the
     * test host may not even be Windows), so [NuvioImageBridge.isAvailable] is `false` and the
     * [WicCoilImageDecoder.Factory] short-circuits to `null`, deferring to Coil's default Skia
     * decoder.
     *
     * Rather than constructing a full `SourceFetchResult` / `Options` / `ImageLoader` (heavy and
     * brittle in a pure unit test), this asserts the two observable contracts that DRIVE the
     * fallback, exactly as `Factory.create` checks them in order:
     *   1. `NuvioImageBridge.isAvailable == false`  -> the factory returns null before sniffing.
     *   2. `sniffImageFormat(...).isWicHandled == false` for a non-WIC format -> even if the bridge
     *      were available, a fall-through format would still defer to the default decoder.
     * Together these prove the decode decision defers to the default decoder without throwing.
     */
    @Test
    fun `NuvioImageBridge is unavailable in the test JVM and the decoder defers to the default decoder`() {
        assertFalse(
            NuvioImageBridge.isAvailable,
            "the WIC bridge DLL must not load in the desktopTest JVM (no DLL on java.library.path / non-Windows)",
        )
        // The first guard in Factory.create is `if (!NuvioImageBridge.isAvailable) return null`, so
        // an unavailable bridge means the factory yields the default decoder regardless of format.
        // The second guard routes non-WIC formats to the default decoder as well.
        assertFalse(
            sniffImageFormat(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)).isWicHandled,
            "a GIF must fall through to the default decoder (WIC would only decode the first frame)",
        )
    }

    // ------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------

    private fun assertSniff(expected: SniffedImageFormat, wicHandled: Boolean, header: ByteArray) {
        val actual = sniffImageFormat(header)
        assertEquals(expected, actual, "sniff mismatch for header=${header.toHex()}")
        assertEquals(
            wicHandled,
            actual.isWicHandled,
            "isWicHandled mismatch for $actual (header=${header.toHex()})",
        )
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

    /** A minimal HEIF/ISO-BMFF header: 4-byte box size + "ftyp" + the 4-char [brand] + filler. */
    private fun heif(brand: String): ByteArray {
        require(brand.length == 4) { "brand must be 4 chars" }
        val out = ByteArray(16)
        // Box size (offset 0..3) — value is irrelevant to the sniffer.
        out[3] = 0x10
        "ftyp".forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        brand.forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        return out
    }

    /** A minimal WebP header: "RIFF" + 4-byte size + "WEBP". */
    private fun webp(): ByteArray {
        val out = ByteArray(12)
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        return out
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ", prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        // Fixed seed for reproducible generative loops ("WICR" = 0x57494352).
        const val ResolveSeed = 0x57494352L
    }
}
