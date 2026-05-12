package com.nuvio.app.features.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.selectedVariantUrl
        } else {
            bestProgressive?.url
        }

        val separatedVideoUrl = bestVideo?.url?.let { resolveReachableUrlOrNull(it) }
        val combinedCandidateUrl = combinedUrl?.let { resolveReachableUrlOrNull(it) }
        val videoUrl = separatedVideoUrl ?: combinedCandidateUrl ?: return@withContext null
        val audioUrl = if (!separatedVideoUrl.isNullOrBlank()) {
            bestAudio?.url?.let { resolveReachableUrlOrNull(it) }
        } else {
            null
        }

        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val httpUrl = url.toHttpUrlOrNull() ?: return if (isUrlReachable(url)) url else null
        val mnParam = httpUrl.queryParameter("mn") ?: return if (isUrlReachable(url)) url else null
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) {
            return if (isUrlReachable(url)) url else null
        }

        val host = httpUrl.host
        val candidates = mutableListOf(url)
        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
            if (altHost != host) {
                candidates += url.replace(host, altHost)
            }
        }

        return coroutineScope {
            val probes = candidates.map { candidate ->
                async(Dispatchers.IO) {
                    if (isUrlReachable(candidate)) candidate else null
                }
            }
            withTimeoutOrNull(2_000L) {
                probes.awaitAll().firstOrNull { !it.isNullOrBlank() }
            }
        }
    }

    private fun isUrlReachable(url: String): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .headers(buildHeaders(defaultHeaders))
                .build()

            probeClient.newCall(request).execute().use { response ->
                response.code in 200..299
            }
        }.getOrDefault(false)

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}
