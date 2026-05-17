package com.nuvio.app.features.player.desktop.mpv

internal const val DesktopDecoderPreferencesName = "nuvio_decoder_settings"
internal const val DesktopHwdecModeKey = "hwdec_mode"
internal const val DesktopHdrModeKey = "hdr_mode"

internal data class MpvRuntimeOption(
    val name: String,
    val value: String,
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

internal fun hdrRuntimeOptions(mode: DesktopHdrMode): List<MpvRuntimeOption> =
    when (mode) {
        DesktopHdrMode.Auto -> listOf(
            MpvRuntimeOption("tone-mapping", "auto"),
            MpvRuntimeOption("hdr-compute-peak", "auto"),
            MpvRuntimeOption("target-prim", "auto"),
            MpvRuntimeOption("target-trc", "auto"),
            MpvRuntimeOption("target-peak", "auto"),
        )
        DesktopHdrMode.ToneMapToSdr -> listOf(
            MpvRuntimeOption("target-prim", "bt.709"),
            MpvRuntimeOption("target-trc", "srgb"),
            MpvRuntimeOption("target-peak", "203"),
            MpvRuntimeOption("tone-mapping", "mobius"),
            MpvRuntimeOption("hdr-compute-peak", "auto"),
            MpvRuntimeOption("gamut-mapping", "desaturate"),
        )
    }
