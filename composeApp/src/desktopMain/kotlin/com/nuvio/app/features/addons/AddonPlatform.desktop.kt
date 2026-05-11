package com.nuvio.app.features.addons

import com.nuvio.app.desktop.DesktopPreferences
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

internal actual object AddonStorage {
    private const val preferencesName = "nuvio_addons"
    private const val addonUrlsKey = "installed_manifest_urls"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        DesktopPreferences.getString(preferencesName, "${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        DesktopPreferences.putString(
            preferencesName,
            "${addonUrlsKey}_$profileId",
            urls.joinToString(separator = "\n"),
        )
    }
}

// Same OkHttp transport as Android (com.squareup.okhttp3:okhttp:4.12.0). OkHttp's
// HttpUrl parser is permissive enough to accept characters like `|` directly in
// the path, which is required by Stremio/Torrentio addon configuration URLs.
// Unlike Android we don't force IPv4-first DNS (that's a workaround for Android
// emulator/network setups) and we don't override the proxy, so Windows users
// keep their system proxy by default.
private val addonHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private const val maxRawResponseBodyBytes = 1024 * 1024
private const val truncationSuffix = "\n...[truncated]"

// Stremio/Torrentio addons embed configuration in the URL path using `|` as a
// separator. Torrentio's router requires the literal pipe — `%7C` returns 404.
// Re-decode any `%7C` to `|` here so URLs that arrived already-encoded (e.g.
// synced from Android via Supabase, pasted from a configurator, or persisted
// by an older Desktop build that percent-encoded them) still hit the right
// route on Desktop.
private fun normalizeDesktopAddonRequestUrl(url: String): String =
    url.trim()
        .replace("%7C", "|", ignoreCase = true)

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private fun readResponseBodyLimited(body: ResponseBody?): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { stream ->
        readAtMostBytes(stream, maxRawResponseBodyBytes)
    }

    val decoded = try {
        String(readResult.bytes, charset)
    } catch (_: Exception) {
        String(readResult.bytes, Charsets.UTF_8)
    }

    return if (readResult.truncated) {
        decoded + truncationSuffix
    } else {
        decoded
    }
}

private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val bytes = body.bytes()
    return runCatching {
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset)
    }.getOrElse {
        String(bytes, Charsets.UTF_8)
    }
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String = withContext(Dispatchers.IO) {
    try {
        val normalizedMethod = method.uppercase()
        val sanitizedHeaders = headers.withoutAcceptEncoding()
        val builder = Request.Builder().url(normalizeDesktopAddonRequestUrl(url))
        sanitizedHeaders.forEach { (key, value) ->
            builder.header(key, value)
        }

        val request = if (requestAllowsBody(normalizedMethod)) {
            val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
                ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
            // Preserve exact media type and avoid implicit charset rewriting used in signed APIs.
            val requestBody = body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())
            builder.method(normalizedMethod, requestBody)
        } else {
            builder.method(normalizedMethod, null)
        }.build()

        addonHttpClient.newCall(request).execute().use { response ->
            val payload = readResponseBody(response.body)
            if (!response.isSuccessful) {
                error("Request failed with HTTP ${response.code}")
            }
            if (payload.isBlank()) {
                throw IllegalStateException("Empty response body")
            }
            payload
        }
    } catch (e: Exception) {
        throw e
    }
}

actual suspend fun httpGetText(url: String): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
): RawHttpResponse =
    withContext(Dispatchers.IO) {
        val normalizedMethod = method.uppercase()
        val sanitizedHeaders = headers.withoutAcceptEncoding()
        val builder = Request.Builder().url(normalizeDesktopAddonRequestUrl(url))
        sanitizedHeaders.forEach { (key, value) ->
            builder.header(key, value)
        }

        val request = if (requestAllowsBody(normalizedMethod)) {
            val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
                ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
            val requestBody = body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())
            builder.method(normalizedMethod, requestBody)
        } else {
            builder.method(normalizedMethod, null)
        }.build()

        val client = if (followRedirects) {
            addonHttpClient
        } else {
            addonHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        client.newCall(request).execute().use { response ->
            RawHttpResponse(
                status = response.code,
                statusText = response.message,
                url = response.request.url.toString(),
                body = readResponseBodyLimited(response.body),
                headers = response.headers.toMultimap().mapValues { (_, values) ->
                    values.joinToString(",")
                }.mapKeys { (name, _) ->
                    name.lowercase()
                },
            )
        }
    }
