package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object StreamBadgeSettingsStorage {
    private const val preferencesName = "nuvio_stream_badge_settings"
    private const val legacyDebridPreferencesName = "nuvio_debrid_settings"
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey)

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(showFileSizeBadgesKey))

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(showFileSizeBadgesKey), enabled)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        DesktopPreferences.getString(legacyDebridPreferencesName, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))

    actual fun clearLegacyDebridStreamBadgeRules() {
        DesktopPreferences.remove(legacyDebridPreferencesName, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    private fun loadString(key: String): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(key), value)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(key))
        }
        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
    }
}
