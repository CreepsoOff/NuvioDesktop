package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopExternalPlaybackWindowController
import com.nuvio.app.desktop.DesktopRuntimeLog
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.CompletableFuture

internal actual object ExternalPlayerPlatform {
    private val detectedPlayers: List<WindowsExternalPlayerInstall> by lazy {
        val players = detectWindowsExternalPlayers()
        DesktopRuntimeLog.info(
            "externalPlayer detection complete count=${players.size} ids=${players.joinToString { it.definition.id }}",
        )
        players
    }

    actual fun defaultPlayerId(): String? =
        detectedPlayers.firstOrNull()?.definition?.id

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        detectedPlayers.map { install ->
            ExternalPlayerApp(
                id = install.definition.id,
                name = install.definition.name,
            )
        }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        DesktopRuntimeLog.info(
            "externalPlayer open requested configuredId=${playerId ?: "none"} " +
                "sourceKind=${request.sourceUrl.safeSourceKind()} sourceKey=${request.sourceUrl.safeSourceKey()} " +
                "headers=${request.sourceHeaders.keys.sorted()} audio=${!request.sourceAudioUrl.isNullOrBlank()} " +
                "initialPositionMs=${request.initialPositionMs.coerceAtLeast(0L)}",
        )
        if (playerId.isNullOrBlank()) {
            DesktopRuntimeLog.warn("externalPlayer open rejected: no configured player")
            return ExternalPlayerOpenResult.NotConfigured
        }
        val knownDefinition = windowsExternalPlayerDefinitions.firstOrNull { it.id == playerId }
            ?: run {
                DesktopRuntimeLog.warn("externalPlayer open rejected: unknown configured id=$playerId")
                return ExternalPlayerOpenResult.NotConfigured
            }
        val install = detectedPlayers.firstOrNull { it.definition.id == playerId }
            ?: run {
                DesktopRuntimeLog.warn("External player unavailable id=${knownDefinition.id}")
                return ExternalPlayerOpenResult.NoPlayerAvailable
            }
        val commandResult = buildWindowsExternalPlayerCommand(install, request)
        val command = commandResult.command
            ?: run {
                DesktopRuntimeLog.warn(
                    "External player launch rejected id=${install.definition.id} reason=${commandResult.failureReason}",
                )
                return ExternalPlayerOpenResult.Failed
            }
        return runCatching {
            val diagnostics = windowsExternalPlayerLaunchDiagnostics(install, request, command)
            DesktopRuntimeLog.info(
                "externalPlayer command prepared id=${diagnostics.playerId} kind=${diagnostics.kind} " +
                    "sourceKind=${diagnostics.sourceKind} sourceKey=${diagnostics.sourceKey} " +
                    "sourceExt=${diagnostics.sourceExtension ?: "none"} headers=${diagnostics.headerNames} " +
                    "audio=${diagnostics.hasSeparateAudio} initialPositionMs=${diagnostics.initialPositionMs} " +
                    "seekNote=${diagnostics.seekSupportNote} command=${diagnostics.commandPreview}",
            )
            val startMs = System.currentTimeMillis()
            val process = ProcessBuilder(command)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            val processPid = runCatching { process.pid() }.getOrNull()
            DesktopRuntimeLog.info(
                "externalPlayer launched id=${install.definition.id} pid=${processPid ?: "unknown"} " +
                    "elapsedLaunchMs=${System.currentTimeMillis() - startMs} executable=${install.executablePath}",
            )
            DesktopExternalPlaybackWindowController.minimizeToTray(install.definition.id, processPid)
            monitorExternalPlayerProcess(process, install.definition.id, startMs)
            ExternalPlayerOpenResult.Opened
        }.getOrElse { throwable ->
            DesktopRuntimeLog.error("External player launch failed id=${install.definition.id}", throwable)
            ExternalPlayerOpenResult.Failed
        }
    }

    private fun monitorExternalPlayerProcess(process: Process, playerId: String, startedAtMs: Long) {
        CompletableFuture.runAsync {
            val pid = runCatching { process.pid() }.getOrNull()
            DesktopRuntimeLog.info("externalPlayer monitor start id=$playerId pid=${pid ?: "unknown"}")
            val exitCode = runCatching { process.waitFor() }
                .onFailure { error ->
                    DesktopRuntimeLog.error(
                        "externalPlayer monitor failed id=$playerId pid=${pid ?: "unknown"}",
                        error,
                    )
                }
                .getOrNull()
            DesktopRuntimeLog.info(
                "externalPlayer exited id=$playerId pid=${pid ?: "unknown"} exitCode=${exitCode ?: "unknown"} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs} progressSync=unavailable",
            )
            DesktopExternalPlaybackWindowController.restoreFromTray("external-player-exit:$playerId")
        }
    }
}

private fun String.safeSourceKind(): String = when {
    startsWith("file:", ignoreCase = true) -> "file-uri"
    startsWith("http://", ignoreCase = true) -> "http"
    startsWith("https://", ignoreCase = true) -> "https"
    else -> "other"
}

private fun String.safeSourceKey(): String =
    hashCode().toUInt().toString(16)
