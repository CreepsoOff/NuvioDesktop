package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopRuntimeLog

internal actual object ExternalPlayerPlatform {
    private val detectedPlayers: List<WindowsExternalPlayerInstall> by lazy {
        detectWindowsExternalPlayers()
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
        if (playerId.isNullOrBlank()) return ExternalPlayerOpenResult.NotConfigured
        val knownDefinition = windowsExternalPlayerDefinitions.firstOrNull { it.id == playerId }
            ?: return ExternalPlayerOpenResult.NotConfigured
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
            ProcessBuilder(command).start()
            DesktopRuntimeLog.info("External player launched id=${install.definition.id} executable=${install.executablePath}")
            ExternalPlayerOpenResult.Opened
        }.getOrElse { throwable ->
            DesktopRuntimeLog.error("External player launch failed id=${install.definition.id}", throwable)
            ExternalPlayerOpenResult.Failed
        }
    }
}
