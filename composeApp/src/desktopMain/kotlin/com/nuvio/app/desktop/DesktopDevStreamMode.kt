package com.nuvio.app.desktop

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.parseAppDeepLink
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.max

private const val DevStreamHeadersEnv = "NUVIO_DEV_STREAM_HEADERS"
private const val DevStreamHeadersProperty = "nuvio.dev.stream.headers"
private const val DevStreamHeaderEnvPrefix = "NUVIO_DEV_STREAM_HEADER_"
private val DevStreamHeaderNamePattern = Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""")

internal data class DesktopDevStreamMode(
    val launch: PlayerLaunch,
    val screenshotDirectory: Path?,
    val screenshotDelayMs: Long,
    val performanceSampleIntervalMs: Long,
    val performanceSampleCount: Int,
) {
    companion object {
        fun from(args: Array<String>, startupUrls: List<String>): DesktopDevStreamMode? {
            val values = args.parseDevStreamArgs()
            val envUrl = System.getenv("NUVIO_DEV_STREAM_URL")?.trim()?.takeIf { it.isNotBlank() }
            val propUrl = System.getProperty("nuvio.dev.stream.url")?.trim()?.takeIf { it.isNotBlank() }
            val explicitUrl = values["url"] ?: propUrl ?: envUrl
            val deepLink = startupUrls
                .asSequence()
                .mapNotNull { parseAppDeepLink(it) as? AppDeepLink.DevStream }
                .firstOrNull()

            val launch = when {
                deepLink != null -> deepLink.toPlayerLaunch()
                explicitUrl != null -> {
                    val devStreamHeaders = buildMap {
                        putAll(parseDevStreamHeaderSpec(System.getenv(DevStreamHeadersEnv)))
                        putAll(parseDevStreamHeaderSpec(System.getProperty(DevStreamHeadersProperty)))
                        putAll(parseDevStreamHeaderEnvironment(System.getenv()))
                        putAll(values.headers())
                    }
                    val title = values["title"]
                        ?: System.getProperty("nuvio.dev.stream.title")
                        ?: System.getenv("NUVIO_DEV_STREAM_TITLE")
                        ?: "Dev stream"
                    PlayerLaunch(
                        title = title,
                        sourceUrl = explicitUrl,
                        sourceAudioUrl = values["audio-url"]
                            ?: System.getProperty("nuvio.dev.stream.audioUrl")
                            ?: System.getenv("NUVIO_DEV_STREAM_AUDIO_URL"),
                        sourceHeaders = sanitizePlaybackHeaders(devStreamHeaders),
                        poster = values["poster"] ?: System.getenv("NUVIO_DEV_STREAM_POSTER"),
                        background = values["background"] ?: System.getenv("NUVIO_DEV_STREAM_BACKGROUND"),
                        streamTitle = values["stream-title"] ?: title,
                        streamSubtitle = "Custom dev stream",
                        providerName = values["provider"] ?: "Dev stream",
                        providerAddonId = "dev-stream",
                        contentType = values["type"] ?: "movie",
                        videoId = values["video-id"] ?: "dev-stream",
                        parentMetaId = values["parent-meta-id"] ?: values["video-id"] ?: "dev-stream",
                        parentMetaType = values["parent-meta-type"] ?: values["type"] ?: "movie",
                    )
                }
                else -> null
            } ?: return null

            return DesktopDevStreamMode(
                launch = launch,
                screenshotDirectory = (values["screenshot-dir"] ?: System.getenv("NUVIO_DEV_STREAM_SCREENSHOT_DIR"))
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of),
                screenshotDelayMs = (values["screenshot-delay-ms"] ?: System.getenv("NUVIO_DEV_STREAM_SCREENSHOT_DELAY_MS"))
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1_000L)
                    ?: 8_000L,
                performanceSampleIntervalMs = (
                    values["perf-sample-ms"]
                        ?: System.getenv("NUVIO_DEV_STREAM_PERF_SAMPLE_MS")
                        ?: System.getenv("NUVIO_DEV_STREAM_PERF_SAMPLE_INTERVAL_MS")
                    )
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1_000L)
                    ?: 5_000L,
                performanceSampleCount = (values["perf-sample-count"] ?: System.getenv("NUVIO_DEV_STREAM_PERF_SAMPLE_COUNT"))
                    ?.toIntOrNull()
                    ?.coerceIn(1, 240)
                    ?: 48,
            )
        }
    }

    fun startDiagnostics(windowProvider: () -> Window?) {
        DesktopRuntimeLog.debugEnabled = true
        DesktopRuntimeLog.info(
            "devStream mode enabled title=${launch.title.safeLogValue()} " +
                "source=${launch.sourceUrl.redactedMediaUrl()} headers=${launch.sourceHeaders.keys.sorted()} " +
                "screenshot=${screenshotDirectory != null} perfIntervalMs=$performanceSampleIntervalMs " +
                "perfSampleCount=$performanceSampleCount",
        )
        startPerformanceSampler()
        scheduleScreenshot(windowProvider)
    }

    fun forwardingDeepLink(): String =
        buildString {
            append("nuvio://dev/play?url=").append(launch.sourceUrl.encodeURLParameter())
            append("&title=").append(launch.title.encodeURLParameter())
            append("&streamTitle=").append(launch.streamTitle.encodeURLParameter())
            append("&provider=").append(launch.providerName.encodeURLParameter())
            launch.sourceAudioUrl?.takeIf { it.isNotBlank() }?.let {
                append("&audioUrl=").append(it.encodeURLParameter())
            }
            launch.poster?.takeIf { it.isNotBlank() }?.let {
                append("&poster=").append(it.encodeURLParameter())
            }
            launch.background?.takeIf { it.isNotBlank() }?.let {
                append("&background=").append(it.encodeURLParameter())
            }
            launch.contentType?.takeIf { it.isNotBlank() }?.let {
                append("&type=").append(it.encodeURLParameter())
            }
            launch.videoId?.takeIf { it.isNotBlank() }?.let {
                append("&videoId=").append(it.encodeURLParameter())
            }
            append("&parentMetaId=").append(launch.parentMetaId.encodeURLParameter())
            append("&parentMetaType=").append(launch.parentMetaType.encodeURLParameter())
            launch.sourceHeaders.forEach { (name, value) ->
                append("&header=").append("$name:$value".encodeURLParameter())
            }
        }

    private fun startPerformanceSampler() {
        Thread(
            {
                val runtime = Runtime.getRuntime()
                repeat(performanceSampleCount) { index ->
                    runCatching {
                        val totalMb = runtime.totalMemory().toMegabytes()
                        val freeMb = runtime.freeMemory().toMegabytes()
                        val usedMb = max(0L, totalMb - freeMb)
                        val maxMb = runtime.maxMemory().toMegabytes()
                        DesktopRuntimeLog.info(
                            "devStream perf sample=${index + 1}/$performanceSampleCount " +
                                "heapUsedMb=$usedMb heapTotalMb=$totalMb heapMaxMb=$maxMb",
                        )
                    }.onFailure {
                        DesktopRuntimeLog.warn("devStream perf sample failed message=${it.message}")
                    }
                    Thread.sleep(performanceSampleIntervalMs)
                }
            },
            "nuvio-dev-stream-perf",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleScreenshot(windowProvider: () -> Window?) {
        val directory = screenshotDirectory ?: return
        Thread(
            {
                Thread.sleep(screenshotDelayMs)
                EventQueue.invokeLater {
                    runCatching {
                        val window = windowProvider() ?: error("window unavailable")
                        val bounds = Rectangle(window.locationOnScreen, window.size)
                        val capture = Robot().createScreenCapture(bounds)
                        Files.createDirectories(directory)
                        val output = directory.resolve(
                            "nuvio-dev-stream-${Instant.now().toString().replace(':', '-')}.png",
                        )
                        ImageIO.write(capture, "png", output.toFile())
                        DesktopRuntimeLog.info("devStream screenshot written path=${DesktopRuntimeLog.safePath(output)}")
                    }.onFailure {
                        DesktopRuntimeLog.warn("devStream screenshot failed message=${it.message}")
                    }
                }
            },
            "nuvio-dev-stream-screenshot",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

private fun AppDeepLink.DevStream.toPlayerLaunch(): PlayerLaunch =
    PlayerLaunch(
        title = title,
        sourceUrl = url,
        sourceAudioUrl = audioUrl,
        sourceHeaders = sanitizePlaybackHeaders(headers),
        poster = poster,
        background = background,
        streamTitle = streamTitle,
        streamSubtitle = "Custom dev stream",
        providerName = providerName,
        providerAddonId = "dev-stream",
        contentType = contentType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
    )

private fun Array<String>.parseDevStreamArgs(): Map<String, String> =
    buildMap {
        for (arg in this@parseDevStreamArgs) {
            val normalized = arg.trim()
            if (!normalized.startsWith("--dev-stream-", ignoreCase = true)) continue
            val payload = normalized.substringAfter("--dev-stream-")
            val separator = payload.indexOf('=')
            if (separator <= 0) continue
            val key = payload.substring(0, separator).trim().lowercase()
            val value = payload.substring(separator + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                put(key, value)
            }
        }
    }

private fun Map<String, String>.headers(): Map<String, String> =
    entries
        .asSequence()
        .filter { it.key.startsWith("header.", ignoreCase = true) }
        .mapNotNull { entry ->
            val name = entry.key.substringAfter("header.").trim()
            val value = entry.value.trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toMap()

internal fun parseDevStreamHeaderSpec(raw: String?): Map<String, String> {
    val input = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return input
        .split(Regex("[\\r\\n;]+"))
        .asSequence()
        .mapNotNull(::parseDevStreamHeaderLine)
        .toMap()
}

internal fun parseDevStreamHeaderEnvironment(env: Map<String, String>): Map<String, String> =
    env.entries
        .asSequence()
        .filter { it.key.startsWith(DevStreamHeaderEnvPrefix, ignoreCase = true) }
        .mapNotNull { entry ->
            val suffix = entry.key.substringAfter(DevStreamHeaderEnvPrefix, missingDelimiterValue = "")
            val name = suffix
                .split('_')
                .filter(String::isNotBlank)
                .joinToString("-")
            normalizeDevStreamHeader(name, entry.value)
        }
        .toMap()

private fun parseDevStreamHeaderLine(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return null
    val colonIndex = trimmed.indexOf(':')
    val equalsIndex = trimmed.indexOf('=')
    val separatorIndex = when {
        colonIndex > 0 && equalsIndex > 0 -> minOf(colonIndex, equalsIndex)
        colonIndex > 0 -> colonIndex
        equalsIndex > 0 -> equalsIndex
        else -> -1
    }
    if (separatorIndex <= 0) return null
    return normalizeDevStreamHeader(
        name = trimmed.substring(0, separatorIndex),
        value = trimmed.substring(separatorIndex + 1),
    )
}

private fun normalizeDevStreamHeader(name: String, value: String): Pair<String, String>? {
    val normalizedName = name.trim()
    val normalizedValue = value.trim()
    if (normalizedName.isBlank() || normalizedValue.isBlank()) return null
    if (!DevStreamHeaderNamePattern.matches(normalizedName)) return null
    return normalizedName to normalizedValue
}

private fun Long.toMegabytes(): Long = this / (1024L * 1024L)

private fun String.safeLogValue(): String =
    trim().take(80).replace(Regex("[\\r\\n\\t]+"), " ")

private fun String.redactedMediaUrl(): String {
    val parsed = runCatching { Url(this) }.getOrNull() ?: return "unparsed"
    return buildString {
        append(parsed.protocol.name)
        append("://")
        append(parsed.host.ifBlank { "local" })
        if (parsed.port != parsed.protocol.defaultPort) {
            append(':').append(parsed.port)
        }
        val extension = parsed.encodedPath.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isNotBlank() && extension.length <= 8) {
            append("/*.").append(extension)
        } else {
            append("/*")
        }
        val queryKeys = parsed.parameters.names().sorted()
        if (queryKeys.isNotEmpty()) {
            append("?keys=").append(queryKeys.joinToString(","))
        }
    }
}
