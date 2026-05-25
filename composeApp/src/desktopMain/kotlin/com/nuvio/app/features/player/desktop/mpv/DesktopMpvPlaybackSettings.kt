package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.features.player.IosHardwareDecoderMode
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.IosToneMappingMode
import com.nuvio.app.features.player.IosVideoOutputPreset
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerTargetPrimaries
import com.nuvio.app.features.player.PlayerTargetTransfer
import com.nuvio.app.features.player.PlayerToneMappingMode
import com.nuvio.app.features.player.PlayerVideoOutputPreset
import com.nuvio.app.features.player.PlayerVideoTuningSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal const val DesktopDecoderPreferencesName = "nuvio_decoder_settings"
internal const val DesktopHwdecModeKey = "hwdec_mode"
internal const val DesktopHdrModeKey = "hdr_mode"
internal const val DesktopVideoOutputPresetKey = "video_output_preset"
internal const val DesktopToneMappingModeKey = "tone_mapping_mode"
internal const val DesktopTargetPrimariesKey = "target_primaries"
internal const val DesktopTargetTransferKey = "target_transfer"
internal const val DesktopHdrComputePeakKey = "hdr_compute_peak"
internal const val DesktopDebandEnabledKey = "deband_enabled"
internal const val DesktopInterpolationEnabledKey = "interpolation_enabled"
internal const val DesktopBrightnessKey = "brightness"
internal const val DesktopContrastKey = "contrast"
internal const val DesktopSaturationKey = "saturation"
internal const val DesktopGammaKey = "gamma"

internal object DesktopMpvPlaybackSettingsSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun notifyChanged() {
        _version.update { it + 1 }
    }
}

internal data class MpvRuntimeOption(
    val name: String,
    val value: String,
    val applyTiming: MpvRuntimeOptionApplyTiming = MpvRuntimeOptionApplyTiming.PlaybackHeavy,
)

internal enum class MpvRuntimeOptionApplyTiming {
    LoadOnly,
    PlaybackHeavy,
    PlaybackSafe,
}

internal data class DesktopMpvVideoTuning(
    val settings: PlayerVideoTuningSettings,
    val legacyHdrMode: DesktopHdrMode,
)

internal enum class DesktopHdrMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    Auto(
        storageValue = "auto",
        label = "Auto (recommended)",
        description = "Let mpv pick the best HDR and tone-mapping path for the current display.",
    ),
    ToneMapToSdr(
        storageValue = "tone_map_sdr",
        label = "Tone map to SDR",
        description = "Map HDR video into the app's SDR desktop surface for consistent colors.",
    );

    companion object {
        fun fromStorage(value: String?): DesktopHdrMode =
            entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

internal fun loadDesktopMpvVideoTuning(): DesktopMpvVideoTuning {
    val legacyHdrMode = DesktopHdrMode.fromStorage(
        DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopHdrModeKey),
    )
    val preset = DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey)
        ?.enumValueOrNull<PlayerVideoOutputPreset>()
        ?: legacyHdrMode.toVideoOutputPreset()

    return DesktopMpvVideoTuning(
        settings = PlayerVideoTuningSettings(
            outputPreset = preset,
            hardwareDecoderMode = loadHardwareDecoderMode(),
            toneMappingMode = loadEnum(DesktopToneMappingModeKey, preset.defaultToneMapping()),
            targetPrimaries = loadEnum(DesktopTargetPrimariesKey, preset.defaultPrimaries()),
            targetTransfer = loadEnum(DesktopTargetTransferKey, preset.defaultTransfer()),
            hdrComputePeakEnabled = DesktopPreferences.getBoolean(
                DesktopDecoderPreferencesName,
                DesktopHdrComputePeakKey,
            ) ?: true,
            debandEnabled = DesktopPreferences.getBoolean(DesktopDecoderPreferencesName, DesktopDebandEnabledKey) ?: false,
            interpolationEnabled = DesktopPreferences.getBoolean(
                DesktopDecoderPreferencesName,
                DesktopInterpolationEnabledKey,
            ) ?: false,
            brightness = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopBrightnessKey)?.coerceVideoEq()
                ?: 0,
            contrast = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopContrastKey)?.coerceVideoEq()
                ?: 0,
            saturation = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopSaturationKey)?.coerceVideoEq()
                ?: 0,
            gamma = DesktopPreferences.getInt(DesktopDecoderPreferencesName, DesktopGammaKey)?.coerceVideoEq()
                ?: 0,
        ),
        legacyHdrMode = legacyHdrMode,
    )
}

internal fun storeDesktopVideoOutputPreset(preset: PlayerVideoOutputPreset) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, preset.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopToneMappingModeKey, preset.defaultToneMapping().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetPrimariesKey, preset.defaultPrimaries().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetTransferKey, preset.defaultTransfer().name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopHdrModeKey, preset.toLegacyHdrMode().storageValue)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopHardwareDecoderMode(mode: PlayerHardwareDecoderMode) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopHwdecModeKey, mode.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopToneMappingMode(mode: PlayerToneMappingMode) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopToneMappingModeKey, mode.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopTargetPrimaries(primaries: PlayerTargetPrimaries) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetPrimariesKey, primaries.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopTargetTransfer(transfer: PlayerTargetTransfer) {
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopVideoOutputPresetKey, PlayerVideoOutputPreset.Custom.name)
    DesktopPreferences.putString(DesktopDecoderPreferencesName, DesktopTargetTransferKey, transfer.name)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopBooleanTuning(key: String, value: Boolean) {
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, key, value)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopIntTuning(key: String, value: Int) {
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, key, value.coerceVideoEq())
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun storeDesktopVideoTuningFromPlayerSettings(settings: PlayerSettingsUiState) {
    val outputPreset = settings.iosVideoOutputPreset.toDesktopPreset()
    val nextSettings = PlayerVideoTuningSettings(
        outputPreset = outputPreset,
        hardwareDecoderMode = settings.iosHardwareDecoderMode.toDesktopHardwareDecoderMode(),
        toneMappingMode = settings.iosToneMappingMode.toDesktopToneMappingMode(),
        targetPrimaries = settings.iosTargetPrimaries.toDesktopTargetPrimaries(),
        targetTransfer = settings.iosTargetTransfer.toDesktopTargetTransfer(),
        hdrComputePeakEnabled = settings.iosHdrComputePeakEnabled,
        debandEnabled = settings.iosDebandEnabled,
        interpolationEnabled = settings.iosInterpolationEnabled,
        brightness = settings.iosBrightness.coerceVideoEq(),
        contrast = settings.iosContrast.coerceVideoEq(),
        saturation = settings.iosSaturation.coerceVideoEq(),
        gamma = settings.iosGamma.coerceVideoEq(),
    )
    val nextLegacyHdrMode = outputPreset.toLegacyHdrMode()
    val previous = loadDesktopMpvVideoTuning()
    if (previous.settings == nextSettings && previous.legacyHdrMode == nextLegacyHdrMode) return

    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopVideoOutputPresetKey,
        nextSettings.outputPreset.name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopHwdecModeKey,
        nextSettings.hardwareDecoderMode.name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopToneMappingModeKey,
        nextSettings.toneMappingMode.name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopTargetPrimariesKey,
        nextSettings.targetPrimaries.name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopTargetTransferKey,
        nextSettings.targetTransfer.name,
    )
    DesktopPreferences.putString(
        DesktopDecoderPreferencesName,
        DesktopHdrModeKey,
        nextLegacyHdrMode.storageValue,
    )
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopHdrComputePeakKey, nextSettings.hdrComputePeakEnabled)
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopDebandEnabledKey, nextSettings.debandEnabled)
    DesktopPreferences.putBoolean(DesktopDecoderPreferencesName, DesktopInterpolationEnabledKey, nextSettings.interpolationEnabled)
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopBrightnessKey, nextSettings.brightness)
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopContrastKey, nextSettings.contrast)
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopSaturationKey, nextSettings.saturation)
    DesktopPreferences.putInt(DesktopDecoderPreferencesName, DesktopGammaKey, nextSettings.gamma)
    DesktopMpvPlaybackSettingsSignal.notifyChanged()
}

internal fun mpvRuntimeOptions(tuning: DesktopMpvVideoTuning): List<MpvRuntimeOption> {
    val settings = tuning.settings
    val targetPeak = when (settings.outputPreset) {
        PlayerVideoOutputPreset.ToneMappedSdr -> "203"
        else -> "auto"
    }
    return listOf(
        *stremioBaselineRuntimeOptions().toTypedArray(),
        MpvRuntimeOption("hwdec", settings.hardwareDecoderMode.mpvValue, MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("tone-mapping", settings.toneMappingMode.mpvValue),
        MpvRuntimeOption("hdr-compute-peak", if (settings.hdrComputePeakEnabled) "auto" else "no"),
        MpvRuntimeOption("target-prim", settings.targetPrimaries.mpvValue),
        MpvRuntimeOption("target-trc", settings.targetTransfer.mpvValue),
        MpvRuntimeOption("target-peak", targetPeak),
        MpvRuntimeOption("gamut-mapping", if (settings.outputPreset == PlayerVideoOutputPreset.ToneMappedSdr) "desaturate" else "auto"),
        MpvRuntimeOption("deband", if (settings.debandEnabled) "yes" else "no"),
        MpvRuntimeOption("interpolation", if (settings.interpolationEnabled) "yes" else "no"),
        MpvRuntimeOption("video-sync", if (settings.interpolationEnabled) "display-resample" else "audio"),
        MpvRuntimeOption("brightness", settings.brightness.toString(), MpvRuntimeOptionApplyTiming.PlaybackSafe),
        MpvRuntimeOption("contrast", settings.contrast.toString(), MpvRuntimeOptionApplyTiming.PlaybackSafe),
        MpvRuntimeOption("saturation", settings.saturation.toString(), MpvRuntimeOptionApplyTiming.PlaybackSafe),
        MpvRuntimeOption("gamma", settings.gamma.toString(), MpvRuntimeOptionApplyTiming.PlaybackSafe),
        *diagnosticRuntimeOptions().toTypedArray(),
    )
}

internal fun stremioBaselineRuntimeOptions(): List<MpvRuntimeOption> =
    listOf(
        // Stremio uses `wid` + `vo=gpu-next` against a native HWND. Nuvio's
        // current in-window path uses libmpv's render API into the Compose GL
        // surface, so `vo` must stay `libmpv` or MPV opens its own native window.
        MpvRuntimeOption("demuxer-lavf-probesize", "524288", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("demuxer-lavf-analyzeduration", "0.5", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("demuxer-max-bytes", stremioCacheBytes(), MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("demuxer-max-packets", "150000000", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("cache", "yes", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("cache-pause", "no", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("cache-secs", "60", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("vd-lavc-threads", "0", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("ad-lavc-threads", "0", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("audio-fallback-to-null", "yes", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("audio-client-name", "Nuvio", MpvRuntimeOptionApplyTiming.LoadOnly),
        MpvRuntimeOption("title", "Nuvio", MpvRuntimeOptionApplyTiming.LoadOnly),
    )

private fun stremioCacheBytes(): String {
    val configured = System.getProperty("nuvio.mpv.demuxer.maxBytes")
        ?: System.getenv("NUVIO_MPV_DEMUXER_MAX_BYTES")
    return configured
        ?.toLongOrNull()
        ?.coerceIn(32L * 1024L * 1024L, 300L * 1024L * 1024L)
        ?.toString()
        ?: (128L * 1024L * 1024L).toString()
}

private fun diagnosticRuntimeOptions(): List<MpvRuntimeOption> =
    listOfNotNull(
        boundedDiagnosticOption(
            name = "hwdec",
            propertyName = "nuvio.mpv.diagnostic.hwdec",
            envName = "NUVIO_MPV_DIAGNOSTIC_HWDEC",
            allowedValues = setOf("auto", "no", "d3d11va", "d3d11va-copy", "dxva2", "nvdec", "nvdec-copy"),
        ),
        boundedDiagnosticOption(
            name = "framedrop",
            propertyName = "nuvio.mpv.diagnostic.framedrop",
            envName = "NUVIO_MPV_DIAGNOSTIC_FRAMEDROP",
            allowedValues = setOf("no", "vo", "decoder", "decoder+vo"),
        ),
        boundedDiagnosticOption(
            name = "video-sync",
            propertyName = "nuvio.mpv.diagnostic.videoSync",
            envName = "NUVIO_MPV_DIAGNOSTIC_VIDEO_SYNC",
            allowedValues = setOf(
                "audio",
                "display-resample",
                "display-resample-vdrop",
                "display-resample-desync",
                "display-vdrop",
                "display-adrop",
                "display-desync",
                "desync",
            ),
        ),
    )

private fun boundedDiagnosticOption(
    name: String,
    propertyName: String,
    envName: String,
    allowedValues: Set<String>,
): MpvRuntimeOption? {
    val value = (System.getProperty(propertyName) ?: System.getenv(envName))
        ?.trim()
        ?.lowercase()
        ?: return null
    return value
        .takeIf { it in allowedValues }
        ?.let { MpvRuntimeOption(name, it) }
}

private fun loadHardwareDecoderMode(): PlayerHardwareDecoderMode {
    val storedValue = DesktopPreferences.getString(DesktopDecoderPreferencesName, DesktopHwdecModeKey)
    return storedValue?.enumValueOrNull<PlayerHardwareDecoderMode>()
        ?: storedValue?.legacyHwdecValue()?.enumValueOrNull<PlayerHardwareDecoderMode>()
        ?: PlayerHardwareDecoderMode.Auto
}

private inline fun <reified T : Enum<T>> loadEnum(key: String, default: T): T =
    DesktopPreferences.getString(DesktopDecoderPreferencesName, key)?.enumValueOrNull<T>()
        ?: default

private inline fun <reified T : Enum<T>> String.enumValueOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

private fun String.legacyHwdecValue(): String? =
    when (this) {
        "auto" -> PlayerHardwareDecoderMode.Auto.name
        "no" -> PlayerHardwareDecoderMode.Off.name
        "d3d11va" -> PlayerHardwareDecoderMode.D3d11va.name
        "d3d11va-copy" -> PlayerHardwareDecoderMode.D3d11vaCopy.name
        "dxva2" -> PlayerHardwareDecoderMode.Dxva2.name
        "nvdec" -> PlayerHardwareDecoderMode.Nvdec.name
        "nvdec-copy" -> PlayerHardwareDecoderMode.NvdecCopy.name
        else -> null
    }

private fun DesktopHdrMode.toVideoOutputPreset(): PlayerVideoOutputPreset =
    when (this) {
        DesktopHdrMode.Auto -> PlayerVideoOutputPreset.Native
        DesktopHdrMode.ToneMapToSdr -> PlayerVideoOutputPreset.ToneMappedSdr
    }

private fun PlayerVideoOutputPreset.toLegacyHdrMode(): DesktopHdrMode =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> DesktopHdrMode.ToneMapToSdr
        else -> DesktopHdrMode.Auto
    }

private fun PlayerVideoOutputPreset.defaultToneMapping(): PlayerToneMappingMode =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerToneMappingMode.Mobius
        else -> PlayerToneMappingMode.Auto
    }

private fun PlayerVideoOutputPreset.defaultPrimaries(): PlayerTargetPrimaries =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerTargetPrimaries.Bt709
        else -> PlayerTargetPrimaries.Auto
    }

private fun PlayerVideoOutputPreset.defaultTransfer(): PlayerTargetTransfer =
    when (this) {
        PlayerVideoOutputPreset.ToneMappedSdr -> PlayerTargetTransfer.Srgb
        else -> PlayerTargetTransfer.Auto
    }

private fun IosVideoOutputPreset.toDesktopPreset(): PlayerVideoOutputPreset =
    when (this) {
        IosVideoOutputPreset.NativeEdr -> PlayerVideoOutputPreset.Native
        IosVideoOutputPreset.SdrToneMapped -> PlayerVideoOutputPreset.ToneMappedSdr
        IosVideoOutputPreset.Compatibility -> PlayerVideoOutputPreset.Compatibility
        IosVideoOutputPreset.Custom -> PlayerVideoOutputPreset.Custom
    }

private fun IosHardwareDecoderMode.toDesktopHardwareDecoderMode(): PlayerHardwareDecoderMode =
    when (this) {
        IosHardwareDecoderMode.Auto,
        IosHardwareDecoderMode.VideoToolbox -> PlayerHardwareDecoderMode.Auto
        IosHardwareDecoderMode.Off -> PlayerHardwareDecoderMode.Off
    }

private fun IosToneMappingMode.toDesktopToneMappingMode(): PlayerToneMappingMode =
    when (this) {
        IosToneMappingMode.Auto -> PlayerToneMappingMode.Auto
        IosToneMappingMode.Bt2390 -> PlayerToneMappingMode.Bt2390
        IosToneMappingMode.Mobius -> PlayerToneMappingMode.Mobius
        IosToneMappingMode.Reinhard -> PlayerToneMappingMode.Reinhard
        IosToneMappingMode.Hable -> PlayerToneMappingMode.Hable
        IosToneMappingMode.Gamma -> PlayerToneMappingMode.Gamma
        IosToneMappingMode.Clip -> PlayerToneMappingMode.Clip
    }

private fun IosTargetPrimaries.toDesktopTargetPrimaries(): PlayerTargetPrimaries =
    when (this) {
        IosTargetPrimaries.Auto -> PlayerTargetPrimaries.Auto
        IosTargetPrimaries.Bt709 -> PlayerTargetPrimaries.Bt709
        IosTargetPrimaries.DisplayP3 -> PlayerTargetPrimaries.DisplayP3
        IosTargetPrimaries.Bt2020 -> PlayerTargetPrimaries.Bt2020
    }

private fun IosTargetTransfer.toDesktopTargetTransfer(): PlayerTargetTransfer =
    when (this) {
        IosTargetTransfer.Auto -> PlayerTargetTransfer.Auto
        IosTargetTransfer.Srgb -> PlayerTargetTransfer.Srgb
        IosTargetTransfer.Bt1886 -> PlayerTargetTransfer.Bt1886
        IosTargetTransfer.Gamma22 -> PlayerTargetTransfer.Gamma22
        IosTargetTransfer.Gamma24 -> PlayerTargetTransfer.Gamma24
        IosTargetTransfer.Pq -> PlayerTargetTransfer.Pq
        IosTargetTransfer.Hlg -> PlayerTargetTransfer.Hlg
    }

private fun Int.coerceVideoEq(): Int = coerceIn(-100, 100)
