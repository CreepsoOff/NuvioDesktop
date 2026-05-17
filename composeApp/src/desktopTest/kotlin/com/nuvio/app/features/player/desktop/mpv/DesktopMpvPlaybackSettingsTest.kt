package com.nuvio.app.features.player.desktop.mpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMpvPlaybackSettingsTest {
    @Test
    fun invalidHdrModeFallsBackToAuto() {
        assertEquals(DesktopHdrMode.Auto, DesktopHdrMode.fromStorage(null))
        assertEquals(DesktopHdrMode.Auto, DesktopHdrMode.fromStorage("unknown"))
    }

    @Test
    fun toneMapToSdrUsesSdrTargetOptions() {
        val options = hdrRuntimeOptions(DesktopHdrMode.ToneMapToSdr).associate { it.name to it.value }

        assertEquals("bt.709", options["target-prim"])
        assertEquals("srgb", options["target-trc"])
        assertEquals("203", options["target-peak"])
        assertEquals("mobius", options["tone-mapping"])
        assertEquals("auto", options["hdr-compute-peak"])
        assertEquals("desaturate", options["gamut-mapping"])
    }

    @Test
    fun autoHdrLeavesDisplaySelectionAutomatic() {
        val options = hdrRuntimeOptions(DesktopHdrMode.Auto).associate { it.name to it.value }

        assertEquals("auto", options["target-prim"])
        assertEquals("auto", options["target-trc"])
        assertEquals("auto", options["target-peak"])
        assertEquals("auto", options["tone-mapping"])
        assertTrue("gamut-mapping" !in options)
    }
}
