package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import java.net.URI
import kotlin.math.max

private const val MaxCachedAnimatedGifs = 12
private const val DefaultGifFrameDelayMs = 100
private val gifDecodeScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val animatedGifCache = mutableMapOf<String, DesktopAnimatedGif>()
private val animatedGifCacheOrder = mutableListOf<String>()
private val animatedGifInFlight = mutableMapOf<String, Deferred<DesktopAnimatedGif?>>()

private data class DesktopGifFrame(
    val image: ImageBitmap,
    val delayMs: Int,
)

private data class DesktopAnimatedGif(
    val frames: List<DesktopGifFrame>,
)

@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    if (animateIfPossible) {
        AnimatedCollectionGif(
            imageUrl = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    StaticCollectionImage(
        imageUrl = imageUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
private fun StaticCollectionImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val context = LocalPlatformContext.current
    val request = remember(context, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("home-collection:$imageUrl")
            .diskCacheKey(imageUrl)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
private fun AnimatedCollectionGif(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    var animatedGif by remember(imageUrl) { mutableStateOf(cachedAnimatedGif(imageUrl)) }
    var frameIndex by remember(imageUrl) { mutableIntStateOf(0) }

    LaunchedEffect(imageUrl) {
        animatedGif = loadAnimatedGif(imageUrl)
        frameIndex = 0
    }

    val gif = animatedGif
    if (gif == null || gif.frames.isEmpty()) {
        StaticCollectionImage(
            imageUrl = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    LaunchedEffect(gif) {
        while (gif.frames.size > 1) {
            val frame = gif.frames[frameIndex.coerceIn(0, gif.frames.lastIndex)]
            delay(frame.delayMs.toLong())
            frameIndex = (frameIndex + 1) % gif.frames.size
        }
    }

    Image(
        bitmap = gif.frames[frameIndex.coerceIn(0, gif.frames.lastIndex)].image,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

private fun cachedAnimatedGif(imageUrl: String): DesktopAnimatedGif? {
    val image = synchronized(animatedGifCache) {
        animatedGifCache[imageUrl]
    } ?: return null
    synchronized(animatedGifCache) {
        animatedGifCacheOrder.remove(imageUrl)
        animatedGifCacheOrder.add(imageUrl)
    }
    return image
}

private fun storeAnimatedGif(imageUrl: String, image: DesktopAnimatedGif) {
    synchronized(animatedGifCache) {
        animatedGifCache[imageUrl] = image
        animatedGifCacheOrder.remove(imageUrl)
        animatedGifCacheOrder.add(imageUrl)

        while (animatedGifCacheOrder.size > MaxCachedAnimatedGifs) {
            val eldestKey = animatedGifCacheOrder.removeFirstOrNull() ?: break
            animatedGifCache.remove(eldestKey)
        }
    }
}

private suspend fun loadAnimatedGif(imageUrl: String): DesktopAnimatedGif? {
    cachedAnimatedGif(imageUrl)?.let { return it }

    val request = synchronized(animatedGifInFlight) {
        animatedGifInFlight[imageUrl] ?: gifDecodeScope.async {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    URI(imageUrl).toURL().openStream().use { input -> input.readBytes() }
                }
                decodeAnimatedGif(bytes)
            }.getOrNull()
        }.also { animatedGifInFlight[imageUrl] = it }
    }

    val image = try {
        request.await()
    } finally {
        synchronized(animatedGifInFlight) {
            if (animatedGifInFlight[imageUrl] === request) {
                animatedGifInFlight.remove(imageUrl)
            }
        }
    }

    if (image != null) {
        storeAnimatedGif(imageUrl, image)
    }

    return image
}

private fun decodeAnimatedGif(bytes: ByteArray): DesktopAnimatedGif? {
    if (bytes.isEmpty()) return null
    val data = Data.makeFromBytes(bytes)
    val codec = Codec.makeFromData(data)
    if (codec.encodedImageFormat != EncodedImageFormat.GIF || codec.frameCount <= 1) return null

    val frames = buildList {
        repeat(codec.frameCount) { index ->
            val bitmap = Bitmap()
            if (!bitmap.allocPixels(codec.imageInfo)) return@repeat
            codec.readPixels(bitmap, index)
            add(
                DesktopGifFrame(
                    image = bitmap.asComposeImageBitmap(),
                    delayMs = max(codec.getFrameInfo(index).duration, DefaultGifFrameDelayMs),
                ),
            )
        }
    }

    return frames.takeIf { it.isNotEmpty() }?.let(::DesktopAnimatedGif)
}
