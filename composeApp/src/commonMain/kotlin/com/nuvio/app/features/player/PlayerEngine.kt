package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Cross-platform boundary used by [PlayerScreen].
 *
 * Platform backends may support only a subset of the optional methods below.
 * Unsupported operations must be safe no-ops, and commands issued after
 * release/close must be ignored by the platform controller.
 */
interface PlayerEngineController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun retry()
    fun setPlaybackSpeed(speed: Float)
    fun currentVolume(): PlayerAudioLevel? = null
    fun setVolume(level: Float): PlayerAudioLevel? = null
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)
    fun setSubtitleUri(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun release() {}
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun setMetadata(
        title: String,
        streamTitle: String,
        providerName: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        artwork: String? = null,
        logo: String? = null,
    ) {}
    fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {}
    fun showSkipButton(type: String, endTimeMs: Long) {}
    fun hideSkipButton() {}
    fun showNextEpisode(
        season: Int,
        episode: Int,
        title: String,
        thumbnail: String? = null,
        hasAired: Boolean = true,
    ) {}
    fun hideNextEpisode() {}
    fun setOnCloseCallback(callback: () -> Unit) {}
    fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {}
    fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {}
    fun setOnSourcesRequestedCallback(callback: () -> Unit) {}
    fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {}
    fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {}
    fun setOnSourceReloadCallback(callback: () -> Unit) {}
    fun setOnEpisodesRequestedCallback(callback: () -> Unit) {}
    fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {}
    fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {}
    fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {}
    fun setOnEpisodeReloadCallback(callback: () -> Unit) {}
    fun setOnEpisodeBackCallback(callback: () -> Unit) {}
    fun pushSourceData(
        streams: List<com.nuvio.app.features.streams.StreamItem>,
        groups: List<com.nuvio.app.features.streams.AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {}
    fun pushEpisodes(episodes: List<com.nuvio.app.features.details.MetaVideo>) {}
    fun pushEpisodeStreamsData(
        streams: List<com.nuvio.app.features.streams.StreamItem>,
        groups: List<com.nuvio.app.features.streams.AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {}
    fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {}

    /**
     * Optional native-controller source switch hook.
     *
     * Desktop backends should treat this as best-effort. The Compose player UI
     * primarily switches sources by updating the active PlatformPlayerSurface
     * request; desktop MPV reloads media in-place when this hook is invoked by
     * native/controller chrome.
     */
    fun switchSource(url: String, audioUrl: String?, headersJson: String?) {}
}

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    useNativeController: Boolean = false,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
