package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import kotlin.math.roundToInt

/**
 * Per-platform sampler used by every Coil/AsyncImage call site.
 *
 * - Android, iOS, macOS/Linux Desktop: [FilterQuality.High] (Skia Mitchell bicubic).
 *   Matches the look the rest of the app was authored against.
 * - Windows Desktop: [FilterQuality.Medium] (Skia linear + nearest mipmap).
 *
 * Why a Windows-only override: Compose Multiplatform on Windows is forced onto
 * Skiko's OpenGL backend (libmpv shares its GL context with Skiko, see
 * `composeApp/build.gradle.kts` `-Dskiko.renderApi=OPENGL`). Mitchell-Netravali
 * resampling on the GL backend is visibly ringy/"crispy" on the heavy
 * downscales we do for posters and collection covers (TMDB sources are
 * 780–3840 px, our shelf cards are 130–260 px wide). Metal on macOS and the
 * iOS native renderer hide that under their own sampling, so they look clean.
 *
 * Linear + mipmap is what native macOS/iOS image views use by default and is
 * what makes those builds look the way they do. Switching Windows to it is
 * the smallest correct change that brings the rendered output in line.
 */
internal expect val NuvioImageFilterQuality: FilterQuality

internal expect fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int

@Composable
internal fun rememberSizedImageRequest(
    imageUrl: String?,
    width: Dp,
    height: Dp,
    memoryCacheKeyPrefix: String,
): ImageRequest? {
    val platformContext = LocalPlatformContext.current
    val density = LocalDensity.current
    val widthPx = nuvioQualityDecodeDimensionPx(with(density) { width.roundToPx() }.coerceAtLeast(1))
    val heightPx = nuvioQualityDecodeDimensionPx(with(density) { height.roundToPx() }.coerceAtLeast(1))
    val resolvedImageUrl = remember(imageUrl) { imageUrl?.upgradeTmdbImageQuality() }

    return remember(platformContext, resolvedImageUrl, widthPx, heightPx, memoryCacheKeyPrefix) {
        resolvedImageUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                ImageRequest.Builder(platformContext)
                    .data(url)
                    .size(Size(widthPx, heightPx))
                    .precision(Precision.EXACT)
                    .memoryCacheKey("$memoryCacheKeyPrefix:$widthPx:$heightPx:${url.hashCode()}")
                    .diskCacheKey(url)
                    .build()
            }
    }
}

internal fun Int.roundUpToQualityBucket(bucketPx: Int): Int {
    if (this <= 0) return bucketPx
    return (((this + bucketPx - 1) / bucketPx) * bucketPx).coerceAtLeast(bucketPx)
}

internal fun Int.scaleQualityDimension(multiplier: Float, maxPx: Int): Int =
    (this * multiplier).roundToInt()
        .coerceAtLeast(this)
        .coerceAtMost(maxPx)
