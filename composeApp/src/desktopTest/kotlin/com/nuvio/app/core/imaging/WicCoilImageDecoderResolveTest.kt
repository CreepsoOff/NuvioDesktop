package com.nuvio.app.core.imaging

import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the aspect-ratio-preserving target sizing the WIC decoder
 * uses. Without this, callers that pass a box-shaped target (e.g. a
 * `Size(1024, 256)` hero for a 16:9 backdrop) would have their image
 * squashed because WIC scales to the exact requested dimensions.
 */
class WicCoilImageDecoderResolveTest {

    private fun resolve(
        size: Size,
        scale: Scale,
        sourceW: Int,
        sourceH: Int,
    ): Pair<Int, Int> = WicResolveTargetSize.resolve(size, scale, sourceW, sourceH)

    @Test
    fun `FILL on a 16-9 backdrop resampled into a wider-than-source box covers the box`() {
        // Source 1280x720 (16:9). Target 1024x256 (4:1) with FILL.
        // Width ratio = 1024/1280 = 0.8; height ratio = 256/720 = 0.355.
        // FILL picks the larger ratio (0.8) so the output covers the
        // box on both axes.
        // 1280 * 0.8 = 1024, 720 * 0.8 = 576. Output is 1024x576 — wider
        // than the box's height, which is correct for ContentScale.Crop.
        val (w, h) = resolve(
            size = Size(Dimension(1024), Dimension(256)),
            scale = Scale.FILL,
            sourceW = 1280,
            sourceH = 720,
        )
        assertEquals(1024 to 576, w to h)
    }

    @Test
    fun `FIT on a 2-3 poster into a square box letterboxes inside the box`() {
        // Source 800x1200 (2:3). Target 400x400 with FIT.
        // Width ratio = 0.5; height ratio = 0.333.
        // FIT picks the smaller ratio so the output fits inside the box.
        // 800 * 0.333 = 266.7 -> 267, 1200 * 0.333 = 400.
        val (w, h) = resolve(
            size = Size(Dimension(400), Dimension(400)),
            scale = Scale.FIT,
            sourceW = 800,
            sourceH = 1200,
        )
        assertEquals(267 to 400, w to h)
    }

    @Test
    fun `FILL on a square source into a square box returns the box`() {
        val (w, h) = resolve(
            size = Size(Dimension(256), Dimension(256)),
            scale = Scale.FILL,
            sourceW = 1024,
            sourceH = 1024,
        )
        assertEquals(256 to 256, w to h)
    }

    @Test
    fun `only-width specified preserves source aspect`() {
        val (w, h) = resolve(
            size = Size(Dimension(640), Dimension.Undefined),
            scale = Scale.FIT,
            sourceW = 1280,
            sourceH = 720,
        )
        assertEquals(640 to 360, w to h)
    }

    @Test
    fun `only-height specified preserves source aspect`() {
        val (w, h) = resolve(
            size = Size(Dimension.Undefined, Dimension(360)),
            scale = Scale.FIT,
            sourceW = 1280,
            sourceH = 720,
        )
        assertEquals(640 to 360, w to h)
    }

    @Test
    fun `undefined-undefined keeps source size untouched`() {
        val (w, h) = resolve(
            size = Size(Dimension.Undefined, Dimension.Undefined),
            scale = Scale.FILL,
            sourceW = 1280,
            sourceH = 720,
        )
        assertEquals(1280 to 720, w to h)
    }
}
