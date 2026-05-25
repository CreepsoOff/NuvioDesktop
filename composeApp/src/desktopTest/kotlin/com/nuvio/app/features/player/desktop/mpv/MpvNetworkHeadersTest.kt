package com.nuvio.app.features.player.desktop.mpv

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvNetworkHeadersTest {
    @Test
    fun combinesHeadersForSingleMpvListOption() {
        val fields = mpvHttpHeaderFields(
            linkedMapOf(
                "Authorization" to "Bearer smoke-token",
                "X-Nuvio-Smoke" to "header-ok",
            ),
        )

        assertEquals("Authorization: Bearer smoke-token,X-Nuvio-Smoke: header-ok", fields)
    }

    @Test
    fun excludesUserAgentAndRangeFromHeaderFields() {
        val fields = mpvHttpHeaderFields(
            linkedMapOf(
                "User-Agent" to "Nuvio",
                "Range" to "bytes=0-",
                "Referer" to "https://example.test/watch",
            ),
        )

        assertEquals("Referer: https://example.test/watch", fields)
    }

    @Test
    fun returnsNullForEmptyHeaderFields() {
        val fields = mpvHttpHeaderFields(
            linkedMapOf(
                "User-Agent" to "Nuvio",
                "Range" to "bytes=0-",
            ),
        )

        assertEquals(null, fields)
    }
}
