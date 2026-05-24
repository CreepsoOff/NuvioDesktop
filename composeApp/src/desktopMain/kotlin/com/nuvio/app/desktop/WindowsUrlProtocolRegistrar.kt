package com.nuvio.app.desktop

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

internal object WindowsUrlProtocolRegistrar {
    private const val SchemeName = "nuvio"
    private const val RootKey = "HKCU\\Software\\Classes\\nuvio"
    private const val CommandKey = "$RootKey\\shell\\open\\command"
    private const val DefaultIconKey = "$RootKey\\DefaultIcon"
    /** Env var passed only to PowerShell subprocess for `[shell]/open/command` writes (avoids `reg.exe` quoting bugs). */
    private const val NuvioExePathEnv = "NUVIO_EXE_PATH"

    internal data class EnsureResult(
        val success: Boolean,
        val message: String,
        val diagnostics: List<String> = emptyList(),
    )

    fun ensureRegisteredForCurrentExecutable(): EnsureResult {
        if (!isWindows()) {
            return EnsureResult(success = true, message = "skip non-Windows", diagnostics = listOf("os=non-windows"))
        }

        val executable = currentExecutablePath()
            ?: return EnsureResult(
                success = false,
                message = "Unable to determine current executable path",
                diagnostics = listOf("currentExecutablePath=<null>"),
            )
        val escapedExecutable = executable.replace("\"", "\\\"")
        val expectedCommand = "\"$escapedExecutable\" \"%1\""
        val expectedIcon = "\"$escapedExecutable\",0"

        val currentCommand = queryDefaultValue(CommandKey)
        val needsRepair = currentCommand == null || !sameCommand(currentCommand, expectedCommand)
        val diagnostics = mutableListOf(
            "currentExecutablePath=${DesktopRuntimeLog.safePath(executable)}",
            "currentCommand=${currentCommand?.redactCommandForLog(executable) ?: "<missing>"}",
            "expectedCommand=${expectedCommand.redactCommandForLog(executable)}",
            "needsRepair=$needsRepair",
        )
        if (!needsRepair) {
            return EnsureResult(
                success = true,
                message = "$SchemeName protocol already valid",
                diagnostics = diagnostics,
            )
        }

        val steps = listOf(
            "RootKey" to regAddDefault(RootKey, "URL:Nuvio Protocol"),
            "URL Protocol" to regAddNamed(RootKey, "URL Protocol", ""),
            "DefaultIcon" to regAddDefault(DefaultIconKey, expectedIcon),
            // reg.exe rejects / mangled quoting for `"<exe>" "%1"` — use PowerShell like manual setup.
            "shell/open/command" to setShellOpenCommandViaPowerShell(executable),
        )
        steps.forEach { (label, result) ->
            diagnostics += "write $label success=${result.success} message=${result.message}"
        }
        val firstFailure = steps.firstOrNull { (_, result) -> !result.success }
        if (firstFailure != null) {
            return EnsureResult(
                success = false,
                message = "Failed to register $SchemeName protocol: ${firstFailure.second.message}",
                diagnostics = diagnostics,
            )
        }
        return EnsureResult(
            success = true,
            message = "$SchemeName protocol registered for current executable",
            diagnostics = diagnostics,
        )
    }

    private fun regAddDefault(key: String, value: String): RegResult =
        runReg("add", key, "/ve", "/t", "REG_SZ", "/d", value, "/f")

    private fun regAddNamed(key: String, name: String, value: String): RegResult =
        runReg("add", key, "/v", name, "/t", "REG_SZ", "/d", value, "/f")

    private fun queryDefaultValue(key: String): String? {
        val result = runReg("query", key, "/ve")
        if (!result.success) return null
        val line = result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("REG_SZ", ignoreCase = true) }
            ?: return null
        val marker = "REG_SZ"
        val markerIndex = line.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null
        return line.substring(markerIndex + marker.length).trim().ifBlank { null }
    }

    private fun sameCommand(actual: String, expected: String): Boolean {
        val normalizedActual = actual.trim().lowercase(Locale.US)
        val normalizedExpected = expected.trim().lowercase(Locale.US)
        return normalizedActual == normalizedExpected
    }

    private fun String.redactCommandForLog(executable: String): String =
        replace(executable, DesktopRuntimeLog.safePath(executable), ignoreCase = true)

    private fun currentExecutablePath(): String? {
        val candidate = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.absoluteFile
        if (candidate == null) return null
        val executableName = candidate.name.lowercase(Locale.US)
        if (executableName == "java.exe" || executableName == "javaw.exe") {
            // In packaged Desktop builds we need the launcher path, not the embedded runtime.
            val launcherCandidate = candidate.parentFile?.parentFile?.resolve("Nuvio.exe")
            if (launcherCandidate != null && launcherCandidate.exists()) {
                return launcherCandidate.absolutePath
            }
        }
        return candidate.absolutePath
    }

    private data class RegResult(
        val success: Boolean,
        val output: String,
        val message: String,
    )

    private fun runReg(vararg args: String): RegResult {
        return runCatching {
            val process = ProcessBuilder(listOf("reg", *args))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                RegResult(success = true, output = output, message = output)
            } else {
                RegResult(success = false, output = output, message = "reg exit=$exitCode output=$output")
            }
        }.getOrElse { throwable ->
            RegResult(success = false, output = "", message = throwable.message ?: throwable::class.simpleName.orEmpty())
        }
    }

    /**
     * Sets the default REG_SZ under `HKCU:\Software\Classes\nuvio\shell\open\command` to `"<exe>" "%1"`.
     * Passes [executableAbsolutePath] via env [NuvioExePathEnv] so PowerShell avoids fragile `reg.exe` quoting.
     */
    private fun setShellOpenCommandViaPowerShell(
        executableAbsolutePath: String,
    ): RegResult {
        // UTF-16LE + Base64 avoids fragile `-Command`/quoting; env carries the exe path (may contain `\`, spaces).
        val psScript = """
            ${'$'}p = 'HKCU:\Software\Classes\nuvio\shell\open\command'
            ${'$'}exe = ${'$'}env:$NuvioExePathEnv
            if ([string]::IsNullOrWhiteSpace(${'$'}exe)) {
                Write-Output 'powershell: missing env $NuvioExePathEnv'
                exit 3
            }
            ${'$'}value = ('"{0}" "%1"' -f ${'$'}exe)
            New-Item -Path ${'$'}p -Force | Out-Null
            Set-ItemProperty -LiteralPath ${'$'}p -Name '(default)' -Value ${'$'}value
        """.trimIndent().replace("\n", "\r\n")
        val encoded = Base64.getEncoder().encodeToString(psScript.toByteArray(StandardCharsets.UTF_16LE))

        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encoded,
            )
                .apply {
                    environment()[NuvioExePathEnv] = executableAbsolutePath
                }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                RegResult(success = true, output = output, message = if (output.isBlank()) "powershell ok" else output)
            } else {
                RegResult(success = false, output = output, message = "powershell exit=$exitCode output=$output")
            }
        }.getOrElse { throwable ->
            RegResult(success = false, output = "", message = throwable.message ?: throwable::class.simpleName.orEmpty())
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase(Locale.US)?.contains("windows") == true
}
