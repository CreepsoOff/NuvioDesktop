package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowPlacement
import com.nuvio.app.LocalDesktopWindow
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncFloat
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.decodeSyncStringSet
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncFloat
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.sync.encodeSyncStringSet
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.features.player.desktop.DesktopPlayerSurfaceHost
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import java.awt.Cursor
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val isMacOS: Boolean by lazy {
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
}

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    if (isMacOS) {
        MacOSPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    } else {
        DesktopPlayerSurfaceHost(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    }
}

// macOS: existing JNA bridge (unchanged)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MacOSPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val bridge = remember { DesktopMPVBridgeLib.INSTANCE }
    val playerPtr = remember { bridge.nuvio_player_create() }
    var onCloseCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onAddonSubtitlesFetchCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourcesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourceStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onSourceFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onSourceReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onEpisodeReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeBackCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(playerPtr) {
        bridge.nuvio_player_show(playerPtr)
        onDispose {
            bridge.nuvio_player_destroy(playerPtr)
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl) {
        val headersJson = if (sourceHeaders.isNotEmpty()) {
            buildJsonObject {
                sourceHeaders.forEach { (k, v) -> put(k, v) }
            }.toString()
        } else null
        bridge.nuvio_player_load_file(playerPtr, sourceUrl, sourceAudioUrl, headersJson)
        if (playWhenReady) {
            bridge.nuvio_player_play(playerPtr)
        }
    }

    LaunchedEffect(resizeMode) {
        val mode = when (resizeMode) {
            PlayerResizeMode.Fit -> 0
            PlayerResizeMode.Fill -> 1
            PlayerResizeMode.Zoom -> 2
        }
        bridge.nuvio_player_set_resize_mode(playerPtr, mode)
    }

    val controller = remember(playerPtr) {
        object : PlayerEngineController {
            override fun play() = bridge.nuvio_player_play(playerPtr)
            override fun pause() = bridge.nuvio_player_pause(playerPtr)
            override fun seekTo(positionMs: Long) = bridge.nuvio_player_seek_to(playerPtr, positionMs)
            override fun seekBy(offsetMs: Long) = bridge.nuvio_player_seek_by(playerPtr, offsetMs)
            override fun retry() = bridge.nuvio_player_retry(playerPtr)
            override fun setPlaybackSpeed(speed: Float) = bridge.nuvio_player_set_speed(playerPtr, speed)

            override fun getAudioTracks(): List<AudioTrack> {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                return (0 until count).map { i ->
                    AudioTrack(
                        index = i,
                        id = bridge.nuvio_player_get_audio_track_id(playerPtr, i).toString(),
                        label = bridge.nuvio_player_get_audio_track_label(playerPtr, i) ?: "",
                        language = bridge.nuvio_player_get_audio_track_lang(playerPtr, i),
                        isSelected = bridge.nuvio_player_is_audio_track_selected(playerPtr, i),
                    )
                }
            }

            override fun getSubtitleTracks(): List<SubtitleTrack> {
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                return (0 until count).map { i ->
                    SubtitleTrack(
                        index = i,
                        id = bridge.nuvio_player_get_subtitle_track_id(playerPtr, i).toString(),
                        label = bridge.nuvio_player_get_subtitle_track_label(playerPtr, i) ?: "",
                        language = bridge.nuvio_player_get_subtitle_track_lang(playerPtr, i),
                        isSelected = bridge.nuvio_player_is_subtitle_track_selected(playerPtr, i),
                    )
                }
            }

            override fun selectAudioTrack(index: Int) {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                if (index in 0 until count) {
                    val trackId = bridge.nuvio_player_get_audio_track_id(playerPtr, index)
                    bridge.nuvio_player_select_audio_track(playerPtr, trackId)
                }
            }

            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    bridge.nuvio_player_select_subtitle_track(playerPtr, -1)
                    return
                }
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                if (index in 0 until count) {
                    val trackId = bridge.nuvio_player_get_subtitle_track_id(playerPtr, index)
                    bridge.nuvio_player_select_subtitle_track(playerPtr, trackId)
                }
            }

            override fun setSubtitleUri(url: String) =
                bridge.nuvio_player_set_subtitle_url(playerPtr, url)

            override fun clearExternalSubtitle() =
                bridge.nuvio_player_clear_external_subtitle(playerPtr)

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                val trackId = if (trackIndex >= 0) {
                    val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                    if (trackIndex < count) bridge.nuvio_player_get_subtitle_track_id(playerPtr, trackIndex) else -1
                } else -1
                bridge.nuvio_player_clear_external_subtitle_and_select(playerPtr, trackId)
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) {
                val colorHex = style.textColor.toMpvColorString()
                val outline = if (style.outlineEnabled) 2.0f else 0.0f
                val subPos = 100 - style.bottomOffset
                bridge.nuvio_player_apply_subtitle_style(
                    playerPtr, colorHex, outline, style.fontSizeSp.toFloat(), subPos,
                )
            }

            override fun setMetadata(
                title: String,
                streamTitle: String,
                providerName: String,
                seasonNumber: Int?,
                episodeNumber: Int?,
                episodeTitle: String?,
                artwork: String?,
                logo: String?,
            ) {
                bridge.nuvio_player_set_metadata(
                    playerPtr, title, streamTitle, providerName,
                    seasonNumber ?: 0, episodeNumber ?: 0, episodeTitle,
                    artwork, logo,
                )
            }

            override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
                bridge.nuvio_player_set_has_video_id(playerPtr, hasVideoId)
                bridge.nuvio_player_set_is_series(playerPtr, isSeries)
            }

            override fun showSkipButton(type: String, endTimeMs: Long) {
                bridge.nuvio_player_show_skip_button(playerPtr, type, endTimeMs)
            }

            override fun hideSkipButton() {
                bridge.nuvio_player_hide_skip_button(playerPtr)
            }

            override fun showNextEpisode(
                season: Int,
                episode: Int,
                title: String,
                thumbnail: String?,
                hasAired: Boolean,
            ) {
                bridge.nuvio_player_show_next_episode(playerPtr, season, episode, title, thumbnail, hasAired)
            }

            override fun hideNextEpisode() {
                bridge.nuvio_player_hide_next_episode(playerPtr)
            }

            override fun setOnCloseCallback(callback: () -> Unit) {
                onCloseCallback = callback
            }

            override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {
                onAddonSubtitlesFetchCallback = callback
            }

            override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
                bridge.nuvio_player_set_addon_subtitles_loading(playerPtr, isLoading)
                if (!isLoading) {
                    bridge.nuvio_player_clear_addon_subtitles(playerPtr)
                    subtitles.forEach { addon ->
                        bridge.nuvio_player_add_addon_subtitle(
                            playerPtr, addon.id, addon.url, addon.language, addon.display,
                        )
                    }
                }
            }

            override fun setOnSourcesRequestedCallback(callback: () -> Unit) {
                onSourcesRequestedCallback = callback
            }

            override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {
                onSourceStreamSelectedCallback = callback
            }

            override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {
                onSourceFilterChangedCallback = callback
            }

            override fun setOnSourceReloadCallback(callback: () -> Unit) {
                onSourceReloadCallback = callback
            }

            override fun setOnEpisodesRequestedCallback(callback: () -> Unit) {
                onEpisodesRequestedCallback = callback
            }

            override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {
                onEpisodeSelectedCallback = callback
            }

            override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {
                onEpisodeStreamSelectedCallback = callback
            }

            override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {
                onEpisodeFilterChangedCallback = callback
            }

            override fun setOnEpisodeReloadCallback(callback: () -> Unit) {
                onEpisodeReloadCallback = callback
            }

            override fun setOnEpisodeBackCallback(callback: () -> Unit) {
                onEpisodeBackCallback = callback
            }

            override fun pushSourceData(
                streams: List<StreamItem>,
                groups: List<AddonStreamGroup>,
                loading: Boolean,
                selectedFilter: String?,
                currentStreamUrl: String?,
            ) {
                bridge.nuvio_player_set_sources_loading(playerPtr, loading)
                bridge.nuvio_player_set_source_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_source_addon_groups(playerPtr)
                groups.forEach { g ->
                    bridge.nuvio_player_add_source_addon_group(
                        playerPtr, g.addonId, g.addonName, g.addonId, g.isLoading, g.error != null,
                    )
                }
                bridge.nuvio_player_clear_source_streams(playerPtr)
                streams.forEach { s ->
                    bridge.nuvio_player_add_source_stream(
                        playerPtr, s.addonId + "_" + (s.url ?: s.infoHash ?: ""),
                        s.streamLabel, s.streamSubtitle, s.addonName, s.addonId,
                        s.directPlaybackUrl ?: "", s.directPlaybackUrl == currentStreamUrl,
                    )
                }
            }

            override fun pushEpisodes(episodes: List<MetaVideo>) {
                bridge.nuvio_player_clear_episodes(playerPtr)
                episodes.forEach { ep ->
                    bridge.nuvio_player_add_episode(
                        playerPtr, ep.id, ep.title, ep.overview, ep.thumbnail,
                        ep.season ?: 0, ep.episode ?: 0,
                    )
                }
            }

            override fun pushEpisodeStreamsData(
                streams: List<StreamItem>,
                groups: List<AddonStreamGroup>,
                loading: Boolean,
                selectedFilter: String?,
                currentStreamUrl: String?,
            ) {
                bridge.nuvio_player_set_episode_streams_loading(playerPtr, loading)
                bridge.nuvio_player_set_episode_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_episode_addon_groups(playerPtr)
                groups.forEach { g ->
                    bridge.nuvio_player_add_episode_addon_group(
                        playerPtr, g.addonId, g.addonName, g.addonId, g.isLoading, g.error != null,
                    )
                }
                bridge.nuvio_player_clear_episode_streams(playerPtr)
                streams.forEach { s ->
                    bridge.nuvio_player_add_episode_stream(
                        playerPtr, s.addonId + "_" + (s.url ?: s.infoHash ?: ""),
                        s.streamLabel, s.streamSubtitle, s.addonName, s.addonId,
                        s.directPlaybackUrl ?: "", s.directPlaybackUrl == currentStreamUrl,
                    )
                }
            }

            override fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {
                bridge.nuvio_player_show_episode_streams(playerPtr, season ?: 0, episode ?: 0, title)
            }

            override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
                bridge.nuvio_player_load_file(playerPtr, url, audioUrl, headersJson)
            }
        }
    }

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    LaunchedEffect(playerPtr) {
        while (true) {
            delay(250)
            if (bridge.nuvio_player_is_closed(playerPtr)) {
                onCloseCallback?.invoke()
                break
            }
            bridge.nuvio_player_refresh_state(playerPtr)
            val snapshot = PlayerPlaybackSnapshot(
                isLoading = bridge.nuvio_player_is_loading(playerPtr),
                isPlaying = bridge.nuvio_player_is_playing(playerPtr),
                isEnded = bridge.nuvio_player_is_ended(playerPtr),
                positionMs = bridge.nuvio_player_get_position_ms(playerPtr),
                durationMs = bridge.nuvio_player_get_duration_ms(playerPtr),
                bufferedPositionMs = bridge.nuvio_player_get_buffered_ms(playerPtr),
                playbackSpeed = bridge.nuvio_player_get_speed(playerPtr),
            )
            onSnapshot(snapshot)
            val error = bridge.nuvio_player_get_error(playerPtr)
            onError(error)
            if (bridge.nuvio_player_is_addon_subtitles_fetch_requested(playerPtr)) {
                onAddonSubtitlesFetchCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_subtitle_style_changed(playerPtr)) {
                val colorIndex = bridge.nuvio_player_get_subtitle_style_color_index(playerPtr)
                    .coerceIn(0, SubtitleColorSwatches.lastIndex)
                val style = SubtitleStyleState(
                    textColor = SubtitleColorSwatches[colorIndex],
                    outlineEnabled = bridge.nuvio_player_get_subtitle_style_outline_enabled(playerPtr),
                    fontSizeSp = bridge.nuvio_player_get_subtitle_style_font_size(playerPtr),
                    bottomOffset = bridge.nuvio_player_get_subtitle_style_bottom_offset(playerPtr),
                )
                PlayerSettingsRepository.setSubtitleStyle(style)
            }
            if (bridge.nuvio_player_pop_next_episode_pressed(playerPtr)) {
            }
            if (bridge.nuvio_player_pop_sources_open_requested(playerPtr)) {
                onSourcesRequestedCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_episodes_open_requested(playerPtr)) {
                onEpisodesRequestedCallback?.invoke()
            }
            bridge.nuvio_player_pop_source_stream_selected(playerPtr)?.let { url ->
                onSourceStreamSelectedCallback?.invoke(url)
            }
            if (bridge.nuvio_player_pop_source_filter_changed(playerPtr)) {
                val filterValue = bridge.nuvio_player_get_source_filter_value(playerPtr)
                onSourceFilterChangedCallback?.invoke(filterValue)
            }
            if (bridge.nuvio_player_pop_source_reload(playerPtr)) {
                onSourceReloadCallback?.invoke()
            }
            bridge.nuvio_player_pop_episode_selected(playerPtr)?.let { episodeId ->
                onEpisodeSelectedCallback?.invoke(episodeId)
            }
            bridge.nuvio_player_pop_episode_stream_selected(playerPtr)?.let { url ->
                onEpisodeStreamSelectedCallback?.invoke(url)
            }
            if (bridge.nuvio_player_pop_episode_filter_changed(playerPtr)) {
                val filterValue = bridge.nuvio_player_get_episode_filter_value(playerPtr)
                onEpisodeFilterChangedCallback?.invoke(filterValue)
            }
            if (bridge.nuvio_player_pop_episode_reload(playerPtr)) {
                onEpisodeReloadCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_episode_back(playerPtr)) {
                onEpisodeBackCallback?.invoke()
            }
        }
    }

    Box(modifier = modifier.background(Color.Black))
}

private fun androidx.compose.ui.graphics.Color.toMpvColorString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#${r.hex()}${g.hex()}${b.hex()}${a.hex()}"
}

private fun Int.hex(): String = toString(16).padStart(2, '0').uppercase()

internal actual object DeviceLanguagePreferences {
    actual fun preferredLanguageCodes(): List<String> =
        listOfNotNull(Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() })
}

internal actual object PlayerSettingsStorage {
    private const val preferencesName = "nuvio_player_settings"
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val resizeModeKey = "resize_mode"
    private const val holdToSpeedEnabledKey = "hold_to_speed_enabled"
    private const val holdToSpeedValueKey = "hold_to_speed_value"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val subtitleTextColorKey = "subtitle_text_color"
    private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
    private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
    private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"
    private const val streamAutoPlayModeKey = "stream_auto_play_mode"
    private const val streamAutoPlaySourceKey = "stream_auto_play_source"
    private const val streamAutoPlaySelectedAddonsKey = "stream_auto_play_selected_addons"
    private const val streamAutoPlaySelectedPluginsKey = "stream_auto_play_selected_plugins"
    private const val streamAutoPlayRegexKey = "stream_auto_play_regex"
    private const val streamAutoPlayTimeoutSecondsKey = "stream_auto_play_timeout_seconds"
    private const val skipIntroEnabledKey = "skip_intro_enabled"
    private const val animeSkipEnabledKey = "animeskip_enabled"
    private const val animeSkipClientIdKey = "animeskip_client_id"
    private const val introDbApiKeyKey = "intro_db_api_key"
    private const val introSubmitEnabledKey = "intro_submit_enabled"
    private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
    private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
    private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
    private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
    private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"
    private const val useLibassKey = "use_libass"
    private const val libassRenderTypeKey = "libass_render_type"
    private val syncKeys = listOf(
        showLoadingOverlayKey,
        resizeModeKey,
        holdToSpeedEnabledKey,
        holdToSpeedValueKey,
        preferredAudioLanguageKey,
        secondaryPreferredAudioLanguageKey,
        preferredSubtitleLanguageKey,
        secondaryPreferredSubtitleLanguageKey,
        streamReuseLastLinkEnabledKey,
        streamReuseLastLinkCacheHoursKey,
        decoderPriorityKey,
        mapDV7ToHevcKey,
        tunnelingEnabledKey,
        streamAutoPlayModeKey,
        streamAutoPlaySourceKey,
        streamAutoPlaySelectedAddonsKey,
        streamAutoPlaySelectedPluginsKey,
        streamAutoPlayRegexKey,
        streamAutoPlayTimeoutSecondsKey,
        skipIntroEnabledKey,
        animeSkipEnabledKey,
        animeSkipClientIdKey,
        introDbApiKeyKey,
        introSubmitEnabledKey,
        streamAutoPlayNextEpisodeEnabledKey,
        streamAutoPlayPreferBingeGroupKey,
        nextEpisodeThresholdModeKey,
        nextEpisodeThresholdPercentKey,
        nextEpisodeThresholdMinutesBeforeEndKey,
        useLibassKey,
        libassRenderTypeKey,
    )

    actual fun loadShowLoadingOverlay(): Boolean? = loadBoolean(showLoadingOverlayKey)

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        saveBoolean(showLoadingOverlayKey, enabled)
    }

    actual fun loadResizeMode(): String? = loadString(resizeModeKey)

    actual fun saveResizeMode(mode: String) {
        saveString(resizeModeKey, mode)
    }

    actual fun loadHoldToSpeedEnabled(): Boolean? = loadBoolean(holdToSpeedEnabledKey)

    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {
        saveBoolean(holdToSpeedEnabledKey, enabled)
    }

    actual fun loadHoldToSpeedValue(): Float? = loadFloat(holdToSpeedValueKey)

    actual fun saveHoldToSpeedValue(speed: Float) {
        saveFloat(holdToSpeedValueKey, speed)
    }

    actual fun loadPreferredAudioLanguage(): String? = loadString(preferredAudioLanguageKey)

    actual fun savePreferredAudioLanguage(language: String) {
        saveString(preferredAudioLanguageKey, language)
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? = loadString(secondaryPreferredAudioLanguageKey)

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        saveNullableString(secondaryPreferredAudioLanguageKey, language)
    }

    actual fun loadPreferredSubtitleLanguage(): String? = loadString(preferredSubtitleLanguageKey)

    actual fun savePreferredSubtitleLanguage(language: String) {
        saveString(preferredSubtitleLanguageKey, language)
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? = loadString(secondaryPreferredSubtitleLanguageKey)

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        saveNullableString(secondaryPreferredSubtitleLanguageKey, language)
    }

    actual fun loadSubtitleTextColor(): String? = loadString(subtitleTextColorKey)

    actual fun saveSubtitleTextColor(colorHex: String) {
        saveString(subtitleTextColorKey, colorHex)
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? = loadBoolean(subtitleOutlineEnabledKey)

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        saveBoolean(subtitleOutlineEnabledKey, enabled)
    }

    actual fun loadSubtitleFontSizeSp(): Int? = loadInt(subtitleFontSizeSpKey)

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        saveInt(subtitleFontSizeSpKey, fontSizeSp)
    }

    actual fun loadSubtitleBottomOffset(): Int? = loadInt(subtitleBottomOffsetKey)

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        saveInt(subtitleBottomOffsetKey, bottomOffset)
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? = loadBoolean(streamReuseLastLinkEnabledKey)

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        saveBoolean(streamReuseLastLinkEnabledKey, enabled)
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? = loadInt(streamReuseLastLinkCacheHoursKey)

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        saveInt(streamReuseLastLinkCacheHoursKey, hours)
    }

    actual fun loadDecoderPriority(): Int? = loadInt(decoderPriorityKey)

    actual fun saveDecoderPriority(priority: Int) {
        saveInt(decoderPriorityKey, priority)
    }

    actual fun loadMapDV7ToHevc(): Boolean? = loadBoolean(mapDV7ToHevcKey)

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        saveBoolean(mapDV7ToHevcKey, enabled)
    }

    actual fun loadTunnelingEnabled(): Boolean? = loadBoolean(tunnelingEnabledKey)

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        saveBoolean(tunnelingEnabledKey, enabled)
    }

    actual fun loadStreamAutoPlayMode(): String? = loadString(streamAutoPlayModeKey)

    actual fun saveStreamAutoPlayMode(mode: String) {
        saveString(streamAutoPlayModeKey, mode)
    }

    actual fun loadStreamAutoPlaySource(): String? = loadString(streamAutoPlaySourceKey)

    actual fun saveStreamAutoPlaySource(source: String) {
        saveString(streamAutoPlaySourceKey, source)
    }

    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? = loadStringSet(streamAutoPlaySelectedAddonsKey)

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        saveStringSet(streamAutoPlaySelectedAddonsKey, addons)
    }

    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? = loadStringSet(streamAutoPlaySelectedPluginsKey)

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        saveStringSet(streamAutoPlaySelectedPluginsKey, plugins)
    }

    actual fun loadStreamAutoPlayRegex(): String? = loadString(streamAutoPlayRegexKey)

    actual fun saveStreamAutoPlayRegex(regex: String) {
        saveString(streamAutoPlayRegexKey, regex)
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? = loadInt(streamAutoPlayTimeoutSecondsKey)

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        saveInt(streamAutoPlayTimeoutSecondsKey, seconds)
    }

    actual fun loadSkipIntroEnabled(): Boolean? = loadBoolean(skipIntroEnabledKey)

    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        saveBoolean(skipIntroEnabledKey, enabled)
    }

    actual fun loadAnimeSkipEnabled(): Boolean? = loadBoolean(animeSkipEnabledKey)

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        saveBoolean(animeSkipEnabledKey, enabled)
    }

    actual fun loadAnimeSkipClientId(): String? = loadString(animeSkipClientIdKey)

    actual fun saveAnimeSkipClientId(clientId: String) {
        saveString(animeSkipClientIdKey, clientId)
    }

    actual fun loadIntroDbApiKey(): String? = loadString(introDbApiKeyKey)

    actual fun saveIntroDbApiKey(apiKey: String) {
        saveString(introDbApiKeyKey, apiKey)
    }

    actual fun loadIntroSubmitEnabled(): Boolean? = loadBoolean(introSubmitEnabledKey)

    actual fun saveIntroSubmitEnabled(enabled: Boolean) {
        saveBoolean(introSubmitEnabledKey, enabled)
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? = loadBoolean(streamAutoPlayNextEpisodeEnabledKey)

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        saveBoolean(streamAutoPlayNextEpisodeEnabledKey, enabled)
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? = loadBoolean(streamAutoPlayPreferBingeGroupKey)

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        saveBoolean(streamAutoPlayPreferBingeGroupKey, enabled)
    }

    actual fun loadNextEpisodeThresholdMode(): String? = loadString(nextEpisodeThresholdModeKey)

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        saveString(nextEpisodeThresholdModeKey, mode)
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? = loadFloat(nextEpisodeThresholdPercentKey)

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        saveFloat(nextEpisodeThresholdPercentKey, percent)
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? = loadFloat(nextEpisodeThresholdMinutesBeforeEndKey)

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        saveFloat(nextEpisodeThresholdMinutesBeforeEndKey, minutes)
    }

    actual fun loadUseLibass(): Boolean? = loadBoolean(useLibassKey)

    actual fun saveUseLibass(enabled: Boolean) {
        saveBoolean(useLibassKey, enabled)
    }

    actual fun loadLibassRenderType(): String? = loadString(libassRenderTypeKey)

    actual fun saveLibassRenderType(renderType: String) {
        saveString(libassRenderTypeKey, renderType)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadShowLoadingOverlay()?.let { put(showLoadingOverlayKey, encodeSyncBoolean(it)) }
        loadResizeMode()?.let { put(resizeModeKey, encodeSyncString(it)) }
        loadHoldToSpeedEnabled()?.let { put(holdToSpeedEnabledKey, encodeSyncBoolean(it)) }
        loadHoldToSpeedValue()?.let { put(holdToSpeedValueKey, encodeSyncFloat(it)) }
        loadPreferredAudioLanguage()?.let { put(preferredAudioLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredAudioLanguage()?.let { put(secondaryPreferredAudioLanguageKey, encodeSyncString(it)) }
        loadPreferredSubtitleLanguage()?.let { put(preferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredSubtitleLanguage()?.let { put(secondaryPreferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadStreamReuseLastLinkEnabled()?.let { put(streamReuseLastLinkEnabledKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkCacheHours()?.let { put(streamReuseLastLinkCacheHoursKey, encodeSyncInt(it)) }
        loadDecoderPriority()?.let { put(decoderPriorityKey, encodeSyncInt(it)) }
        loadMapDV7ToHevc()?.let { put(mapDV7ToHevcKey, encodeSyncBoolean(it)) }
        loadTunnelingEnabled()?.let { put(tunnelingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayMode()?.let { put(streamAutoPlayModeKey, encodeSyncString(it)) }
        loadStreamAutoPlaySource()?.let { put(streamAutoPlaySourceKey, encodeSyncString(it)) }
        loadStreamAutoPlaySelectedAddons()?.let { put(streamAutoPlaySelectedAddonsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlaySelectedPlugins()?.let { put(streamAutoPlaySelectedPluginsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlayRegex()?.let { put(streamAutoPlayRegexKey, encodeSyncString(it)) }
        loadStreamAutoPlayTimeoutSeconds()?.let { put(streamAutoPlayTimeoutSecondsKey, encodeSyncInt(it)) }
        loadSkipIntroEnabled()?.let { put(skipIntroEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipEnabled()?.let { put(animeSkipEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipClientId()?.let { put(animeSkipClientIdKey, encodeSyncString(it)) }
        loadIntroDbApiKey()?.let { put(introDbApiKeyKey, encodeSyncString(it)) }
        loadIntroSubmitEnabled()?.let { put(introSubmitEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayNextEpisodeEnabled()?.let { put(streamAutoPlayNextEpisodeEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayPreferBingeGroup()?.let { put(streamAutoPlayPreferBingeGroupKey, encodeSyncBoolean(it)) }
        loadNextEpisodeThresholdMode()?.let { put(nextEpisodeThresholdModeKey, encodeSyncString(it)) }
        loadNextEpisodeThresholdPercent()?.let { put(nextEpisodeThresholdPercentKey, encodeSyncFloat(it)) }
        loadNextEpisodeThresholdMinutesBeforeEnd()?.let { put(nextEpisodeThresholdMinutesBeforeEndKey, encodeSyncFloat(it)) }
        loadUseLibass()?.let { put(useLibassKey, encodeSyncBoolean(it)) }
        loadLibassRenderType()?.let { put(libassRenderTypeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }

        payload.decodeSyncBoolean(showLoadingOverlayKey)?.let(::saveShowLoadingOverlay)
        payload.decodeSyncString(resizeModeKey)?.let(::saveResizeMode)
        payload.decodeSyncBoolean(holdToSpeedEnabledKey)?.let(::saveHoldToSpeedEnabled)
        payload.decodeSyncFloat(holdToSpeedValueKey)?.let(::saveHoldToSpeedValue)
        payload.decodeSyncString(preferredAudioLanguageKey)?.let(::savePreferredAudioLanguage)
        payload.decodeSyncString(secondaryPreferredAudioLanguageKey)?.let(::saveSecondaryPreferredAudioLanguage)
        payload.decodeSyncString(preferredSubtitleLanguageKey)?.let(::savePreferredSubtitleLanguage)
        payload.decodeSyncString(secondaryPreferredSubtitleLanguageKey)?.let(::saveSecondaryPreferredSubtitleLanguage)
        payload.decodeSyncBoolean(streamReuseLastLinkEnabledKey)?.let(::saveStreamReuseLastLinkEnabled)
        payload.decodeSyncInt(streamReuseLastLinkCacheHoursKey)?.let(::saveStreamReuseLastLinkCacheHours)
        payload.decodeSyncInt(decoderPriorityKey)?.let(::saveDecoderPriority)
        payload.decodeSyncBoolean(mapDV7ToHevcKey)?.let(::saveMapDV7ToHevc)
        payload.decodeSyncBoolean(tunnelingEnabledKey)?.let(::saveTunnelingEnabled)
        payload.decodeSyncString(streamAutoPlayModeKey)?.let(::saveStreamAutoPlayMode)
        payload.decodeSyncString(streamAutoPlaySourceKey)?.let(::saveStreamAutoPlaySource)
        payload.decodeSyncStringSet(streamAutoPlaySelectedAddonsKey)?.let(::saveStreamAutoPlaySelectedAddons)
        payload.decodeSyncStringSet(streamAutoPlaySelectedPluginsKey)?.let(::saveStreamAutoPlaySelectedPlugins)
        payload.decodeSyncString(streamAutoPlayRegexKey)?.let(::saveStreamAutoPlayRegex)
        payload.decodeSyncInt(streamAutoPlayTimeoutSecondsKey)?.let(::saveStreamAutoPlayTimeoutSeconds)
        payload.decodeSyncBoolean(skipIntroEnabledKey)?.let(::saveSkipIntroEnabled)
        payload.decodeSyncBoolean(animeSkipEnabledKey)?.let(::saveAnimeSkipEnabled)
        payload.decodeSyncString(animeSkipClientIdKey)?.let(::saveAnimeSkipClientId)
        payload.decodeSyncString(introDbApiKeyKey)?.let(::saveIntroDbApiKey)
        payload.decodeSyncBoolean(introSubmitEnabledKey)?.let(::saveIntroSubmitEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeEnabled)
        payload.decodeSyncBoolean(streamAutoPlayPreferBingeGroupKey)?.let(::saveStreamAutoPlayPreferBingeGroup)
        payload.decodeSyncString(nextEpisodeThresholdModeKey)?.let(::saveNextEpisodeThresholdMode)
        payload.decodeSyncFloat(nextEpisodeThresholdPercentKey)?.let(::saveNextEpisodeThresholdPercent)
        payload.decodeSyncFloat(nextEpisodeThresholdMinutesBeforeEndKey)?.let(::saveNextEpisodeThresholdMinutesBeforeEnd)
        payload.decodeSyncBoolean(useLibassKey)?.let(::saveUseLibass)
        payload.decodeSyncString(libassRenderTypeKey)?.let(::saveLibassRenderType)
    }

    private fun scopedKey(baseKey: String): String = ProfileScopedKey.of(baseKey)

    private fun loadString(key: String): String? =
        DesktopPreferences.getString(preferencesName, scopedKey(key))

    private fun saveString(key: String, value: String) {
        DesktopPreferences.putString(preferencesName, scopedKey(key), value)
    }

    private fun saveNullableString(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            DesktopPreferences.remove(preferencesName, scopedKey(key))
        } else {
            DesktopPreferences.putString(preferencesName, scopedKey(key), value)
        }
    }

    private fun loadBoolean(key: String): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, scopedKey(key))

    private fun saveBoolean(key: String, value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, scopedKey(key), value)
    }

    private fun loadInt(key: String): Int? =
        DesktopPreferences.getInt(preferencesName, scopedKey(key))

    private fun saveInt(key: String, value: Int) {
        DesktopPreferences.putInt(preferencesName, scopedKey(key), value)
    }

    private fun loadFloat(key: String): Float? =
        DesktopPreferences.getFloat(preferencesName, scopedKey(key))

    private fun saveFloat(key: String, value: Float) {
        DesktopPreferences.putFloat(preferencesName, scopedKey(key), value)
    }

    private fun loadStringSet(key: String): Set<String>? =
        DesktopPreferences.getStringSet(preferencesName, scopedKey(key))

    private fun saveStringSet(key: String, values: Set<String>) {
        DesktopPreferences.putStringSet(preferencesName, scopedKey(key), values)
    }
}

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) = Unit

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
) = Unit

@Composable
actual fun ManagePlayerCursorVisibility(visible: Boolean) {
    val window = LocalDesktopWindow.current
    val hiddenCursor = remember { createHiddenPlayerCursor() }

    DisposableEffect(window) { val keybinds = KeybindsStorage.load().binds
        val previousCursor = window?.cursor
        onDispose {
            if (window != null && previousCursor != null) {
                window.cursor = previousCursor
            }
        }
    }

    SideEffect {
        window?.cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null

@Composable
actual fun rememberPlayerFullscreenController(): PlayerFullscreenController {
    val window = LocalDesktopWindow.current as? ComposeWindow
    var isFullscreen by remember(window) {
        mutableStateOf(window?.placement == WindowPlacement.Fullscreen)
    }

    LaunchedEffect(window) {
        while (true) {
            isFullscreen = window?.isPlayerFullscreen() == true
            delay(250)
        }
    }

    return object : PlayerFullscreenController {
        override val isFullscreenSupported: Boolean
            get() = window != null

        override val isFullscreen: Boolean
            get() = isFullscreen

        override fun toggleFullscreen() {
            val composeWindow = window ?: return
            composeWindow.toggleDesktopFullscreen()
            isFullscreen = composeWindow.isPlayerFullscreen()
        }
    }
}

@Composable
actual fun ManageFullscreenKeyboardShortcuts(isHomeRouteActive: Boolean) {
    val window = LocalDesktopWindow.current as? ComposeWindow
    val currentIsHomeRouteActive by rememberUpdatedState(isHomeRouteActive)

    DisposableEffect(window) {
        val composeWindow = window ?: return@DisposableEffect onDispose {}
        val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val loadedBinds = KeybindsStorage.load().binds
        val appFullscreenCode = loadedBinds
            .firstOrNull { it.action == "toggle_app_fullscreen" }?.keyCode
            ?: KeyEvent.VK_F11
        val exitFullscreenCode = loadedBinds
            .firstOrNull { it.action == "exit_fullscreen" }?.keyCode
            ?: KeyEvent.VK_ESCAPE

        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_RELEASED) {
                return@KeyEventDispatcher false
            }
            if (event.keyCode == appFullscreenCode) {
                composeWindow.toggleDesktopFullscreen()
                return@KeyEventDispatcher true
            }
            if (event.keyCode == exitFullscreenCode) {
                if (currentIsHomeRouteActive && composeWindow.isPlayerFullscreen()) {
                    composeWindow.exitDesktopFullscreen()
                    return@KeyEventDispatcher true
                }
            }
            false
        }

        keyboardFocusManager.addKeyEventDispatcher(dispatcher)
        onDispose {
            keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
        }
    }
}



private object DesktopFullscreenState {
    var previousPlacement: WindowPlacement = WindowPlacement.Floating
}

private fun ComposeWindow.toggleDesktopFullscreen() {
    if (isPlayerFullscreen()) {
        exitDesktopFullscreen()
    } else {
        enterDesktopFullscreen()
    }
}

private fun ComposeWindow.enterDesktopFullscreen() {
    DesktopFullscreenState.previousPlacement = placement.takeIf { it != WindowPlacement.Fullscreen }
        ?: WindowPlacement.Floating
    placement = WindowPlacement.Fullscreen
}

private fun ComposeWindow.exitDesktopFullscreen() {
    exitPlayerFullscreen(DesktopFullscreenState.previousPlacement)
}

private fun ComposeWindow.isPlayerFullscreen(): Boolean {
    val fullScreenWindow = graphicsConfiguration?.device?.fullScreenWindow
    return placement == WindowPlacement.Fullscreen || fullScreenWindow === this
}

private fun ComposeWindow.exitPlayerFullscreen(previousPlacement: WindowPlacement) {
    val device = graphicsConfiguration?.device
    if (device?.fullScreenWindow === this) {
        device.fullScreenWindow = null
    }

    val targetPlacement = previousPlacement.takeIf { it != WindowPlacement.Fullscreen }
        ?: WindowPlacement.Floating
    placement = targetPlacement

    if (targetPlacement == WindowPlacement.Floating) {
        extendedState = extendedState and Frame.MAXIMIZED_BOTH.inv()
    }
}

private fun createHiddenPlayerCursor(): Cursor {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-player-hidden-cursor")
}

actual val usesNativePlayerChrome: Boolean
    get() = isMacOS

actual val usesAnimatedPlayerChrome: Boolean = false
