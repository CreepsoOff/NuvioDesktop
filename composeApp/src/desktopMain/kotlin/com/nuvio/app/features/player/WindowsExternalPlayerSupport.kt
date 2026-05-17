package com.nuvio.app.features.player

import java.io.File
import kotlin.math.roundToLong

internal enum class WindowsExternalPlayerKind {
    Mpc,
    Vlc,
    Mpv,
}

internal data class WindowsExternalPlayerDefinition(
    val id: String,
    val name: String,
    val kind: WindowsExternalPlayerKind,
    val executableNames: List<String>,
    val relativeInstallPaths: List<String>,
)

internal data class WindowsExternalPlayerInstall(
    val definition: WindowsExternalPlayerDefinition,
    val executablePath: String,
)

internal data class WindowsExternalPlayerCommandResult(
    val command: List<String>?,
    val failureReason: String? = null,
)

internal val windowsExternalPlayerDefinitions = listOf(
    WindowsExternalPlayerDefinition(
        id = "mpc-hc",
        name = "MPC-HC",
        kind = WindowsExternalPlayerKind.Mpc,
        executableNames = listOf("mpc-hc64.exe", "mpc-hc.exe"),
        relativeInstallPaths = listOf(
            "MPC-HC/mpc-hc64.exe",
            "MPC-HC/mpc-hc.exe",
            "K-Lite Codec Pack/MPC-HC64/mpc-hc64.exe",
            "K-Lite Codec Pack/MPC-HC64/mpc-hc.exe",
            "K-Lite Codec Pack/MPC-HC/mpc-hc.exe",
        ),
    ),
    WindowsExternalPlayerDefinition(
        id = "mpc-be",
        name = "MPC-BE",
        kind = WindowsExternalPlayerKind.Mpc,
        executableNames = listOf("mpc-be64.exe", "mpc-be.exe"),
        relativeInstallPaths = listOf(
            "MPC-BE x64/mpc-be64.exe",
            "MPC-BE/mpc-be64.exe",
            "MPC-BE/mpc-be.exe",
        ),
    ),
    WindowsExternalPlayerDefinition(
        id = "vlc",
        name = "VLC",
        kind = WindowsExternalPlayerKind.Vlc,
        executableNames = listOf("vlc.exe"),
        relativeInstallPaths = listOf(
            "VideoLAN/VLC/vlc.exe",
        ),
    ),
    WindowsExternalPlayerDefinition(
        id = "mpv",
        name = "mpv",
        kind = WindowsExternalPlayerKind.Mpv,
        executableNames = listOf("mpv.exe"),
        relativeInstallPaths = listOf(
            "mpv/mpv.exe",
        ),
    ),
)

internal fun detectWindowsExternalPlayers(
    getenv: (String) -> String? = System::getenv,
    fileExists: (String) -> Boolean = { File(it).isFile },
): List<WindowsExternalPlayerInstall> =
    windowsExternalPlayerDefinitions.mapNotNull { definition ->
        findWindowsExternalPlayerExecutable(definition, getenv, fileExists)?.let { executable ->
            WindowsExternalPlayerInstall(definition, executable)
        }
    }

internal fun buildWindowsExternalPlayerCommand(
    install: WindowsExternalPlayerInstall,
    request: ExternalPlayerPlaybackRequest,
): WindowsExternalPlayerCommandResult {
    val sourceUrl = request.sourceUrl.trim()
    if (sourceUrl.isBlank()) {
        return WindowsExternalPlayerCommandResult(null, "blank source URL")
    }
    return when (install.definition.kind) {
        WindowsExternalPlayerKind.Mpc -> buildMpcCommand(install.executablePath, request.copy(sourceUrl = sourceUrl))
        WindowsExternalPlayerKind.Vlc -> buildVlcCommand(install.executablePath, request.copy(sourceUrl = sourceUrl))
        WindowsExternalPlayerKind.Mpv -> buildMpvCommand(install.executablePath, request.copy(sourceUrl = sourceUrl))
    }
}

private fun buildMpcCommand(
    executablePath: String,
    request: ExternalPlayerPlaybackRequest,
): WindowsExternalPlayerCommandResult {
    if (request.sourceHeaders.isNotEmpty()) {
        return WindowsExternalPlayerCommandResult(null, "selected player does not support per-stream HTTP headers")
    }
    if (!request.sourceAudioUrl.isNullOrBlank()) {
        return WindowsExternalPlayerCommandResult(null, "selected player does not support separate audio URLs")
    }
    val command = mutableListOf(executablePath, request.sourceUrl, "/play")
    request.initialPositionMs.toMpcStartPosition()?.let { startPosition ->
        command += "/startpos"
        command += startPosition
    }
    return WindowsExternalPlayerCommandResult(command)
}

private fun buildVlcCommand(
    executablePath: String,
    request: ExternalPlayerPlaybackRequest,
): WindowsExternalPlayerCommandResult {
    if (request.sourceHeaders.isNotEmpty()) {
        return WindowsExternalPlayerCommandResult(null, "selected player does not support per-stream HTTP headers")
    }
    if (!request.sourceAudioUrl.isNullOrBlank()) {
        return WindowsExternalPlayerCommandResult(null, "selected player does not support separate audio URLs")
    }
    val command = mutableListOf(executablePath)
    request.initialPositionMs.toStartSeconds()?.let { startSeconds ->
        command += "--start-time=$startSeconds"
    }
    command += request.sourceUrl
    return WindowsExternalPlayerCommandResult(command)
}

private fun buildMpvCommand(
    executablePath: String,
    request: ExternalPlayerPlaybackRequest,
): WindowsExternalPlayerCommandResult {
    val command = mutableListOf(executablePath, "--force-window=yes")
    request.initialPositionMs.toStartSeconds()?.let { startSeconds ->
        command += "--start=$startSeconds"
    }
    request.sourceAudioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
        command += "--audio-file=$audioUrl"
    }
    if (request.sourceHeaders.isNotEmpty()) {
        val headerList = request.sourceHeaders.toMpvHeaderFields()
            ?: return WindowsExternalPlayerCommandResult(null, "selected stream has invalid HTTP headers")
        command += "--http-header-fields=$headerList"
    }
    command += request.sourceUrl
    return WindowsExternalPlayerCommandResult(command)
}

private fun findWindowsExternalPlayerExecutable(
    definition: WindowsExternalPlayerDefinition,
    getenv: (String) -> String?,
    fileExists: (String) -> Boolean,
): String? {
    val installRoots = listOfNotNull(
        getenv("ProgramFiles"),
        getenv("ProgramFiles(x86)"),
        getenv("LOCALAPPDATA"),
    ).distinct()
    installRoots.forEach { root ->
        definition.relativeInstallPaths.forEach { relativePath ->
            val candidate = File(root, relativePath).absolutePath
            if (fileExists(candidate)) return candidate
        }
    }
    val pathEntries = getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        .orEmpty()
    pathEntries.forEach { directory ->
        definition.executableNames.forEach { executableName ->
            val candidate = File(directory, executableName).absolutePath
            if (fileExists(candidate)) return candidate
        }
    }
    return null
}

private fun Long.toMpcStartPosition(): String? {
    if (this <= 0L) return null
    val totalSeconds = (this / 1000.0).roundToLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun Long.toStartSeconds(): Long? =
    (this / 1000.0).roundToLong().takeIf { it > 0L }

private fun Map<String, String>.toMpvHeaderFields(): String? {
    val headers = mapNotNull { (rawName, rawValue) ->
        val name = rawName.trim()
        val value = rawValue.trim()
        if (name.isBlank() || value.isBlank()) return@mapNotNull null
        if (name.any { it == ':' || it == '\r' || it == '\n' }) return null
        if (value.any { it == '\r' || it == '\n' }) return null
        "$name: $value"
    }
    return headers.takeIf { it.isNotEmpty() }?.joinToString(",")
}
