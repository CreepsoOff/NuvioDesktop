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

internal val NuvioImageFilterQuality: FilterQuality = FilterQuality.High

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
