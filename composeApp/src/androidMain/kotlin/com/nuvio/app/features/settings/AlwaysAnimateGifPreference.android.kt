package com.nuvio.app.features.settings

internal actual object AlwaysAnimateGifPreference {
    actual val isSupported: Boolean = false
    actual fun load(): Boolean = false
    actual fun save(enabled: Boolean) = Unit
}
