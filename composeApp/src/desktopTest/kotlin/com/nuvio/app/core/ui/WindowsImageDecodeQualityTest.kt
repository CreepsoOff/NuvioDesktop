package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsImageDecodeQualityTest {

    private val isWindows: Boolean =
        System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true

    @Test
    fun `desktop sampler stays on FilterQuality_High across platforms`() {
        // The Windows-specific aliasing problem is fixed by the WIC
        // decoder (NuvioImageBridge.dll), which delivers Skia a bitmap
        // already at the target size. The Compose draw-time sampler
        // therefore operates on a 1:1 image and the FilterQuality knob
        // is no longer the dominant factor.
        assertEquals(FilterQuality.High, NuvioImageFilterQuality)
    }

    @Test
    fun `Windows decode dimension is exact display size`() {
        if (!isWindows) return
        // No bucket rounding on Windows: WIC delivers Skia a bitmap at
        // the precise pixel target. Even a small bucket round-up would
        // force Skia to redownscale ~1.2x at draw time using Mitchell on
        // the OpenGL backend — visible as the "crispy" poster artifacts
        // inside large card presets ("Grand" = 212dp). Exact size means
        // Skia does a 1:1 blit with no resampling.
        assertEquals(150, nuvioQualityDecodeDimensionPx(150))
        assertEquals(212, nuvioQualityDecodeDimensionPx(212))
        assertEquals(314, nuvioQualityDecodeDimensionPx(314))
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
    fun `tmdbBucketedUrl picks the smallest bucket that covers the target with 1_5x headroom`() {
        val src = "https://image.tmdb.org/t/p/w1280/example.jpg"

        // 130 px shelf card * 1.5 = 195, smallest bucket >= 195 is 342.
        assertEquals(
            "https://image.tmdb.org/t/p/w342/example.jpg",
            src.tmdbBucketedUrl(130),
        )
        // 260 px tablet shelf * 1.5 = 390, smallest bucket >= 390 is 500.
        assertEquals(
            "https://image.tmdb.org/t/p/w500/example.jpg",
            src.tmdbBucketedUrl(260),
        )
        // Hero / backdrop sized targets keep escalating up to original.
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.jpg",
            src.tmdbBucketedUrl(2400),
        )
    }

    @Test
    fun `tmdbBucketedUrl is a no-op for non-TMDB hosts`() {
        val url = "https://example.com/images/w1280/example.jpg"
        assertEquals(url, url.tmdbBucketedUrl(130))
    }

    @Test
    fun `upgradeTmdbImageQuality stays on original for the common URL helper`() {
        val url = "https://image.tmdb.org/t/p/w1280/example.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.jpg",
            url.upgradeTmdbImageQuality(),
        )
    }
}
