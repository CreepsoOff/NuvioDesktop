package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality

private const val DesktopQualityDecodeMultiplier = 2.0f
private const val DesktopQualityDecodeBucketPx = 64
private const val DesktopQualityDecodeMaxDimensionPx = 2560
private val TmdbImageSizeSegment = Regex("/(?:original|[wh]\\d+)/")

/**
 * Sampler used by every Coil/AsyncImage call site on Desktop.
 *
 * Stays at [FilterQuality.High] across all targets so Windows follows
 * the same visual contract as macOS/Linux Desktop instead of a separate
 * post-render filtering path.
 */
internal actual val NuvioImageFilterQuality: FilterQuality = FilterQuality.High

/**
 * Decode dimension picked for Coil. Desktop keeps a 2x over-decode
 * rounded to stable buckets, matching the macOS path and giving rounded
 * cards / fractional scaling enough source pixels before final draw.
 */
internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx
        .coerceAtLeast(1)
        .scaleQualityDimension(
            multiplier = DesktopQualityDecodeMultiplier,
            maxPx = DesktopQualityDecodeMaxDimensionPx,
        )
        .roundUpToQualityBucket(DesktopQualityDecodeBucketPx)

/**
 * Default URL upgrade used by the common UI for TMDB sources. Stays at
 * `/original/` so Desktop receives the same highest-quality upstream
 * source as the macOS reference path.
 */
internal actual fun String.upgradeTmdbImageQuality(): String {
    if (!contains("image.tmdb.org/t/p/", ignoreCase = true)) return this
    return replace(TmdbImageSizeSegment, "/original/")
}
