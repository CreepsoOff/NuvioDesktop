package com.nuvio.app.features.player.desktop.nativebridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.nuvio.app.LocalDesktopWindow
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.desktop.DesktopPlayerBackend
import com.nuvio.app.features.player.desktop.DesktopPlayerError
import com.nuvio.app.features.player.desktop.DesktopPlayerPhase
import com.nuvio.app.features.player.desktop.DesktopPlayerRequest
import com.nuvio.app.features.player.desktop.DesktopPlayerState
import com.nuvio.app.features.player.desktop.WindowsDisplayWakeLock
import com.nuvio.app.features.player.desktop.mpv.redactedMediaUrl
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeBridgeDesktopPlayerBackend private constructor(
    private val bridge: NativeBridgeJnaApi,
) : DesktopPlayerBackend {
    override val id: String = "windows-native-${System.identityHashCode(this)}"
    override val backendName: String = "windows-native-bridge"

    private val playerPtr: Pointer = bridge.nuvio_player_create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateFlow = MutableStateFlow(DesktopPlayerState(backendName = backendName))

    @Volatile private var closed = false
    @Volatile private var attached = false
    @Volatile private var displayWakeLockHeld = false

    private var onCloseCallback: (() -> Unit)? = null
    private var onAddonSubtitlesFetchCallback: (() -> Unit)? = null
    private var onSourcesRequestedCallback: (() -> Unit)? = null
    private var onSourceStreamSelectedCallback: ((String) -> Unit)? = null
    private var onSourceFilterChangedCallback: ((String?) -> Unit)? = null
    private var onSourceReloadCallback: (() -> Unit)? = null
    private var onEpisodesRequestedCallback: (() -> Unit)? = null
    private var onEpisodeSelectedCallback: ((String) -> Unit)? = null
    private var onEpisodeStreamSelectedCallback: ((String) -> Unit)? = null
    private var onEpisodeFilterChangedCallback: ((String?) -> Unit)? = null
    private var onEpisodeReloadCallback: (() -> Unit)? = null
    private var onEpisodeBackCallback: (() -> Unit)? = null

    override val state: StateFlow<DesktopPlayerState> = stateFlow
    override val controller: PlayerEngineController = NativeBridgeController()

    init {
        startPolling()
    }

    override suspend fun load(request: DesktopPlayerRequest) {
        if (closed) return
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Preparing, error = null)
        val headersJson = if (request.sourceHeaders.isNotEmpty()) {
            buildJsonObject { request.sourceHeaders.forEach { (key, value) -> put(key, value) } }.toString()
        } else {
            null
        }
        runCatching {
            DesktopRuntimeLog.info("Native bridge load source=${request.sourceUrl.redactedMediaUrl()}")
            bridge.nuvio_player_load_file(playerPtr, request.sourceUrl, request.sourceAudioUrl, headersJson)
            setResizeMode(request.resizeMode)
            if (request.playWhenReady) bridge.nuvio_player_play(playerPtr) else bridge.nuvio_player_pause(playerPtr)
        }.onFailure {
            releaseDisplayWakeLock("load-failed")
            stateFlow.value = stateFlow.value.copy(
                phase = DesktopPlayerPhase.Error,
                error = DesktopPlayerError.MediaLoadFailed(backendName, "Native bridge media load failed", it),
            )
        }
    }

    override fun setResizeMode(resizeMode: PlayerResizeMode) {
        if (closed) return
        val mode = when (resizeMode) {
            PlayerResizeMode.Fit -> 0
            PlayerResizeMode.Fill -> 1
            PlayerResizeMode.Zoom -> 2
        }
        runCatching { bridge.nuvio_player_set_resize_mode(playerPtr, mode) }
    }

    override fun releaseSoft() {
        if (closed) return
        releaseDisplayWakeLock("releaseSoft")
        runCatching { bridge.nuvio_player_pause(playerPtr) }
    }

    override fun close() {
        if (closed) return
        releaseDisplayWakeLock("close")
        closed = true
        scope.cancel()
        runCatching { bridge.nuvio_player_destroy(playerPtr) }
            .onFailure { DesktopRuntimeLog.error("Native bridge destroy failed", it) }
        stateFlow.value = stateFlow.value.copy(phase = DesktopPlayerPhase.Closed)
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        val desktopWindow = LocalDesktopWindow.current
        val lastBounds = remember(playerPtr) { arrayOfNulls<Rect>(1) }
        var showCalled by remember(playerPtr) { mutableStateOf(false) }

        LaunchedEffect(desktopWindow, playerPtr) {
            val window = desktopWindow ?: return@LaunchedEffect
            val nativePtr = Native.getComponentPointer(window) ?: return@LaunchedEffect
            bridge.nuvio_player_show(playerPtr, Pointer.nativeValue(nativePtr))
            attached = true
            showCalled = true
        }

        Box(
            modifier = modifier
                .background(Color.Black)
                .onGloballyPositioned { coordinates ->
                    if (!showCalled) return@onGloballyPositioned
                    val bounds = coordinates.boundsInWindow()
                    if (lastBounds[0] == bounds) return@onGloballyPositioned
                    lastBounds[0] = bounds
                    bridge.nuvio_player_set_bounds(
                        playerPtr,
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.width.toInt().coerceAtLeast(1),
                        bounds.height.toInt().coerceAtLeast(1),
                    )
                },
        )
    }

    private fun startPolling() {
        scope.launch {
            while (!closed) {
                delay(250)
                val pollState = withContext(Dispatchers.IO) {
                    bridge.nuvio_player_refresh_state(playerPtr)
                    NativeBridgePollState(
                        isClosed = bridge.nuvio_player_is_closed(playerPtr),
                        snapshot = com.nuvio.app.features.player.PlayerPlaybackSnapshot(
                            isLoading = bridge.nuvio_player_is_loading(playerPtr),
                            isPlaying = bridge.nuvio_player_is_playing(playerPtr),
                            isEnded = bridge.nuvio_player_is_ended(playerPtr),
                            positionMs = bridge.nuvio_player_get_position_ms(playerPtr),
                            durationMs = bridge.nuvio_player_get_duration_ms(playerPtr),
                            bufferedPositionMs = bridge.nuvio_player_get_buffered_ms(playerPtr),
                            playbackSpeed = bridge.nuvio_player_get_speed(playerPtr),
                        ),
                        error = bridge.nuvio_player_get_error(playerPtr),
                        addonSubtitlesFetchRequested = bridge.nuvio_player_is_addon_subtitles_fetch_requested(playerPtr),
                        subtitleStyleChanged = bridge.nuvio_player_pop_subtitle_style_changed(playerPtr),
                        subtitleStyleColorIndex = bridge.nuvio_player_get_subtitle_style_color_index(playerPtr),
                        subtitleStyleOutlineEnabled = bridge.nuvio_player_get_subtitle_style_outline_enabled(playerPtr),
                        subtitleStyleFontSize = bridge.nuvio_player_get_subtitle_style_font_size(playerPtr),
                        subtitleStyleBottomOffset = bridge.nuvio_player_get_subtitle_style_bottom_offset(playerPtr),
                        nextEpisodePressed = bridge.nuvio_player_pop_next_episode_pressed(playerPtr),
                        sourcesOpenRequested = bridge.nuvio_player_pop_sources_open_requested(playerPtr),
                        episodesOpenRequested = bridge.nuvio_player_pop_episodes_open_requested(playerPtr),
                        selectedSourceUrl = bridge.nuvio_player_pop_source_stream_selected(playerPtr),
                        sourceFilterChanged = bridge.nuvio_player_pop_source_filter_changed(playerPtr),
                        sourceFilterValue = bridge.nuvio_player_get_source_filter_value(playerPtr),
                        sourceReloadRequested = bridge.nuvio_player_pop_source_reload(playerPtr),
                        selectedEpisodeId = bridge.nuvio_player_pop_episode_selected(playerPtr),
                        selectedEpisodeStreamUrl = bridge.nuvio_player_pop_episode_stream_selected(playerPtr),
                        episodeFilterChanged = bridge.nuvio_player_pop_episode_filter_changed(playerPtr),
                        episodeFilterValue = bridge.nuvio_player_get_episode_filter_value(playerPtr),
                        episodeReloadRequested = bridge.nuvio_player_pop_episode_reload(playerPtr),
                        episodeBackRequested = bridge.nuvio_player_pop_episode_back(playerPtr),
                    )
                }
                if (pollState.isClosed) {
                    onCloseCallback?.invoke()
                    close()
                    break
                }
                val nextState = DesktopPlayerState(
                    phase = when {
                        pollState.error != null -> DesktopPlayerPhase.Error
                        pollState.snapshot.isEnded -> DesktopPlayerPhase.Ended
                        pollState.snapshot.isLoading -> DesktopPlayerPhase.Buffering
                        pollState.snapshot.isPlaying -> DesktopPlayerPhase.Playing
                        else -> DesktopPlayerPhase.Paused
                    },
                    positionMs = pollState.snapshot.positionMs,
                    durationMs = pollState.snapshot.durationMs,
                    bufferedPositionMs = pollState.snapshot.bufferedPositionMs,
                    playbackSpeed = pollState.snapshot.playbackSpeed,
                    backendName = backendName,
                    error = pollState.error?.let { DesktopPlayerError.PlaybackFailed(backendName, it) },
                )
                updateDisplayWakeLock(nextState.phase)
                stateFlow.value = nextState
                if (pollState.addonSubtitlesFetchRequested) onAddonSubtitlesFetchCallback?.invoke()
                if (pollState.subtitleStyleChanged) {
                    val colorIndex = pollState.subtitleStyleColorIndex.coerceIn(0, SubtitleColorSwatches.lastIndex)
                    PlayerSettingsRepository.setSubtitleStyle(
                        SubtitleStyleState(
                            textColor = SubtitleColorSwatches[colorIndex],
                            outlineEnabled = pollState.subtitleStyleOutlineEnabled,
                            fontSizeSp = pollState.subtitleStyleFontSize,
                            bottomOffset = pollState.subtitleStyleBottomOffset,
                        ),
                    )
                }
                if (pollState.sourcesOpenRequested) onSourcesRequestedCallback?.invoke()
                if (pollState.episodesOpenRequested) onEpisodesRequestedCallback?.invoke()
                pollState.selectedSourceUrl?.let { onSourceStreamSelectedCallback?.invoke(it) }
                if (pollState.sourceFilterChanged) onSourceFilterChangedCallback?.invoke(pollState.sourceFilterValue)
                if (pollState.sourceReloadRequested) onSourceReloadCallback?.invoke()
                pollState.selectedEpisodeId?.let { onEpisodeSelectedCallback?.invoke(it) }
                pollState.selectedEpisodeStreamUrl?.let { onEpisodeStreamSelectedCallback?.invoke(it) }
                if (pollState.episodeFilterChanged) onEpisodeFilterChangedCallback?.invoke(pollState.episodeFilterValue)
                if (pollState.episodeReloadRequested) onEpisodeReloadCallback?.invoke()
                if (pollState.episodeBackRequested) onEpisodeBackCallback?.invoke()
            }
        }
    }

    private fun updateDisplayWakeLock(phase: DesktopPlayerPhase) {
        if (phase == DesktopPlayerPhase.Playing) {
            if (!displayWakeLockHeld) {
                displayWakeLockHeld = WindowsDisplayWakeLock.acquire("$backendName:$id:$phase")
            }
        } else {
            releaseDisplayWakeLock("phase-$phase")
        }
    }

    private fun releaseDisplayWakeLock(reason: String) {
        if (!displayWakeLockHeld) return
        displayWakeLockHeld = false
        WindowsDisplayWakeLock.release("$backendName:$id:$reason")
    }

    private inner class NativeBridgeController : PlayerEngineController {
        override fun play() = runIfOpen { bridge.nuvio_player_play(playerPtr) }
        override fun pause() = runIfOpen { bridge.nuvio_player_pause(playerPtr) }
        override fun seekTo(positionMs: Long) = runIfOpen { bridge.nuvio_player_seek_to(playerPtr, positionMs) }
        override fun seekBy(offsetMs: Long) = runIfOpen { bridge.nuvio_player_seek_by(playerPtr, offsetMs) }
        override fun retry() = runIfOpen { bridge.nuvio_player_retry(playerPtr) }
        override fun setPlaybackSpeed(speed: Float) = runIfOpen { bridge.nuvio_player_set_speed(playerPtr, speed) }
        override fun getAudioTracks(): List<AudioTrack> = if (closed) emptyList() else (0 until bridge.nuvio_player_get_audio_track_count(playerPtr)).map { index ->
            AudioTrack(index, bridge.nuvio_player_get_audio_track_id(playerPtr, index).toString(), bridge.nuvio_player_get_audio_track_label(playerPtr, index) ?: "", bridge.nuvio_player_get_audio_track_lang(playerPtr, index), bridge.nuvio_player_is_audio_track_selected(playerPtr, index))
        }
        override fun getSubtitleTracks(): List<SubtitleTrack> = if (closed) emptyList() else (0 until bridge.nuvio_player_get_subtitle_track_count(playerPtr)).map { index ->
            SubtitleTrack(index, bridge.nuvio_player_get_subtitle_track_id(playerPtr, index).toString(), bridge.nuvio_player_get_subtitle_track_label(playerPtr, index) ?: "", bridge.nuvio_player_get_subtitle_track_lang(playerPtr, index), bridge.nuvio_player_is_subtitle_track_selected(playerPtr, index))
        }
        override fun selectAudioTrack(index: Int) = runIfOpen {
            val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
            if (index in 0 until count) bridge.nuvio_player_select_audio_track(playerPtr, bridge.nuvio_player_get_audio_track_id(playerPtr, index))
        }
        override fun selectSubtitleTrack(index: Int) = runIfOpen {
            if (index < 0) {
                bridge.nuvio_player_select_subtitle_track(playerPtr, -1)
            } else {
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                if (index in 0 until count) bridge.nuvio_player_select_subtitle_track(playerPtr, bridge.nuvio_player_get_subtitle_track_id(playerPtr, index))
            }
        }
        override fun setSubtitleUri(url: String) = runIfOpen { bridge.nuvio_player_set_subtitle_url(playerPtr, url) }
        override fun clearExternalSubtitle() = runIfOpen { bridge.nuvio_player_clear_external_subtitle(playerPtr) }
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = runIfOpen {
            val trackId = if (trackIndex >= 0 && trackIndex < bridge.nuvio_player_get_subtitle_track_count(playerPtr)) {
                bridge.nuvio_player_get_subtitle_track_id(playerPtr, trackIndex)
            } else {
                -1
            }
            bridge.nuvio_player_clear_external_subtitle_and_select(playerPtr, trackId)
        }
        override fun release() = releaseSoft()
        override fun setOnCloseCallback(callback: () -> Unit) { onCloseCallback = callback }
        override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) { onAddonSubtitlesFetchCallback = callback }
        override fun setOnSourcesRequestedCallback(callback: () -> Unit) { onSourcesRequestedCallback = callback }
        override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) { onSourceStreamSelectedCallback = callback }
        override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) { onSourceFilterChangedCallback = callback }
        override fun setOnSourceReloadCallback(callback: () -> Unit) { onSourceReloadCallback = callback }
        override fun setOnEpisodesRequestedCallback(callback: () -> Unit) { onEpisodesRequestedCallback = callback }
        override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) { onEpisodeSelectedCallback = callback }
        override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) { onEpisodeStreamSelectedCallback = callback }
        override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) { onEpisodeFilterChangedCallback = callback }
        override fun setOnEpisodeReloadCallback(callback: () -> Unit) { onEpisodeReloadCallback = callback }
        override fun setOnEpisodeBackCallback(callback: () -> Unit) { onEpisodeBackCallback = callback }
        override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) = runIfOpen {
            bridge.nuvio_player_set_addon_subtitles_loading(playerPtr, isLoading)
            if (!isLoading) {
                bridge.nuvio_player_clear_addon_subtitles(playerPtr)
                subtitles.forEach { bridge.nuvio_player_add_addon_subtitle(playerPtr, it.id, it.url, it.language, it.display) }
            }
        }
        override fun pushSourceData(streams: List<StreamItem>, groups: List<AddonStreamGroup>, loading: Boolean, selectedFilter: String?, currentStreamUrl: String?) = Unit
        override fun pushEpisodes(episodes: List<MetaVideo>) = Unit
        override fun pushEpisodeStreamsData(streams: List<StreamItem>, groups: List<AddonStreamGroup>, loading: Boolean, selectedFilter: String?, currentStreamUrl: String?) = Unit
        override fun switchSource(url: String, audioUrl: String?, headersJson: String?) = runIfOpen { bridge.nuvio_player_load_file(playerPtr, url, audioUrl, headersJson) }

        private fun runIfOpen(block: () -> Unit) {
            if (!closed) runCatching(block).onFailure { DesktopRuntimeLog.error("Native bridge controller command failed", it) }
        }
    }

    companion object {
        fun create(): Result<NativeBridgeDesktopPlayerBackend> =
            runCatching {
                val bridge = NativeBridgeRuntimeLocator.loadBridgeOrNull()
                    ?: error("Native bridge is not available")
                NativeBridgeDesktopPlayerBackend(bridge)
            }
    }
}
