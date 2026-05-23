package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.PlayerTargetPrimaries
import com.nuvio.app.features.player.PlayerTargetTransfer
import com.nuvio.app.features.player.PlayerToneMappingMode
import com.nuvio.app.features.player.PlayerVideoOutputPreset
import com.nuvio.app.features.player.PlayerVideoTuningSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMpvPlaybackSettingsTest {
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
}
