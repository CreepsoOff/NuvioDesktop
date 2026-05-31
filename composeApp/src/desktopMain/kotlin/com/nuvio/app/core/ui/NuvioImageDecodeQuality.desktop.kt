package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality

private const val DesktopQualityDecodeMultiplier = 2.0f
private const val DesktopQualityDecodeBucketPx = 64
private const val DesktopQualityDecodeMaxDimensionPx = 2560
private val TmdbImageSizeSegment = Regex("/(?:original|[wh]\\d+)/")

/**
 * Windows Desktop runs on the Skiko OpenGL backend (libmpv shares its GL
 * context). Its resampler aliases visibly on draw-time downscales, so on
 * Windows the decode dimension must equal the measured draw size and never
 * over-decode. macOS/Linux Desktop keep the existing 2x oversample path.
 */
private val isWindowsDesktop: Boolean by lazy {
    System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
}

/**
 * Sampler used by every Coil/AsyncImage call site on Desktop.
 *
 * Stays at [FilterQuality.High] across all targets so Windows follows
 * the same visual contract as macOS/Linux Desktop instead of a separate
 * post-render filtering path.
 */
internal actual val NuvioImageFilterQuality: FilterQuality = FilterQuality.High

/**
 * Decode dimension picked for Coil.
 *
 * On **Windows Desktop** with native WIC rendering enabled (the default, see
 * [WindowsImageRenderingPreference]), the decoded bitmap must equal the measured
 * pixel draw size (clamped `>= 1`) — no 2x multiplier and no 64px bucket rounding —
 * so the Skiko OpenGL backend blits ~1:1 and never performs a draw-time downscale
 * resample (the "crispy"/ringy artifact). When the user opts into legacy Skia
 * rendering, Windows falls back to the same 2x over-decode + bucket path as the
 * other Desktop targets (Skia owns the resample).
 *
 * On **macOS/Linux Desktop** it keeps the 2x over-decode rounded to stable
 * buckets, giving rounded cards / fractional scaling enough source pixels
 * before final draw.
 */
internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int {
    val displayPx = displayDimensionPx.coerceAtLeast(1)
    if (isWindowsDesktop && WindowsImageRenderingPreference.nativeWicEnabled) return displayPx
    return displayPx
        .scaleQualityDimension(
            multiplier = DesktopQualityDecodeMultiplier,
            maxPx = DesktopQualityDecodeMaxDimensionPx,
        )
        .roundUpToQualityBucket(DesktopQualityDecodeBucketPx)
}

/**
 * Default URL upgrade used by the common UI for TMDB sources. Stays at
 * `/original/` so Desktop receives the same highest-quality upstream
 * source as the macOS reference path.
 */
internal actual fun String.upgradeTmdbImageQuality(): String {
    if (!contains("image.tmdb.org/t/p/", ignoreCase = true)) return this
    return replace(TmdbImageSizeSegment, "/original/")
}
