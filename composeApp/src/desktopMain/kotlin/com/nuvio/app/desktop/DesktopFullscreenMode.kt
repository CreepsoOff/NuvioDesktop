package com.nuvio.app.desktop

internal enum class DesktopFullscreenMode(val storageValue: String) {
    NativeBorderless("native_borderless"),
    AwtExclusive("awt_exclusive"),
    ComposeFullscreen("compose_fullscreen"),
    ;

    companion object {
        fun fromStorage(value: String?): DesktopFullscreenMode =
            entries.firstOrNull { it.storageValue == value } ?: NativeBorderless
    }
}

internal object DesktopFullscreenModePreference {
    private const val preferencesName = "nuvio_player_settings"
    private const val fullscreenModeKey = "fullscreen_mode"

    fun load(): DesktopFullscreenMode =
        DesktopFullscreenMode.fromStorage(DesktopPreferences.getString(preferencesName, fullscreenModeKey))

    fun save(mode: DesktopFullscreenMode) {
        DesktopPreferences.putString(preferencesName, fullscreenModeKey, mode.storageValue)
    }
}
