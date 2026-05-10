package com.nuvio.app.features.player.desktop.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.app.desktop.DesktopPlayerRegistry
import com.nuvio.app.desktop.DesktopPreferences

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.desktop.DesktopPlayerBackend
import com.nuvio.app.features.player.desktop.DesktopPlayerError
import com.nuvio.app.features.player.desktop.DesktopPlayerPhase
import com.nuvio.app.features.player.desktop.DesktopPlayerRequest
import com.nuvio.app.features.player.desktop.DesktopPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.UriMediaData
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

private const val ExternalSubtitleCodepage = "+utf-8"
private const val EmbeddedSubtitleCodepage = "auto"
private const val ExternalSubtitleAssOverride = "strip"
private const val EmbeddedSubtitleAssOverride = "no"

@OptIn(InternalMediampApi::class)
internal class MpvDesktopPlayerBackend private constructor(
    private val runtime: MpvRuntimeResolution,
    private val player: MpvMediampPlayer,
) : DesktopPlayerBackend {
    override val id: String = "windows-mpv-${System.identityHashCode(player)}"
    override val backendName: String = "windows-mediamp-mpv"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow(
        DesktopPlayerState(
            phase = DesktopPlayerPhase.Idle,
            backendName = backendName,
            diagnostics = runtime.diagnostics,
        ),
    )

    @Volatile private var stopped = false
    @Volatile private var nativeClosed = false
    @Volatile private var currentRequest: DesktopPlayerRequest? = null
    @Volatile private var lastKnownPositionMs: Long = 0L
    @Volatile private var externalSubtitleActive = false
    @Volatile private var latestSubtitleStyle = SubtitleStyleState.DEFAULT
    private val externalSubtitleRequestCounter = AtomicInteger(0)
    private val externalSubtitleTempFiles = mutableSetOf<Path>()
    private val subtitleHttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override val state: StateFlow<DesktopPlayerState> = stateFlow
    override val controller: PlayerEngineController = MpvController()

    init {
        observePlayerState()
        DesktopRuntimeLog.info("MPV backend created id=$id runtime=${runtime.directory?.safePath() ?: "none"}")
    }

    override suspend fun load(request: DesktopPlayerRequest) {
        if (nativeClosed) return
        if (request.sourceUrl.isBlank()) {
            fail(DesktopPlayerError.InvalidSource(backendName, "Blank source URL"))
            return
        }
        currentRequest = request
        stopped = false
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Preparing, error = null)
        runCatching {
            val headers = request.sourceHeaders.toMutableMap()
            DesktopRuntimeLog.info(
                "MPV load start session=${request.sessionKey} source=${request.sourceUrl.redactedMediaUrl()} " +
                    "audio=${request.sourceAudioUrl?.redactedMediaUrl() ?: "none"} headersPresent=${headers.isNotEmpty()}",
            )
            resetExternalSubtitleState("load")
            // Apply user-configured decoder settings from Desktop preferences
            applyDecoderSettings()
                        player.setMediaData(UriMediaData(request.sourceUrl, headers))
            // Seek to saved position after source switch
            if (request.seekTargetMs > 0L) {
                runCatching { player.impl.command("seek", (request.seekTargetMs / 1000.0).toString(), "absolute") }
                    .onFailure { DesktopRuntimeLog.error("MPV seek after load failed target=${request.seekTargetMs}ms", it) }
                DesktopRuntimeLog.info("MPV seek after load target=${request.seekTargetMs}ms")
            }
            request.sourceAudioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
                runCatching { player.impl.command("audio-add", audioUrl, "auto") }
                    .onFailure { DesktopRuntimeLog.error("MPV audio-add failed audio=${audioUrl.redactedMediaUrl()}", it) }
            }
            setResizeMode(request.resizeMode)
            if (request.playWhenReady) {
                player.resume()
                runCatching { player.impl.setPropertyBoolean("pause", false) }
                    .onFailure { DesktopRuntimeLog.error("MPV unpause after load failed", it) }
            } else {
                player.pause()
            }
            DesktopRuntimeLog.info("MPV load success session=${request.sessionKey}")
        }.onFailure { throwable ->
            DesktopRuntimeLog.error("MPV load failed source=${request.sourceUrl.redactedMediaUrl()}", throwable)
            fail(DesktopPlayerError.MediaLoadFailed(backendName, "MPV media load failed", throwable))
        }
    }

    override fun setResizeMode(resizeMode: PlayerResizeMode) {
        if (!canReceiveCommands()) return
        runCatching { player.impl.applyResizeMode(resizeMode) }
            .onSuccess { DesktopRuntimeLog.info("MPV resizeMode=$resizeMode applied") }
            .onFailure { DesktopRuntimeLog.error("MPV resizeMode=$resizeMode failed", it) }
    }

    override fun releaseSoft() {
        if (stopped) return
        stopped = true
        DesktopRuntimeLog.info("MPV releaseSoft id=$id")
        resetExternalSubtitleState("releaseSoft")
        runCatching { player.impl.setPropertyBoolean("mute", true) }
        runCatching { player.impl.command("stop") }
            .onFailure { DesktopRuntimeLog.error("MPV stop failed id=$id", it) }
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Closed)
    }

    override fun close() {
        if (nativeClosed) return
        resetExternalSubtitleState("close")
        nativeClosed = true
        scope.cancel()
        DesktopRuntimeLog.info("MPV close async id=$id")
        val thread = Thread({
            val startMs = System.currentTimeMillis()
            runCatching { player.close() }
                .onSuccess {
                    DesktopRuntimeLog.info("MPV native close done id=$id elapsedMs=${System.currentTimeMillis() - startMs}")
                }
                .onFailure { DesktopRuntimeLog.error("MPV native close failed id=$id", it) }
        }, "mpv-close-$id").apply { isDaemon = true }
        DesktopPlayerRegistry.trackCloseThread(thread)
        thread.start()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        MpvDesktopPlayerSurface(player = player, modifier = modifier)
    }

    private fun observePlayerState() {
        combine(
            player.playbackState,
            player.currentPositionMillis,
            player.mediaProperties,
        ) { playbackState, position, props ->
            val phase = playbackState.toDesktopPhase()
            DesktopPlayerState(
                phase = phase,
                positionMs = position,
                durationMs = props?.durationMillis?.takeIf { it > 0 } ?: 0L,
                bufferedPositionMs = 0L,
                playbackSpeed = player.features[PlaybackSpeed]?.value ?: 1.0f,
                backendName = backendName,
                diagnostics = runtime.diagnostics,
                error = if (playbackState == PlaybackState.ERROR) {
                    DesktopPlayerError.PlaybackFailed(backendName, "MPV playback state is ERROR")
                } else {
                    null
                },
            )
        }.onEach { mapped ->
            if (mapped.phase == DesktopPlayerPhase.Playing && mapped.positionMs > 0L) {
                lastKnownPositionMs = mapped.positionMs
            }
            if (!nativeClosed) {
                stateFlow.value = mapped
            }
        }.launchIn(scope)
    }

    
    /**
     * Applies Desktop decoder preferences (hwdec mode) to the running MPV player.
     * Settings are read from DesktopPreferences under "nuvio_decoder_settings".
     *
     * Only hwdec is configurable at runtime. The GPU rendering backend is fixed
     * to OpenGL because mpv's vo=libmpv render API only supports OpenGL contexts.
     * For D3D11/Vulkan rendering, a native HWND-based approach (vo=gpu-next+wid)
     * would be required - see stremio-community-v5 for an example of this pattern.
     */
    private fun applyDecoderSettings() {
        if (nativeClosed) return
        val hwdecMode = DesktopPreferences.getString("nuvio_decoder_settings", "hwdec_mode") ?: "auto"
        runCatching {
            player.impl.command("set", "hwdec", hwdecMode)
            DesktopRuntimeLog.info("MPV decoder: hwdec=$hwdecMode")
        }.onFailure {
            DesktopRuntimeLog.warn("MPV decoder: failed to set hwdec=$hwdecMode message=${'$'}{it.message}")
        }
    }

    private fun fail(error: DesktopPlayerError) {
        stateFlow.value = stateFlow.value.copy(
            phase = DesktopPlayerPhase.Error,
            error = error,
            diagnostics = error.technicalMessage,
        )
    }

    private fun canReceiveCommands(): Boolean =
        !stopped && !nativeClosed && player.getCurrentPlaybackState() != PlaybackState.FINISHED

    private fun durationMs(): Long? =
        player.mediaProperties.value?.durationMillis?.takeIf { it > 0L }

    private fun snapshotForLog(): String =
        "state=${player.getCurrentPlaybackState()} posMs=${player.currentPositionMillis.value} durationMs=${durationMs() ?: -1}"

    private fun resetExternalSubtitleState(reason: String) {
        if (nativeClosed) return
        externalSubtitleRequestCounter.incrementAndGet()
        externalSubtitleActive = false
        clearExternalSubtitleTempFiles(reason)
        runCatching {
            player.impl.setMpvRuntimeOption("sub-codepage", EmbeddedSubtitleCodepage)
            player.impl.setMpvRuntimeOption("embeddedfonts", "yes")
            player.impl.setMpvRuntimeOption("sub-ass-override", EmbeddedSubtitleAssOverride)
        }.onFailure { DesktopRuntimeLog.warn("MPV reset external subtitle state failed reason=$reason message=${it.message}") }
    }

    private inner class MpvController : PlayerEngineController {
        override fun release() = releaseSoft()

        override fun play() {
            if (!canReceiveCommands()) return
            val before = snapshotForLog()
            val result = runCatching {
                player.resume()
                player.impl.setPropertyBoolean("pause", false)
            }
            DesktopRuntimeLog.info("MPV controller play before=$before result=${result.getOrNull()} after=${snapshotForLog()}")
            result.onFailure { DesktopRuntimeLog.error("MPV controller play failed", it) }
        }

        override fun pause() {
            if (!canReceiveCommands()) return
            val before = snapshotForLog()
            val result = runCatching { player.pause() }
            DesktopRuntimeLog.info("MPV controller pause before=$before result=${result.getOrNull()} after=${snapshotForLog()}")
            result.onFailure { DesktopRuntimeLog.error("MPV controller pause failed", it) }
        }

        override fun seekTo(positionMs: Long) {
            if (!canReceiveCommands()) return
            val durationMs = durationMs()
            val targetMs = positionMs.coerceAtLeast(0L).let { target -> durationMs?.let(target::coerceAtMost) ?: target }
            val before = snapshotForLog()
            val result = runCatching { player.impl.command("seek", (targetMs / 1000.0).toString(), "absolute+exact") }
            if (result.getOrNull() == true) player.currentPositionMillis.value = targetMs
            DesktopRuntimeLog.info(
                "MPV controller seekTo targetMs=$targetMs durationMs=${durationMs ?: -1} " +
                    "before=$before result=${result.getOrNull()} after=${snapshotForLog()}",
            )
            result.onFailure { DesktopRuntimeLog.error("MPV controller seekTo failed targetMs=$targetMs", it) }
        }

        override fun seekBy(offsetMs: Long) {
            if (!canReceiveCommands()) return
            seekTo(player.currentPositionMillis.value.coerceAtLeast(0L) + offsetMs)
        }

        override fun retry() = play()

        override fun setPlaybackSpeed(speed: Float) {
            if (!canReceiveCommands()) return
            player.features[PlaybackSpeed]?.set(speed.coerceIn(0.25f, 4.0f))
        }

        override fun getAudioTracks(): List<AudioTrack> =
            if (canReceiveCommands()) runCatching { player.impl.audioTracks() }.getOrDefault(emptyList()) else emptyList()

        override fun getSubtitleTracks(): List<SubtitleTrack> =
            if (canReceiveCommands()) runCatching { player.impl.subtitleTracks() }.getOrDefault(emptyList()) else emptyList()

        override fun selectAudioTrack(index: Int) {
            if (!canReceiveCommands()) return
            val tracks = getAudioTracks()
            if (index in tracks.indices) {
                runCatching { player.impl.setMpvProperty("aid", tracks[index].id) }
                    .onFailure { DesktopRuntimeLog.error("MPV selectAudioTrack failed index=$index", it) }
            }
        }

        override fun selectSubtitleTrack(index: Int) {
            if (!canReceiveCommands()) return
            if (index < 0) {
                runCatching {
                    externalSubtitleActive = false
                    player.impl.setMpvProperty("sid", "no")
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "select-none")
                }
                return
            }
            val tracks = getSubtitleTracks()
            if (index in tracks.indices) {
                runCatching {
                    externalSubtitleActive = false
                    player.impl.setMpvProperty("sid", tracks[index].id)
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "select-built-in")
                }
                    .onFailure { DesktopRuntimeLog.error("MPV selectSubtitleTrack failed index=$index", it) }
            }
        }

        override fun setSubtitleUri(url: String) {
            if (!canReceiveCommands()) return
            val requestId = externalSubtitleRequestCounter.incrementAndGet()
            removeExternalSubtitleTracks(cancelPendingRequest = false, reason = "replace-external")
            runCatching {
                externalSubtitleActive = true
                player.impl.setMpvRuntimeOption("sub-codepage", ExternalSubtitleCodepage)
                player.impl.setMpvRuntimeOption("sub-visibility", "yes")
                applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "set-external-preload")
            }
                .onFailure { DesktopRuntimeLog.error("MPV setSubtitleUri failed url=${url.redactedMediaUrl()}", it) }
            scope.launch(Dispatchers.IO) {
                val subtitleRef = runCatching { prepareExternalSubtitleReference(url) }
                    .onFailure {
                        DesktopRuntimeLog.warn(
                            "MPV external subtitle normalization failed url=${url.redactedMediaUrl()} message=${it.message}; using original URL",
                        )
                    }
                    .getOrDefault(url)
                if (requestId != externalSubtitleRequestCounter.get() || !canReceiveCommands()) {
                    DesktopRuntimeLog.info("MPV external subtitle add skipped stale request url=${url.redactedMediaUrl()}")
                    return@launch
                }
                runCatching {
                    player.impl.setMpvRuntimeOption("sub-codepage", ExternalSubtitleCodepage)
                    player.impl.setMpvRuntimeOption("sub-visibility", "yes")
                    player.impl.command("sub-add", subtitleRef, "select")
                    selectNewestExternalSubtitle()
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "set-external")
                }.onFailure {
                    DesktopRuntimeLog.error("MPV setSubtitleUri failed url=${url.redactedMediaUrl()}", it)
                }
            }
        }

        override fun clearExternalSubtitle() {
            removeExternalSubtitleTracks(cancelPendingRequest = true, reason = "clear-external")
        }

        private fun removeExternalSubtitleTracks(cancelPendingRequest: Boolean, reason: String) {
            if (!canReceiveCommands()) return
            if (cancelPendingRequest) {
                externalSubtitleRequestCounter.incrementAndGet()
            }
            val handle = player.impl
            val hadExternalSubtitle = externalSubtitleActive
            val count = handle.getMpvIntProperty("track-list/count")
            if (count == null) {
                if (hadExternalSubtitle) {
                    externalSubtitleActive = false
                    applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = "$reason-missing-track-list")
                }
                clearExternalSubtitleTempFiles("$reason-missing-track-list")
                return
            }
            for (i in count - 1 downTo 0) {
                val type = handle.getMpvStringProperty("track-list/$i/type")
                val external = handle.getMpvBooleanProperty("track-list/$i/external")
                if (type == "sub" && external) {
                    val id = handle.getMpvIntProperty("track-list/$i/id") ?: continue
                    runCatching { handle.command("sub-remove", id.toString()) }
                }
            }
            if (hadExternalSubtitle) {
                externalSubtitleActive = false
                applySubtitleStyleToCurrentTrack(latestSubtitleStyle, reason = reason)
            }
            clearExternalSubtitleTempFiles(reason)
        }

        private fun selectNewestExternalSubtitle() {
            val handle = player.impl
            val count = handle.getMpvIntProperty("track-list/count") ?: return
            var newestExternalSubtitleId: Int? = null
            for (i in 0 until count) {
                val type = handle.getMpvStringProperty("track-list/$i/type")
                val external = handle.getMpvBooleanProperty("track-list/$i/external")
                if (type == "sub" && external) {
                    newestExternalSubtitleId = handle.getMpvIntProperty("track-list/$i/id") ?: newestExternalSubtitleId
                }
            }
            newestExternalSubtitleId?.let { id ->
                handle.setMpvProperty("sid", id.toString())
            }
        }

        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
            clearExternalSubtitle()
            selectSubtitleTrack(trackIndex)
        }

        override fun applySubtitleStyle(style: SubtitleStyleState) {
            latestSubtitleStyle = style
            if (!canReceiveCommands()) return
            applySubtitleStyleToCurrentTrack(style, reason = "settings")
        }

        private fun applySubtitleStyleToCurrentTrack(style: SubtitleStyleState, reason: String) {
            if (!canReceiveCommands()) return
            val handle = player.impl
            val colorHex = style.textColor.toMpvColorString()
            val outline = if (style.outlineEnabled) 2.0 else 0.0
            val subPos = 100 - style.bottomOffset
            runCatching {
                val selectedTrack = handle.selectedSubtitleTrackDetails()
                val useExternalSubtitleStyle = externalSubtitleActive || selectedTrack?.external == true
                val assOverrideMode = if (useExternalSubtitleStyle) ExternalSubtitleAssOverride else EmbeddedSubtitleAssOverride
                val codepage = if (useExternalSubtitleStyle) ExternalSubtitleCodepage else EmbeddedSubtitleCodepage

                // Desktop MPV already renders ASS/SSA through libass. Preserve authored ASS/SSA
                // styles and embedded fonts for embedded tracks. External addon subtitles are
                // normalized to app-controlled plain text, so they follow Nuvio style and placement.
                handle.setMpvRuntimeOption("sub-codepage", codepage)
                handle.setMpvRuntimeOption("embeddedfonts", "yes")
                handle.setMpvRuntimeOption("sub-ass-override", assOverrideMode)
                handle.setMpvRuntimeOption("sub-color", colorHex)
                handle.setMpvRuntimeOption("sub-border-size", outline)
                handle.setMpvRuntimeOption("sub-font-size", style.fontSizeSp.toDouble())
                handle.setMpvRuntimeOption("sub-pos", subPos)
                handle.setMpvRuntimeOption("sub-align-y", "bottom")

                DesktopRuntimeLog.info(
                    "MPV applySubtitleStyle selected=${selectedTrack?.toLogString() ?: "none"} " +
                        "reason=$reason assOverride=$assOverrideMode codepage=$codepage " +
                        "embeddedfonts=yes externalActive=$externalSubtitleActive " +
                        "appStyleTarget=${if (useExternalSubtitleStyle) "external-subtitle" else "embedded-plain-text"}",
                )
            }.onFailure { DesktopRuntimeLog.error("MPV applySubtitleStyle failed", it) }
        }

        override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
            if (!canReceiveCommands()) return
            val previous = currentRequest ?: return
            val headers = parseHeadersJson(headersJson).ifEmpty { previous.sourceHeaders }
            DesktopRuntimeLog.info(
                "MPV switchSource reloadInPlace url=${url.redactedMediaUrl()} " +
                    "audio=${audioUrl?.redactedMediaUrl() ?: "none"} headersPresent=${headers.isNotEmpty()}",
            )
            scope.launch {
                load(
                    previous.copy(
                        sourceUrl = url,
                        sourceAudioUrl = audioUrl,
                        sourceHeaders = headers,
                        playWhenReady = true,
                    ),
                )
            }
        }
    }

    private fun prepareExternalSubtitleReference(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url
        val request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "NuvioDesktop/1.0")
            .build()
        val response = subtitleHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()}")
        }
        val text = response.body().decodeExternalSubtitleText()
        val extension = subtitleExtension(uri, text)
        val file = Files.createTempFile("nuvio-external-subtitle-", extension)
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        synchronized(externalSubtitleTempFiles) {
            externalSubtitleTempFiles.add(file)
        }
        DesktopRuntimeLog.info(
            "MPV external subtitle normalized url=${url.redactedMediaUrl()} temp=${file.toSafeLogPath()} extension=$extension",
        )
        return file.toUri().toString()
    }

    private fun ByteArray.decodeExternalSubtitleText(): String {
        val bytes = dropUtf8Bom()
        val decoded = runCatching { bytes.decodeStrictUtf8() }.getOrElse {
            String(bytes, Charset.forName("windows-1252"))
        }
        return decoded.repairCommonMojibake()
    }

    private fun ByteArray.dropUtf8Bom(): ByteArray =
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
            copyOfRange(3, size)
        } else {
            this
        }

    private fun ByteArray.decodeStrictUtf8(): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(this))
            .toString()

    private fun String.repairCommonMojibake(): String {
        if ('Ã' !in this && 'Â' !in this && '�' !in this) return this
        if (any { it.code > 255 }) return this
        val repaired = runCatching {
            String(toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        }.getOrNull() ?: return this
        return if (repaired.mojibakeScore() < mojibakeScore()) repaired else this
    }

    private fun String.mojibakeScore(): Int =
        count { it == 'Ã' || it == 'Â' || it == '�' }

    private fun subtitleExtension(uri: URI, text: String): String {
        val pathExtension = uri.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            pathExtension in setOf("ass", "ssa", "srt", "vtt") -> ".$pathExtension"
            text.trimStart().startsWith("[Script Info]", ignoreCase = true) -> ".ass"
            text.trimStart().startsWith("WEBVTT", ignoreCase = true) -> ".vtt"
            else -> ".srt"
        }
    }

    private fun clearExternalSubtitleTempFiles(reason: String) {
        val files = synchronized(externalSubtitleTempFiles) {
            externalSubtitleTempFiles.toList().also { externalSubtitleTempFiles.clear() }
        }
        files.forEach { file ->
            runCatching { Files.deleteIfExists(file) }
                .onFailure {
                    DesktopRuntimeLog.warn(
                        "MPV external subtitle temp cleanup failed reason=$reason file=${file.toSafeLogPath()} message=${it.message}",
                    )
                }
        }
    }

    private fun MPVHandle.setMpvRuntimeOption(name: String, value: Any): Boolean {
        val stringValue = value.toString()
        return command("set", name, stringValue) || option(name, stringValue) || setMpvProperty(name, value)
    }

    private fun Path.toSafeLogPath(): String =
        runCatching { toAbsolutePath().toString() }.getOrDefault(toString())

    private data class MpvSubtitleTrackDetails(
        val id: Int,
        val codec: String,
        val external: Boolean,
        val title: String,
        val language: String,
    ) {
        fun toLogString(): String =
            "id=$id codec=${codec.ifBlank { "unknown" }} external=$external " +
                "title=${title.ifBlank { "none" }} lang=${language.ifBlank { "none" }}"
    }

    private fun org.openani.mediamp.mpv.MPVHandle.selectedSubtitleTrackDetails(): MpvSubtitleTrackDetails? {
        val count = getMpvIntProperty("track-list/count") ?: return null
        for (i in 0 until count) {
            if (getMpvStringProperty("track-list/$i/type") != "sub") continue
            if (!getMpvBooleanProperty("track-list/$i/selected")) continue
            val id = getMpvIntProperty("track-list/$i/id") ?: continue
            return MpvSubtitleTrackDetails(
                id = id,
                codec = getMpvStringProperty("track-list/$i/codec"),
                external = getMpvBooleanProperty("track-list/$i/external"),
                title = getMpvStringProperty("track-list/$i/title"),
                language = getMpvStringProperty("track-list/$i/lang"),
            )
        }
        return null
    }

    companion object {
        fun create(runtime: MpvRuntimeResolution): Result<MpvDesktopPlayerBackend> =
            runCatching {
                MpvDesktopPlayerBackend(
                    runtime = runtime,
                    player = MpvMediampPlayer(Unit, EmptyCoroutineContext),
                )
            }
    }
}

private fun parseHeadersJson(headersJson: String?): Map<String, String> {
    if (headersJson.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(headersJson).jsonObject.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val content = primitive.jsonPrimitive.content.trim()
            if (key.isBlank() || content.isBlank()) null else key.trim() to content
        }.toMap()
    }.getOrDefault(emptyMap())
}

private fun Color.toMpvColorString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#${r.hex()}${g.hex()}${b.hex()}${a.hex()}"
}

private fun Int.hex(): String = toString(16).padStart(2, '0').uppercase()
