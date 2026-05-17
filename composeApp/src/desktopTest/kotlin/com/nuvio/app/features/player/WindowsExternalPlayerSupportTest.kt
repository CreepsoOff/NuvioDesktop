package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsExternalPlayerSupportTest {
    @Test
    fun mpcCommandIncludesResumePosition() {
        val command = buildWindowsExternalPlayerCommand(
            install = install("mpc-hc", "C:/Program Files/MPC-HC/mpc-hc64.exe"),
            request = request(initialPositionMs = 3_723_000L),
        ).command

        assertEquals(
            listOf(
                "C:/Program Files/MPC-HC/mpc-hc64.exe",
                "https://example.test/movie.mkv",
                "/play",
                "/startpos",
                "01:02:03",
            ),
            command,
        )
    }

    @Test
    fun mpcRejectsStreamsThatRequireHeaders() {
        val result = buildWindowsExternalPlayerCommand(
            install = install("mpc-hc", "C:/MPC-HC/mpc-hc64.exe"),
            request = request(sourceHeaders = mapOf("Referer" to "https://example.test")),
        )

        assertNull(result.command)
        assertTrue(result.failureReason?.contains("HTTP headers") == true)
    }

    @Test
    fun mpvCommandCarriesHeadersAudioAndResumePosition() {
        val command = buildWindowsExternalPlayerCommand(
            install = install("mpv", "C:/Tools/mpv/mpv.exe"),
            request = request(
                sourceAudioUrl = "https://example.test/audio.m4a",
                sourceHeaders = mapOf(
                    "Referer" to "https://example.test",
                    "User-Agent" to "Nuvio",
                ),
                initialPositionMs = 90_000L,
            ),
        ).command.orEmpty()

        assertEquals("C:/Tools/mpv/mpv.exe", command.first())
        assertTrue("--force-window=yes" in command)
        assertTrue("--start=90" in command)
        assertTrue("--audio-file=https://example.test/audio.m4a" in command)
        assertTrue("--http-header-fields=Referer: https://example.test,User-Agent: Nuvio" in command)
        assertEquals("https://example.test/movie.mkv", command.last())
    }

    @Test
    fun detectedPlayersFollowPreferredOrder() {
        val detected = detectWindowsExternalPlayers(
            getenv = { key ->
                when (key) {
                    "ProgramFiles" -> "C:/Program Files"
                    "PATH" -> "C:/Tools/mpv;C:/VideoLAN/VLC"
                    else -> null
                }
            },
            fileExists = { path ->
                path == "C:\\Program Files\\MPC-HC\\mpc-hc64.exe" ||
                    path == "C:\\Tools\\mpv\\mpv.exe" ||
                    path == "C:\\VideoLAN\\VLC\\vlc.exe"
            },
        )

        assertEquals(listOf("mpc-hc", "vlc", "mpv"), detected.map { it.definition.id })
    }

    private fun request(
        sourceAudioUrl: String? = null,
        sourceHeaders: Map<String, String> = emptyMap(),
        initialPositionMs: Long = 0L,
    ): ExternalPlayerPlaybackRequest =
        ExternalPlayerPlaybackRequest(
            sourceUrl = "https://example.test/movie.mkv",
            sourceAudioUrl = sourceAudioUrl,
            title = "Movie",
            streamTitle = "1080p",
            sourceHeaders = sourceHeaders,
            initialPositionMs = initialPositionMs,
        )

    private fun install(id: String, path: String): WindowsExternalPlayerInstall {
        val definition = windowsExternalPlayerDefinitions.first { it.id == id }
        return WindowsExternalPlayerInstall(definition, path)
    }
}
