package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.features.player.PlatformHardwareDecoderMode
import com.nuvio.app.features.player.PlatformTargetPrimaries
import com.nuvio.app.features.player.PlatformTargetTransfer
import com.nuvio.app.features.player.PlatformToneMappingMode
import com.nuvio.app.features.player.PlatformVideoOutputPreset
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerTargetPrimaries
import com.nuvio.app.features.player.PlayerTargetTransfer
import com.nuvio.app.features.player.PlayerToneMappingMode
import com.nuvio.app.features.player.PlayerVideoOutputPreset
import com.nuvio.app.features.player.PlayerVideoTuningSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMpvPlaybackSettingsTest {
    private fun resetDecoderPreferences() {
        DesktopPreferences.clearNode(DesktopDecoderPreferencesName)
    }

    @Test
    fun invalidHdrModeFallsBackToAuto() {
        assertEquals(DesktopHdrMode.Auto, DesktopHdrMode.fromStorage(null))
        assertEquals(DesktopHdrMode.Auto, DesktopHdrMode.fromStorage("unknown"))
    }

    @Test
    fun toneMapToSdrUsesSdrTargetOptions() {
        val options = mpvRuntimeOptions(
            DesktopMpvVideoTuning(
                settings = PlayerVideoTuningSettings(
                    outputPreset = PlayerVideoOutputPreset.ToneMappedSdr,
                    toneMappingMode = PlayerToneMappingMode.Mobius,
                    targetPrimaries = PlayerTargetPrimaries.Bt709,
                    targetTransfer = PlayerTargetTransfer.Srgb,
                ),
                legacyHdrMode = DesktopHdrMode.ToneMapToSdr,
            ),
        ).associate { it.name to it.value }

        assertEquals("bt.709", options["target-prim"])
        assertEquals("srgb", options["target-trc"])
        assertEquals("203", options["target-peak"])
        assertEquals("mobius", options["tone-mapping"])
        assertEquals("auto", options["hdr-compute-peak"])
        assertEquals("desaturate", options["gamut-mapping"])
    }

    @Test
    fun autoHdrLeavesDisplaySelectionAutomatic() {
        val options = mpvRuntimeOptions(
            DesktopMpvVideoTuning(
                settings = PlayerVideoTuningSettings(),
                legacyHdrMode = DesktopHdrMode.Auto,
            ),
        ).associate { it.name to it.value }

        assertEquals("auto", options["target-prim"])
        assertEquals("auto", options["target-trc"])
        assertEquals("auto", options["target-peak"])
        assertEquals("auto", options["tone-mapping"])
        assertEquals("auto", options["gamut-mapping"])
    }

    @Test
    fun hardwareDecoderMapsToMpvHwdecOption() {
        val options = mpvRuntimeOptions(
            DesktopMpvVideoTuning(
                settings = PlayerVideoTuningSettings(
                    hardwareDecoderMode = PlayerHardwareDecoderMode.D3d11va,
                ),
                legacyHdrMode = DesktopHdrMode.Auto,
            ),
        ).associate { it.name to it.value }

        assertEquals("d3d11va", options["hwdec"])
    }

    @Test
    fun videoEnhancementOptionsMapToMpvRuntimeOptions() {
        val options = mpvRuntimeOptions(
            DesktopMpvVideoTuning(
                settings = PlayerVideoTuningSettings(
                    debandEnabled = true,
                    interpolationEnabled = true,
                    brightness = -12,
                    contrast = 7,
                    saturation = 15,
                    gamma = -3,
                ),
                legacyHdrMode = DesktopHdrMode.Auto,
            ),
        ).associate { it.name to it.value }

        assertEquals("yes", options["deband"])
        assertEquals("yes", options["interpolation"])
        assertEquals("display-resample", options["video-sync"])
        assertEquals("-12", options["brightness"])
        assertEquals("7", options["contrast"])
        assertEquals("15", options["saturation"])
        assertEquals("-3", options["gamma"])
    }

    @Test
    fun runtimeOptionsClassifyOnlyPictureEqAsPlaybackSafe() {
        val timings = mpvRuntimeOptions(
            DesktopMpvVideoTuning(
                settings = PlayerVideoTuningSettings(
                    brightness = 5,
                    contrast = 6,
                    saturation = 7,
                    gamma = 8,
                ),
                legacyHdrMode = DesktopHdrMode.Auto,
            ),
        ).associate { it.name to it.applyTiming }

        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackSafe, timings["brightness"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackSafe, timings["contrast"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackSafe, timings["saturation"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackSafe, timings["gamma"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackHeavy, timings["tone-mapping"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackHeavy, timings["hdr-compute-peak"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackHeavy, timings["deband"])
        assertEquals(MpvRuntimeOptionApplyTiming.PlaybackHeavy, timings["interpolation"])
        assertEquals(MpvRuntimeOptionApplyTiming.LoadOnly, timings["hwdec"])
        assertEquals(MpvRuntimeOptionApplyTiming.LoadOnly, timings["demuxer-max-bytes"])
    }

    @Test
    fun desktopSettingStoresNotifyTheLiveMpvSettingsSignal() {
        resetDecoderPreferences()
        val startVersion = DesktopMpvPlaybackSettingsSignal.version.value

        storeDesktopHardwareDecoderMode(PlayerHardwareDecoderMode.D3d11va)
        storeDesktopBooleanTuning(DesktopDebandEnabledKey, true)
        storeDesktopIntTuning(DesktopBrightnessKey, 142)

        assertEquals(startVersion + 3, DesktopMpvPlaybackSettingsSignal.version.value)

        val tuning = loadDesktopMpvVideoTuning().settings
        assertEquals(PlayerHardwareDecoderMode.D3d11va, tuning.hardwareDecoderMode)
        assertEquals(true, tuning.debandEnabled)
        assertEquals(100, tuning.brightness)
    }

    @Test
    fun playerSettingsMirrorStoresPlatformVideoOptionsAsDesktopMpvTuning() {
        resetDecoderPreferences()
        val startVersion = DesktopMpvPlaybackSettingsSignal.version.value

        storeDesktopVideoTuningFromPlayerSettings(
            PlayerSettingsUiState(
                iosVideoOutputPreset = PlatformVideoOutputPreset.SdrToneMapped,
                iosHardwareDecoderMode = PlatformHardwareDecoderMode.Off,
                iosToneMappingMode = PlatformToneMappingMode.Mobius,
                iosTargetPrimaries = PlatformTargetPrimaries.Bt709,
                iosTargetTransfer = PlatformTargetTransfer.Srgb,
                iosHdrComputePeakEnabled = false,
                iosDebandEnabled = true,
                iosInterpolationEnabled = true,
                iosBrightness = -12,
                iosContrast = 7,
                iosSaturation = 15,
                iosGamma = -3,
            ),
        )

        assertEquals(startVersion + 1, DesktopMpvPlaybackSettingsSignal.version.value)

        val tuning = loadDesktopMpvVideoTuning()
        assertEquals(DesktopHdrMode.ToneMapToSdr, tuning.legacyHdrMode)
        assertEquals(PlayerVideoOutputPreset.ToneMappedSdr, tuning.settings.outputPreset)
        assertEquals(PlayerHardwareDecoderMode.Off, tuning.settings.hardwareDecoderMode)
        assertEquals(PlayerToneMappingMode.Mobius, tuning.settings.toneMappingMode)
        assertEquals(PlayerTargetPrimaries.Bt709, tuning.settings.targetPrimaries)
        assertEquals(PlayerTargetTransfer.Srgb, tuning.settings.targetTransfer)
        assertEquals(false, tuning.settings.hdrComputePeakEnabled)
        assertEquals(true, tuning.settings.debandEnabled)
        assertEquals(true, tuning.settings.interpolationEnabled)
        assertEquals(-12, tuning.settings.brightness)
        assertEquals(7, tuning.settings.contrast)
        assertEquals(15, tuning.settings.saturation)
        assertEquals(-3, tuning.settings.gamma)
    }

    @Test
    fun playerSettingsMirrorDoesNotNotifyWhenDesktopTuningIsAlreadyCurrent() {
        resetDecoderPreferences()
        val state = PlayerSettingsUiState(
            iosVideoOutputPreset = PlatformVideoOutputPreset.Compatibility,
            iosHardwareDecoderMode = PlatformHardwareDecoderMode.Auto,
            iosToneMappingMode = PlatformToneMappingMode.Bt2390,
            iosTargetPrimaries = PlatformTargetPrimaries.DisplayP3,
            iosTargetTransfer = PlatformTargetTransfer.Gamma22,
            iosBrightness = 9,
        )

        storeDesktopVideoTuningFromPlayerSettings(state)
        val afterFirstStore = DesktopMpvPlaybackSettingsSignal.version.value
        storeDesktopVideoTuningFromPlayerSettings(state)

        assertEquals(afterFirstStore, DesktopMpvPlaybackSettingsSignal.version.value)
    }
}
