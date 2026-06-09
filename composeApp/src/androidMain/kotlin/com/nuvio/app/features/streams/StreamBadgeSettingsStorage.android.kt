package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object StreamBadgeSettingsStorage {
    private const val preferencesName = "nuvio_stream_badge_settings"
    private const val legacyDebridPreferencesName = "nuvio_debrid_settings"
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val streamBadgePlacementKey = "stream_badge_placement"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, streamBadgePlacementKey)

    private var preferences: SharedPreferences? = null
    private var legacyDebridPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        legacyDebridPreferences = context.getSharedPreferences(legacyDebridPreferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? {
        val key = ProfileScopedKey.of(showFileSizeBadgesKey)
        return preferences?.takeIf { it.contains(key) }?.getBoolean(key, true)
    }

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(showFileSizeBadgesKey), enabled)
            ?.apply()
    }

    actual fun loadStreamBadgePlacement(): String? = loadString(streamBadgePlacementKey)

    actual fun saveStreamBadgePlacement(placement: String) {
        saveString(streamBadgePlacementKey, placement)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        legacyDebridPreferences?.getString(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey), null)

    actual fun clearLegacyDebridStreamBadgeRules() {
        legacyDebridPreferences
            ?.edit()
            ?.remove(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
            ?.apply()
    }

    private fun loadString(key: String): String? =
        preferences?.getString(ProfileScopedKey.of(key), null)

    private fun saveString(key: String, value: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(key), value)
            ?.apply()
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        preferences?.edit()?.apply {
            syncKeys.forEach { remove(ProfileScopedKey.of(it)) }
        }?.apply()

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
    }
}
