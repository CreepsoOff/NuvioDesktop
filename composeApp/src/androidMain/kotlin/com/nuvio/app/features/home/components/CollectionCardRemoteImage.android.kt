package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
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
    val context = LocalContext.current
    val effectiveUrl = if (animateIfPossible && !animatedImageUrl.isNullOrBlank()) {
        animatedImageUrl
    } else {
        imageUrl
    }
    val request: ImageRequest = remember(context, effectiveUrl) {
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
