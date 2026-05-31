package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object P2pSettingsStorage {
    private const val preferencesName = "torrent_settings"
    private const val p2pEnabledKey = "p2p_enabled"
    private const val enableUploadKey = "enable_upload"
    private const val hideTorrentStatsKey = "hide_torrent_stats"

    actual fun loadP2pEnabled(): Boolean? =
        loadBoolean(p2pEnabledKey)

    actual fun saveP2pEnabled(enabled: Boolean) {
        saveBoolean(p2pEnabledKey, enabled)
    }

    actual fun loadEnableUpload(): Boolean? =
        loadBoolean(enableUploadKey)

    actual fun saveEnableUpload(enabled: Boolean) {
        saveBoolean(enableUploadKey, enabled)
    }

    actual fun loadHideTorrentStats(): Boolean? =
        loadBoolean(hideTorrentStatsKey)

    actual fun saveHideTorrentStats(enabled: Boolean) {
        saveBoolean(hideTorrentStatsKey, enabled)
    }

    private fun loadBoolean(keyBase: String): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(keyBase))

    private fun saveBoolean(keyBase: String, value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(keyBase), value)
    }
}
