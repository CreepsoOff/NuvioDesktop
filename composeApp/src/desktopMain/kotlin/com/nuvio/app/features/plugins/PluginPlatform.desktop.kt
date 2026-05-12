package com.nuvio.app.features.plugins

import com.nuvio.app.desktop.DesktopPreferences

internal object PluginStorage {
    private const val preferencesName = "nuvio_plugins"
    private const val pluginsStateKey = "plugins_state"

    fun loadState(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, "${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        DesktopPreferences.putString(preferencesName, "${pluginsStateKey}_$profileId", payload)
    }
}

internal fun currentPluginPlatform(): String = "desktop"

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
