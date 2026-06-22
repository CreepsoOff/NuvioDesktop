package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    animatedImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val context = LocalPlatformContext.current
    val effectiveUrl = if (animateIfPossible && !animatedImageUrl.isNullOrBlank()) {
        animatedImageUrl
    } else {
        imageUrl
    }
    val request = remember(context, effectiveUrl) {
        ImageRequest.Builder(context)
            .data(effectiveUrl)
            .memoryCacheKey("home-collection:$effectiveUrl")
            .diskCacheKey(effectiveUrl)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
