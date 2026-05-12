package com.nuvio.app.core.logging

private val sensitiveQueryKeys = setOf(
    "access_token",
    "apikey",
    "api_key",
    "auth",
    "authorization",
    "client_secret",
    "expires",
    "hash",
    "key",
    "signature",
    "sig",
    "token",
)

internal fun String.redactedUrlForLog(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return "url=blank"
    val host = trimmed.substringAfter("://", trimmed)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .ifBlank { "unknown" }
    val sensitiveQueryCount = trimmed.substringAfter("?", "")
        .substringBefore("#")
        .split('&')
        .count { part ->
            val key = part.substringBefore('=').lowercase()
            key in sensitiveQueryKeys || sensitiveQueryKeys.any { sensitive -> key.contains(sensitive) }
        }
    return "host=$host len=${trimmed.length} hash=${trimmed.hashCode().toUInt().toString(16)} sensitiveQueryKeys=$sensitiveQueryCount"
}

internal fun Map<String, String>?.redactedHeadersForLog(): String {
    val headers = this ?: return "headers=none"
    if (headers.isEmpty()) return "headers=empty"
    val names = headers.keys.map { key ->
        when {
            key.equals("authorization", ignoreCase = true) -> "authorization=<redacted>"
            key.equals("cookie", ignoreCase = true) -> "cookie=<redacted>"
            key.contains("token", ignoreCase = true) -> "$key=<redacted>"
            key.contains("key", ignoreCase = true) -> "$key=<redacted>"
            else -> key
        }
    }
    return "headers=${names.joinToString(prefix = "[", postfix = "]")}"
}
