package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsImageDecodeQualityTest {

    private val isWindows: Boolean =
        System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true

    @Test
    fun `desktop sampler stays on FilterQuality_High across platforms`() {
        assertEquals(FilterQuality.High, NuvioImageFilterQuality)
    }

    @Test
    fun `Windows decode dimension follows the desktop 2x quality path`() {
        if (!isWindows) return
        assertEquals(320, nuvioQualityDecodeDimensionPx(150))
        assertEquals(448, nuvioQualityDecodeDimensionPx(212))
        assertEquals(640, nuvioQualityDecodeDimensionPx(314))
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
}
