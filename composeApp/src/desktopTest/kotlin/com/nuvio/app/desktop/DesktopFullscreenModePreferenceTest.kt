package com.nuvio.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFullscreenModePreferenceTest {
    @Test
    fun invalidFullscreenModeFallsBackToNativeBorderless() {
        assertEquals(DesktopFullscreenMode.NativeBorderless, DesktopFullscreenMode.fromStorage(null))
        assertEquals(DesktopFullscreenMode.NativeBorderless, DesktopFullscreenMode.fromStorage("unknown"))
        assertEquals(DesktopFullscreenMode.NativeBorderless, DesktopFullscreenMode.fromStorage("native_borderless"))
    }

    @Test
    fun fullscreenModePreferencePersistsEverySupportedMode() {
        DesktopPreferences.clearNode("nuvio_player_settings")

        DesktopFullscreenMode.entries.forEach { mode ->
            DesktopFullscreenModePreference.save(mode)
            assertEquals(mode, DesktopFullscreenModePreference.load())
        }
    }
}
