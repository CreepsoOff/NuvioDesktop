package com.nuvio.app.features.settings

import com.nuvio.app.desktop.DesktopPreferences

private const val homePrefsNamespace = "nuvio_home_settings"
private const val alwaysAnimateGifKey = "always_animate_gif"

internal actual object AlwaysAnimateGifPreference {
    actual val isSupported: Boolean = true

    actual fun load(): Boolean =
        DesktopPreferences.getBoolean(homePrefsNamespace, alwaysAnimateGifKey) ?: false

    actual fun save(enabled: Boolean) {
        DesktopPreferences.putBoolean(homePrefsNamespace, alwaysAnimateGifKey, enabled)
    }
}
