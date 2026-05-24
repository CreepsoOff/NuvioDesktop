package com.nuvio.app.features.player

import kotlinx.serialization.Serializable

@Serializable
data class PlayerRoute(
    val launchId: Long,
)

data class PlayerLaunch(
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val bingeGroup: String? = null,
    val pauseDescription: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val contentType: String? = null,
    val videoId: String? = null,
    val parentMetaId: String,
    val parentMetaType: String,
    val initialPositionMs: Long = 0L,
    val initialProgressFraction: Float? = null,
)

object PlayerLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, PlayerLaunch>()

    fun put(launch: PlayerLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): PlayerLaunch? = launches[launchId]

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }
}

enum class PlayerResizeMode {
    Fit,
    Fill,
    Zoom,
}

enum class PlayerVideoOutputPreset {
    Native,
    ToneMappedSdr,
    Compatibility,
    Custom,
}

enum class PlayerToneMappingMode(val mpvValue: String) {
    Auto("auto"),
    Bt2390("bt.2390"),
    Mobius("mobius"),
    Reinhard("reinhard"),
    Hable("hable"),
    Gamma("gamma"),
    Clip("clip"),
}

enum class PlayerTargetPrimaries(val mpvValue: String) {
    Auto("auto"),
    Bt709("bt.709"),
    DisplayP3("display-p3"),
    Bt2020("bt.2020"),
}

enum class PlayerTargetTransfer(val mpvValue: String) {
    Auto("auto"),
    Srgb("srgb"),
    Bt1886("bt.1886"),
    Gamma22("gamma2.2"),
    Gamma24("gamma2.4"),
    Pq("pq"),
    Hlg("hlg"),
}

enum class PlayerHardwareDecoderMode(val mpvValue: String) {
    Auto("auto"),
    Off("no"),
    D3d11va("d3d11va"),
    D3d11vaCopy("d3d11va-copy"),
    Dxva2("dxva2"),
    Nvdec("nvdec"),
    NvdecCopy("nvdec-copy"),
}

data class PlayerVideoTuningSettings(
    val outputPreset: PlayerVideoOutputPreset = PlayerVideoOutputPreset.Native,
    val hardwareDecoderMode: PlayerHardwareDecoderMode = PlayerHardwareDecoderMode.Auto,
    val toneMappingMode: PlayerToneMappingMode = PlayerToneMappingMode.Auto,
    val targetPrimaries: PlayerTargetPrimaries = PlayerTargetPrimaries.Auto,
    val targetTransfer: PlayerTargetTransfer = PlayerTargetTransfer.Auto,
    val hdrComputePeakEnabled: Boolean = true,
    val debandEnabled: Boolean = false,
    val interpolationEnabled: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
)

enum class IosVideoOutputPreset(
    val label: String,
    val description: String,
) {
    NativeEdr(
        label = "Native EDR",
        description = "Best for HDR-capable iPhones and iPads.",
    ),
    SdrToneMapped(
        label = "SDR tone mapped",
        description = "More predictable whites and blacks on SDR-style output.",
    ),
    Compatibility(
        label = "Compatibility",
        description = "Closest to the older iOS MPV behavior.",
    ),
    Custom(
        label = "Custom",
        description = "Use your advanced values below.",
    ),
}

enum class IosToneMappingMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt2390("bt.2390", "BT.2390"),
    Mobius("mobius", "Mobius"),
    Reinhard("reinhard", "Reinhard"),
    Hable("hable", "Hable"),
    Gamma("gamma", "Gamma"),
    Clip("clip", "Clip"),
}

enum class IosTargetPrimaries(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt709("bt.709", "BT.709"),
    DisplayP3("display-p3", "Display P3"),
    Bt2020("bt.2020", "BT.2020"),
}

enum class IosTargetTransfer(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Srgb("srgb", "sRGB"),
    Bt1886("bt.1886", "BT.1886"),
    Gamma22("gamma2.2", "Gamma 2.2"),
    Gamma24("gamma2.4", "Gamma 2.4"),
    Pq("pq", "PQ"),
    Hlg("hlg", "HLG"),
}

enum class IosHardwareDecoderMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    VideoToolbox("videotoolbox", "VideoToolbox"),
    Off("no", "Off"),
}
data class PlayerPlaybackSnapshot(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)
