package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

actual object BingeGroupCacheStorage {
    private const val preferencesName = "nuvio_binge_group_cache"

    actual fun load(hashedKey: String): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(hashedKey))

    actual fun save(hashedKey: String, value: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(hashedKey), value)
    }

    actual fun remove(hashedKey: String) {
        DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(hashedKey))
    }
}
