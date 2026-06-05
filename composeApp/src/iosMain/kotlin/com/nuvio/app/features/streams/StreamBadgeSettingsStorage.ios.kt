package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

actual object StreamBadgeSettingsStorage {
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"
    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey)

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? {
        val key = ProfileScopedKey.of(showFileSizeBadgesKey)
        return if (NSUserDefaults.standardUserDefaults.objectForKey(key) != null) {
            NSUserDefaults.standardUserDefaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(showFileSizeBadgesKey))
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        loadString(legacyDebridStreamBadgeRulesKey)

    actual fun clearLegacyDebridStreamBadgeRules() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    private fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(key))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
    }
}
