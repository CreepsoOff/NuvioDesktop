package com.nuvio.app.features.settings

internal expect object AlwaysAnimateGifPreference {
    val isSupported: Boolean
    fun load(): Boolean
    fun save(enabled: Boolean)
}
