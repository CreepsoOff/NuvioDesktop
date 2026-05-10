package com.nuvio.app.desktop

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal object DesktopRuntimeLog {
    private val processId: Long by lazy { ProcessHandle.current().pid() }
    private val logFile: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        localAppData.resolve("Nuvio").resolve("cache").resolve("logs").resolve("desktop-runtime.log")
    }

    @Volatile
    var debugEnabled: Boolean = false

    @Synchronized
    fun initialize() {
        Files.createDirectories(logFile.parent)
        appendLine("")
        appendLine("===== Nuvio desktop startup ${Instant.now()} pid=$processId =====")
    }

    fun installGlobalExceptionHandlers() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("Uncaught exception on thread=${thread.name}", throwable)
            DesktopPlayerRegistry.releaseAll("uncaught:${thread.name}")
        }
        runCatching {
            Toolkit.getDefaultToolkit().systemEventQueue.push(
                object : EventQueue() {
                    override fun dispatchEvent(event: AWTEvent) {
                        try {
                            super.dispatchEvent(event)
                        } catch (throwable: Throwable) {
                            error("Uncaught AWT/EventQueue exception event=${event.javaClass.name}", throwable)
                            DesktopPlayerRegistry.releaseAll("awtException")
                            throw throwable
                        }
                    }
                },
            )
            info("Installed AWT/EventQueue exception logger")
        }.onFailure {
            error("Failed to install AWT/EventQueue exception logger", it)
        }
    }

    @Synchronized
    fun debug(message: String) {
        if (debugEnabled) {
            appendLine("${Instant.now()} DEBUG $message")
        }
    }

    @Synchronized
    fun info(message: String) {
        appendLine("${Instant.now()} INFO  $message")
    }

    @Synchronized
    fun warn(message: String) {
        appendLine("${Instant.now()} WARN  $message")
    }

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        appendLine("${Instant.now()} ERROR $message")
        if (throwable != null) {
            appendLine(stackTrace(throwable))
        }
    }

    fun path(): Path = logFile

    fun processPid(): Long = processId

    @Synchronized
    fun logNonDaemonThreads(tag: String, limit: Int = 40) {
        val entries = Thread.getAllStackTraces().keys
            .filter { it.isAlive && !it.isDaemon }
            .sortedBy { it.name }
            .take(limit)
            .joinToString(separator = " | ") { thread ->
                "name=${thread.name},state=${thread.state}"
            }
        appendLine("${Instant.now()} INFO  nonDaemonThreads tag=$tag pid=$processId count=${Thread.getAllStackTraces().keys.count { it.isAlive && !it.isDaemon }} sample=[$entries]")
    }

    private fun appendLine(line: String) {
        Files.writeString(
            logFile,
            line + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
