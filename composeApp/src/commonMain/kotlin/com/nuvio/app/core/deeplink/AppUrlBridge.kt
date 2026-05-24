package com.nuvio.app.core.deeplink

import com.nuvio.app.features.trakt.handleTraktAuthCallbackUrl
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
    ) : AppDeepLink

    data object Downloads : AppDeepLink

    data class DevStream(
        val url: String,
        val title: String,
        val audioUrl: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val streamTitle: String = title,
        val providerName: String = "Dev stream",
        val contentType: String = "movie",
        val videoId: String = "dev-stream",
        val parentMetaId: String = videoId,
        val parentMetaType: String = contentType,
        val headers: Map<String, String> = emptyMap(),
    ) : AppDeepLink
}

object AppDeepLinkRepository {
    private val _pendingDeepLink = MutableStateFlow<AppDeepLink?>(null)
    val pendingDeepLink: StateFlow<AppDeepLink?> = _pendingDeepLink.asStateFlow()

    fun handleUrl(url: String) {
        parseAppDeepLink(url)?.let { deepLink ->
            _pendingDeepLink.value = deepLink
        }
    }

    fun markConsumed(deepLink: AppDeepLink) {
        if (_pendingDeepLink.value == deepLink) {
            _pendingDeepLink.value = null
        }
    }
}

fun handleAppUrl(url: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return

    handleTraktAuthCallbackUrl(normalizedUrl)
    AppDeepLinkRepository.handleUrl(normalizedUrl)
}

fun buildMetaDeepLinkUrl(
    type: String,
    id: String,
): String = buildString {
    append("nuvio://meta?type=")
    append(type.trim().encodeURLParameter())
    append("&id=")
    append(id.trim().encodeURLParameter())
}

fun buildDownloadsDeepLinkUrl(): String = "nuvio://downloads"

internal fun parseAppDeepLink(url: String): AppDeepLink? {
    val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return null
    if (!parsedUrl.protocol.name.equals("nuvio", ignoreCase = true)) return null

    return when (parsedUrl.host.lowercase()) {
        "meta" -> {
            val type = parsedUrl.parameters["type"]?.trim().orEmpty()
            val id = parsedUrl.parameters["id"]?.trim().orEmpty()
            if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
        }

        "downloads" -> AppDeepLink.Downloads

        "dev" -> {
            if (!parsedUrl.encodedPath.equals("/play", ignoreCase = true)) {
                null
            } else {
                parseDevStreamDeepLink(parsedUrl)
            }
        }

        "dev-stream" -> parseDevStreamDeepLink(parsedUrl)

        else -> null
    }
}

private fun parseDevStreamDeepLink(parsedUrl: Url): AppDeepLink.DevStream? {
    val streamUrl = parsedUrl.parameters["url"]?.trim().orEmpty()
    if (streamUrl.isBlank()) return null

    val title = parsedUrl.parameters["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "Dev stream"
    val streamTitle = parsedUrl.parameters["streamTitle"]?.trim()?.takeIf { it.isNotBlank() } ?: title
    val providerName = parsedUrl.parameters["provider"]?.trim()?.takeIf { it.isNotBlank() } ?: "Dev stream"
    val contentType = parsedUrl.parameters["type"]?.trim()?.takeIf { it.isNotBlank() } ?: "movie"
    val videoId = parsedUrl.parameters["videoId"]?.trim()?.takeIf { it.isNotBlank() } ?: "dev-stream"
    val parentMetaId = parsedUrl.parameters["parentMetaId"]?.trim()?.takeIf { it.isNotBlank() } ?: videoId
    val parentMetaType = parsedUrl.parameters["parentMetaType"]?.trim()?.takeIf { it.isNotBlank() } ?: contentType

    return AppDeepLink.DevStream(
        url = streamUrl,
        title = title,
        audioUrl = parsedUrl.parameters["audioUrl"]?.trim()?.takeIf { it.isNotBlank() },
        poster = parsedUrl.parameters["poster"]?.trim()?.takeIf { it.isNotBlank() },
        background = parsedUrl.parameters["background"]?.trim()?.takeIf { it.isNotBlank() },
        streamTitle = streamTitle,
        providerName = providerName,
        contentType = contentType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        headers = parsedUrl.parameters.getAll("header")
            ?.mapNotNull(::parseHeaderParameter)
            ?.toMap()
            .orEmpty(),
    )
}

private fun parseHeaderParameter(value: String): Pair<String, String>? {
    val separatorIndex = value.indexOf(':').takeIf { it > 0 } ?: value.indexOf('=').takeIf { it > 0 } ?: return null
    val name = value.substring(0, separatorIndex).trim()
    val headerValue = value.substring(separatorIndex + 1).trim()
    if (name.isBlank() || headerValue.isBlank()) return null
    return name to headerValue
}
