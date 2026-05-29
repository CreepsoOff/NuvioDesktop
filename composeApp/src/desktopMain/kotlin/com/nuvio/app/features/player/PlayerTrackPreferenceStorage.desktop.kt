package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object PlayerTrackPreferenceStorage {
    private const val preferencesName = "nuvio_player_track_preferences"
    private const val subtitleTypeKey = "subtitle_type"
    private const val subtitleLanguageKey = "subtitle_language"
    private const val subtitleNameKey = "subtitle_name"
    private const val subtitleTrackIdKey = "subtitle_track_id"
    private const val addonSubtitleIdKey = "addon_subtitle_id"
    private const val addonSubtitleUrlKey = "addon_subtitle_url"
    private const val addonSubtitleAddonNameKey = "addon_subtitle_addon_name"
    private const val audioLanguageKey = "audio_language"
    private const val audioNameKey = "audio_name"
    private const val audioTrackIdKey = "audio_track_id"
    private const val subtitleDelayMsKey = "subtitle_delay_ms"

    actual fun load(contentId: String): PersistedPlayerTrackPreference? {
        val id = contentId.normalizedStorageId() ?: return null
        val preference = PersistedPlayerTrackPreference(
            subtitleType = loadString(subtitleTypeKey, id),
            subtitleLanguage = loadString(subtitleLanguageKey, id),
            subtitleName = loadString(subtitleNameKey, id),
            subtitleTrackId = loadString(subtitleTrackIdKey, id),
            addonSubtitleId = loadString(addonSubtitleIdKey, id),
            addonSubtitleUrl = loadString(addonSubtitleUrlKey, id),
            addonSubtitleAddonName = loadString(addonSubtitleAddonNameKey, id),
            audioLanguage = loadString(audioLanguageKey, id),
            audioName = loadString(audioNameKey, id),
            audioTrackId = loadString(audioTrackIdKey, id),
        )
        return preference.takeIf {
            listOf(
                it.subtitleType,
                it.subtitleLanguage,
                it.subtitleName,
                it.subtitleTrackId,
                it.addonSubtitleId,
                it.addonSubtitleUrl,
                it.addonSubtitleAddonName,
                it.audioLanguage,
                it.audioName,
                it.audioTrackId,
            ).any { value -> !value.isNullOrBlank() }
        }
    }

    actual fun save(contentId: String, preference: PersistedPlayerTrackPreference) {
        val id = contentId.normalizedStorageId() ?: return
        saveOptionalString(subtitleTypeKey, id, preference.subtitleType)
        saveOptionalString(subtitleLanguageKey, id, preference.subtitleLanguage)
        saveOptionalString(subtitleNameKey, id, preference.subtitleName)
        saveOptionalString(subtitleTrackIdKey, id, preference.subtitleTrackId)
        saveOptionalString(addonSubtitleIdKey, id, preference.addonSubtitleId)
        saveOptionalString(addonSubtitleUrlKey, id, preference.addonSubtitleUrl)
        saveOptionalString(addonSubtitleAddonNameKey, id, preference.addonSubtitleAddonName)
        saveOptionalString(audioLanguageKey, id, preference.audioLanguage)
        saveOptionalString(audioNameKey, id, preference.audioName)
        saveOptionalString(audioTrackIdKey, id, preference.audioTrackId)
    }

    actual fun loadSubtitleDelayMs(videoId: String): Int? {
        val id = videoId.normalizedStorageId() ?: return null
        return DesktopPreferences.getInt(preferencesName, scopedKey(subtitleDelayMsKey, id))
    }

    actual fun saveSubtitleDelayMs(videoId: String, delayMs: Int) {
        val id = videoId.normalizedStorageId() ?: return
        DesktopPreferences.putInt(
            preferencesName,
            scopedKey(subtitleDelayMsKey, id),
            delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS),
        )
    }

    private fun loadString(field: String, contentId: String): String? =
        DesktopPreferences.getString(preferencesName, scopedKey(field, contentId))
            ?.takeIf { it.isNotBlank() }

    private fun saveOptionalString(field: String, contentId: String, value: String?) {
        val key = scopedKey(field, contentId)
        if (value.isNullOrBlank()) {
            DesktopPreferences.remove(preferencesName, key)
        } else {
            DesktopPreferences.putString(preferencesName, key, value)
        }
    }

    private fun scopedKey(field: String, contentId: String): String =
        ProfileScopedKey.of("$field|$contentId")

    private fun String.normalizedStorageId(): String? =
        trim().takeIf { it.isNotBlank() }
}
