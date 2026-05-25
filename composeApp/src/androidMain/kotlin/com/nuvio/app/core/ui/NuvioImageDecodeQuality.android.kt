package com.nuvio.app.core.ui

internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx.coerceAtLeast(1)

internal actual fun String.upgradeTmdbImageQuality(): String {
    if (!contains("image.tmdb.org/t/p/", ignoreCase = true)) return this
    return replace(Regex("/[wh]\\d+/"), "/original/")
}
