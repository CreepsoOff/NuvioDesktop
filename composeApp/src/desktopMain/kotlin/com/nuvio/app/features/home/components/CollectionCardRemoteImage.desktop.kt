package com.nuvio.app.features.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.nuvio.app.core.ui.NuvioImageFilterQuality
import com.nuvio.app.core.ui.nuvioQualityDecodeDimensionPx
import com.nuvio.app.core.ui.upgradeTmdbImageQuality
import com.nuvio.app.desktop.DesktopPreferences

private const val FallbackDecodeDimensionPx = 360

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    animatedImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    animateNow: Boolean,
) {
    val alwaysAnimateGif = remember {
        DesktopPreferences.getBoolean("nuvio_home_settings", "always_animate_gif") ?: false
    }
    val staticImageUrl = remember(imageUrl) { imageUrl.upgradeTmdbImageQuality() }
    val gifUrl = animatedImageUrl?.takeIf { animateIfPossible && it.isNotBlank() }
    val shouldAnimate = gifUrl != null && (alwaysAnimateGif || animateNow)

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val platformContext = LocalPlatformContext.current
        val targetWidthPx = maxWidth.value
            .takeIf { it.isFinite() && it > 0f }
            ?.let { with(density) { maxWidth.roundToPx() } }
            ?: FallbackDecodeDimensionPx
        val targetHeightPx = maxHeight.value
            .takeIf { it.isFinite() && it > 0f }
            ?.let { with(density) { maxHeight.roundToPx() } }
            ?: FallbackDecodeDimensionPx
        val decodeWidthPx = nuvioQualityDecodeDimensionPx(targetWidthPx.coerceAtLeast(1))
        val decodeHeightPx = nuvioQualityDecodeDimensionPx(targetHeightPx.coerceAtLeast(1))
        val staticRequest = remember(platformContext, staticImageUrl, decodeWidthPx, decodeHeightPx) {
            buildDesktopCollectionImageRequest(
                platformContext = platformContext,
                imageUrl = staticImageUrl,
                widthPx = decodeWidthPx,
                heightPx = decodeHeightPx,
                cachePrefix = "home-collection-static",
            )
        }
        val gifRequest = remember(platformContext, gifUrl, decodeWidthPx, decodeHeightPx) {
            gifUrl?.let { url ->
                buildDesktopCollectionImageRequest(
                    platformContext = platformContext,
                    imageUrl = url,
                    widthPx = decodeWidthPx,
                    heightPx = decodeHeightPx,
                    cachePrefix = "home-collection-gif",
                )
            }
        }
        val gifAlpha by animateFloatAsState(
            targetValue = if (shouldAnimate && gifRequest != null) 1f else 0f,
            animationSpec = tween(180),
            label = "desktopCollectionGifFade",
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = staticRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                filterQuality = NuvioImageFilterQuality,
            )

            gifRequest?.let { request ->
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = gifAlpha },
                    contentScale = contentScale,
                    filterQuality = NuvioImageFilterQuality,
                )
            }
        }
    }
}

private fun buildDesktopCollectionImageRequest(
    platformContext: PlatformContext,
    imageUrl: String,
    widthPx: Int,
    heightPx: Int,
    cachePrefix: String,
): ImageRequest =
    ImageRequest.Builder(platformContext)
        .data(imageUrl)
        .size(Size(widthPx, heightPx))
        .precision(Precision.EXACT)
        .memoryCacheKey("$cachePrefix:$widthPx:$heightPx:${imageUrl.hashCode()}")
        .diskCacheKey(imageUrl)
        .build()
