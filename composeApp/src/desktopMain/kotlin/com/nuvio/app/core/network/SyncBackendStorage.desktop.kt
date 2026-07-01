package com.nuvio.app.core.network

import com.nuvio.app.desktop.DesktopPreferences
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual object SyncBackendStorage {
    private const val PREFERENCES_NAME = "nuvio_sync_backend"
    private const val KEY_SELECTION_PAYLOAD = "selection_payload_v1"

    actual fun loadSelectionPayload(): String? =
        DesktopPreferences.getString(PREFERENCES_NAME, KEY_SELECTION_PAYLOAD)

    actual fun saveSelectionPayload(payload: String) {
        DesktopPreferences.putString(PREFERENCES_NAME, KEY_SELECTION_PAYLOAD, payload)
    }
}

private val syncBackendHttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual suspend fun fetchSyncBackendManifestText(url: String): String =
    withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = syncBackendHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Sync backend manifest request failed with HTTP ${response.statusCode()}")
        }

        response.body()?.takeIf { it.isNotBlank() }
            ?: error("Sync backend manifest response was empty")
    }
