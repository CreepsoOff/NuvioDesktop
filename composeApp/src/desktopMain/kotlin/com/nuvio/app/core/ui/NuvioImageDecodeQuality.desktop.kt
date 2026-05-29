package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality

private const val DesktopQualityDecodeMultiplier = 2.0f
private const val DesktopQualityDecodeBucketPx = 64
private const val DesktopQualityDecodeMaxDimensionPx = 2560
private val TmdbImageSizeSegment = Regex("/(?:original|[wh]\\d+)/")

private val isWindowsDesktop: Boolean =
    System.getProperty("os.name")
        ?.contains("windows", ignoreCase = true) == true

/**
 * Sampler used by every Coil/AsyncImage call site on Desktop.
 *
 * Stays at [FilterQuality.High] across all targets. On Windows the
 * actual downscale work happens inside `NuvioImageBridge.dll` (WIC's
 * `WICBitmapInterpolationModeHighQualityCubic`) before Coil even hands
 * the bitmap to Skia, so the Compose draw-time sampler operates on a
 * 1:1 (or near-1:1) bitmap and the FilterQuality knob is no longer the
 * dominant factor in image quality. See
 * `core/imaging/WicCoilImageDecoder.kt`.
 */
internal actual val NuvioImageFilterQuality: FilterQuality = FilterQuality.High

/**
 * Decode dimension picked for Coil.
 *
 * - macOS / Linux Desktop: 2× over-decode. Pairs with Mitchell sampling
 *   under Metal/Vulkan to give the GPU a denser source under fractional
 *   zoom and rounded clipping; visually clean.
 * - Windows Desktop: **exact display size**. The WIC decoder
 *   (`core/imaging/WicCoilImageDecoder.kt`) produces a bitmap at the
 *   precise pixel target of the layout, so Skia's draw-time blit is a
 *   1:1 copy with at most a tiny edge crop — no Mitchell resampling on
 *   the OpenGL backend, no aliasing.
 *
 *   Important: do NOT round up to the 64 px decode bucket on Windows.
 *   Even a small mismatch (say 212 → 256) forces Skia to redownscale
 *   the WIC output by ~1.2× at draw time, which is precisely the
 *   worst-case ratio for Mitchell ringing on OpenGL — visible as the
 *   "crispy" poster artifacts inside large card presets ("Grand"
 *   = 212dp). The bucket rounding was useful when Skia did the decode
 *   sampling itself; with WIC handling the resample, exact size wins.
 *
 *   This holds for every image source — TMDB, Cinemeta, Better Posters,
 *   any custom addon — because the contract is "WIC owns the resample,
 *   Skia owns the blit", not "this URL pattern needs special handling".
 */
internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int {
    val displayPx = displayDimensionPx.coerceAtLeast(1)
    if (isWindowsDesktop) {
        return displayPx.coerceAtMost(DesktopQualityDecodeMaxDimensionPx)
    }
    return displayPx
        .scaleQualityDimension(
            multiplier = DesktopQualityDecodeMultiplier,
            maxPx = DesktopQualityDecodeMaxDimensionPx,
        )
        .roundUpToQualityBucket(DesktopQualityDecodeBucketPx)
}

/**
 * Default URL upgrade used by the common UI for TMDB sources. Stays at
 * `/original/` so non-Windows targets continue to receive the highest
 * quality upstream source. Per-callsite code that knows the actual
 * layout pixel target should call [tmdbBucketedUrl] instead so we do
 * not download a 4K JPEG to render a 260 px shelf card.
 */
internal actual fun String.upgradeTmdbImageQuality(): String {
    if (!contains("image.tmdb.org/t/p/", ignoreCase = true)) return this
    return replace(TmdbImageSizeSegment, "/original/")
}

/**
 * Pick the smallest TMDB CDN bucket that's >= [targetWidthPx] with a 1.5×
 * headroom for high-DPI scaling, fractional zoom, and rounded clipping.
 *
 * Buckets are TMDB's documented `/t/p/{size}/` set. Anything above the
 * largest bucket (1280 px) escalates to `/original/`.
 *
 * Returns the input unchanged for non-TMDB hosts.
 */
internal fun String.tmdbBucketedUrl(targetWidthPx: Int): String {
    if (!contains("image.tmdb.org/t/p/", ignoreCase = true)) return this
    val needed = (targetWidthPx.coerceAtLeast(1) * 1.5f).toInt()
    val bucket = when {
        needed <= 92 -> "w92"
        needed <= 154 -> "w154"
        needed <= 185 -> "w185"
        needed <= 342 -> "w342"
        needed <= 500 -> "w500"
        needed <= 780 -> "w780"
        needed <= 1280 -> "w1280"
        else -> "original"
    }
    return replace(TmdbImageSizeSegment, "/$bucket/")
}
