package com.nuvio.app.features.trakt

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.desktop.DesktopPreferences
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.trakt_logo_wordmark
import nuvio.composeapp.generated.resources.trakt_tv_favicon
import org.jetbrains.compose.resources.painterResource

internal actual object TraktAuthStorage {
    private const val preferencesName = "nuvio_trakt_auth"
    private const val payloadKey = "trakt_auth_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}

internal actual object TraktCommentsStorage {
    private const val preferencesName = "nuvio_trakt_comments"
    private const val enabledKey = "comments_enabled"
    private val syncKeys = listOf(enabledKey)

    actual fun loadEnabled(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(enabledKey))

    actual fun saveEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(enabledKey), enabled)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
    }
}

internal actual object TraktLibraryStorage {
    private const val preferencesName = "nuvio_trakt_library"
    private const val payloadKey = "trakt_library_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}

@Composable
actual fun traktBrandPainter(asset: TraktBrandAsset): Painter =
    when (asset) {
        TraktBrandAsset.Glyph -> painterResource(Res.drawable.trakt_tv_favicon)
        TraktBrandAsset.Wordmark -> painterResource(Res.drawable.trakt_logo_wordmark)
    }

internal actual object TraktPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

    actual fun parseIsoDateTimeToEpochMs(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()
}
