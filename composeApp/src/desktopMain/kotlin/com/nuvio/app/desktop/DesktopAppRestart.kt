package com.nuvio.app.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * Relaunch the running Desktop app, then terminate the current process.
 *
 * Used by settings that can only be applied cleanly at startup (e.g. the image-rendering
 * mode, which is baked into the Coil [coil3.ImageLoader] and its decoded-bitmap caches when
 * they are built once at launch). A full relaunch guarantees a deterministic clean state
 * rather than trying to rebuild the loader and invalidate caches in place.
 *
 * The new process is spawned detached from this one. The single-instance manager binds a
 * loopback port; this process exits immediately after spawning so that port is released and
 * the freshly launched process can become the primary instance. If the relaunch command
 * cannot be resolved, the app still exits (the user can reopen it) rather than getting stuck.
 */
internal fun restartDesktopApp() {
    val launched = runCatching { launchNewInstance() }
        .onFailure { DesktopRuntimeLog.error("restartDesktopApp: failed to spawn new instance", it) }
        .getOrDefault(false)

    if (!launched) {
        DesktopRuntimeLog.warn("restartDesktopApp: could not relaunch automatically; exiting")
    }
    // Exit the current instance. The spawned process (if any) is independent and will start
    // once this one releases the single-instance port.
    exitProcess(0)
}

private fun launchNewInstance(): Boolean {
    val handle = ProcessHandle.current()
    val info = handle.info()
    val command = info.command().orElse(null)?.takeIf { it.isNotBlank() } ?: return false

    // Rebuild the original argument vector when the JVM exposes it. For the packaged
    // native launcher (Nuvio.exe) this is just the executable; for a `gradle run` /
    // java launch it is `java <jvm args> -cp ... MainKt`, which `commandLine()` captures.
    val fullCommandLine = info.commandLine().orElse(null)
    val processCommand: List<String> = when {
        !fullCommandLine.isNullOrBlank() -> splitCommandLine(fullCommandLine)
        else -> listOf(command) + info.arguments().orElse(emptyArray()).toList()
    }.ifEmpty { listOf(command) }

    val workingDir = System.getProperty("user.dir")?.let(::File)?.takeIf(File::isDirectory)

    DesktopRuntimeLog.info("restartDesktopApp: relaunching exe=${File(command).name} argc=${processCommand.size}")

    ProcessBuilder(processCommand)
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return true
}

/**
 * Minimal command-line splitter honoring double-quoted segments (Windows paths with spaces).
 * Good enough to reconstruct the launcher argv reported by [ProcessHandle.Info.commandLine].
 */
private fun splitCommandLine(commandLine: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (ch in commandLine) {
        when {
            ch == '"' -> inQuotes = !inQuotes
            ch.isWhitespace() && !inQuotes -> {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}
