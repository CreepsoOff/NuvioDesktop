package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import java.time.LocalDate

actual object CurrentDateProvider {
    actual fun todayIsoDate(): String = LocalDate.now().toString()
}

internal actual object WatchProgressClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object ContinueWatchingPreferencesStorage {
    private const val preferencesName = "nuvio_continue_watching_preferences"
    private const val payloadKey = "continue_watching_preferences_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}

actual object ContinueWatchingEnrichmentStorage {
    private const val preferencesName = "nuvio_cw_enrichment"

    actual fun loadPayload(key: String): String? =
        DesktopPreferences.getString(preferencesName, key)

    actual fun savePayload(key: String, payload: String) {
        DesktopPreferences.putString(preferencesName, key, payload)
    }

    actual fun removePayload(key: String) {
        DesktopPreferences.remove(preferencesName, key)
    }
}

actual object ResumePromptStorage {
    private const val preferencesName = "nuvio_resume_prompt"
    private const val wasInPlayerKey = "was_in_player"
    private const val lastPlayerVideoIdKey = "last_player_video_id"

    actual fun loadWasInPlayer(): Boolean =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(wasInPlayerKey)) ?: false

    actual fun saveWasInPlayer(value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(wasInPlayerKey), value)
    }

    actual fun loadLastPlayerVideoId(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(lastPlayerVideoIdKey))

    actual fun saveLastPlayerVideoId(videoId: String?) {
        DesktopPreferences.putNullableString(
            preferencesName,
            ProfileScopedKey.of(lastPlayerVideoIdKey),
            videoId,
        )
    }
}

internal actual object WatchProgressStorage {
    private const val preferencesName = "nuvio_watch_progress"
    private const val payloadKey = "watch_progress_payload"

    actual fun loadPayload(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, "${payloadKey}_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        DesktopPreferences.putString(preferencesName, "${payloadKey}_$profileId", payload)
    }
}
