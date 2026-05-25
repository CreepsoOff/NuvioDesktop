package com.nuvio.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDevStreamModeTest {
    @Test
    fun parsesDelimitedHeaderSpec() {
        val headers = parseDevStreamHeaderSpec(
            """
            Authorization: Bearer test-token
            Referer=https://example.test/watch
            User-Agent: Nuvio Smoke
            Range: bytes=0-
            """.trimIndent(),
        )

        assertEquals("Bearer test-token", headers["Authorization"])
        assertEquals("https://example.test/watch", headers["Referer"])
        assertEquals("Nuvio Smoke", headers["User-Agent"])
        assertEquals("bytes=0-", headers["Range"])
    }

    @Test
    fun ignoresInvalidOrBlankHeaderEntries() {
        val headers = parseDevStreamHeaderSpec(
            """
            MissingSeparator
            Bad Header: value
            Empty:
            X-Test: ok
            """.trimIndent(),
        )

        assertEquals(mapOf("X-Test" to "ok"), headers)
    }

    @Test
    fun parsesHeaderEnvironmentVariables() {
        val headers = parseDevStreamHeaderEnvironment(
            mapOf(
                "NUVIO_DEV_STREAM_HEADER_AUTHORIZATION" to "Bearer env-token",
                "NUVIO_DEV_STREAM_HEADER_USER_AGENT" to "Nuvio Env",
                "NUVIO_DEV_STREAM_URL" to "https://example.test/video.m3u8",
            ),
        )

        assertEquals("Bearer env-token", headers["AUTHORIZATION"])
        assertEquals("Nuvio Env", headers["USER-AGENT"])
        assertEquals(2, headers.size)
    }
}
