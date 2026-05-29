package com.nuvio.app.features.home.components

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopGifDecodeTest {
    @Test
    fun `Skia GIF decoder exposes frames and delays`() {
        val decoded = decodeGifForComposeWithSkia(
            bytes = twoFrameGifBytes(),
            target = GifDecodeTarget(widthPx = 32, heightPx = 32),
        )

        assertNotNull(decoded)
        assertEquals(2, decoded.frames.size)
        assertEquals(2, decoded.delaysMs.size)
        assertTrue(decoded.delaysMs.all { it > 0 })
        assertTrue(decoded.approxBytes > 0)
    }

    @Test
    fun `non GIF bytes are ignored`() {
        val decoded = decodeGifForComposeWithSkia(
            bytes = byteArrayOf(0x01, 0x02, 0x03),
            target = GifDecodeTarget(widthPx = 32, heightPx = 32),
        )

        assertEquals(null, decoded)
    }

    private fun twoFrameGifBytes(): ByteArray =
        Base64.getDecoder().decode(
            "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQIDAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQICAAAACwAAAAAAgACAIEAAP8AAAAAAAAAAAAIBgABCAQQEAA7",
        )
}
