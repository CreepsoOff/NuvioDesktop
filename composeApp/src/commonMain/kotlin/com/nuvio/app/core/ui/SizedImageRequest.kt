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
 * [FilterQuality.High] (Skia Mitchell bicubic) on **all** targets — Android, iOS,
 * macOS/Linux Desktop, and Windows Desktop. There is no per-platform override.
 *
 * Why the sampler is the same everywhere: the sampler only matters when Skia has to
 * resample at draw time. The Windows "crispy"/ringy artifact was never a sampler
 * problem — on Windows, Compose Multiplatform is pinned to Skiko's OpenGL backend
 * (libmpv shares its GL context with Skiko, see `composeApp/build.gradle.kts`
 * `-Dskiko.renderApi=OPENGL`), and the real cause was handing Skia a bitmap larger
 * than the pixel draw size, which forced a draw-time downscale. The fix removes the
 * resample at its source: the Windows decode dimension is matched to the measured
 * draw size and a native WIC decoder owns the downscale (HighQualityCubic), so Skia
 * receives a bitmap already at the draw size and only blits ~1:1.
 *
 * Once Skia blits 1:1 (decode dimension == draw size), the sampler is irrelevant —
 * there is no resample for it to influence. Earlier investigation considered a
 * Windows-only [FilterQuality.Medium] override (linear + nearest mipmap); it was
 * ruled out and never shipped. [FilterQuality.High] is kept on every target.
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
