package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size

@Composable
internal fun rememberSizedImageRequest(
    imageUrl: String?,
    width: Dp,
    height: Dp,
    memoryCacheKeyPrefix: String,
): ImageRequest? {
    val platformContext = LocalPlatformContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)

    return remember(platformContext, imageUrl, widthPx, heightPx, memoryCacheKeyPrefix) {
        imageUrl
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
