package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
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
)

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

internal fun mpvRuntimeOptions(tuning: DesktopMpvVideoTuning): List<MpvRuntimeOption> {
    val settings = tuning.settings
    val targetPeak = when (settings.outputPreset) {
        PlayerVideoOutputPreset.ToneMappedSdr -> "203"
        else -> "auto"
    }
    return listOf(
        MpvRuntimeOption("hwdec", settings.hardwareDecoderMode.mpvValue),
        MpvRuntimeOption("tone-mapping", settings.toneMappingMode.mpvValue),
        MpvRuntimeOption("hdr-compute-peak", if (settings.hdrComputePeakEnabled) "auto" else "no"),
        MpvRuntimeOption("target-prim", settings.targetPrimaries.mpvValue),
        MpvRuntimeOption("target-trc", settings.targetTransfer.mpvValue),
        MpvRuntimeOption("target-peak", targetPeak),
        MpvRuntimeOption("gamut-mapping", if (settings.outputPreset == PlayerVideoOutputPreset.ToneMappedSdr) "desaturate" else "auto"),
        MpvRuntimeOption("deband", if (settings.debandEnabled) "yes" else "no"),
        MpvRuntimeOption("interpolation", if (settings.interpolationEnabled) "yes" else "no"),
        MpvRuntimeOption("video-sync", if (settings.interpolationEnabled) "display-resample" else "audio"),
        MpvRuntimeOption("brightness", settings.brightness.toString()),
        MpvRuntimeOption("contrast", settings.contrast.toString()),
        MpvRuntimeOption("saturation", settings.saturation.toString()),
        MpvRuntimeOption("gamma", settings.gamma.toString()),
    )
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

private fun Int.coerceVideoEq(): Int = coerceIn(-100, 100)
