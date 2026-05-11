package com.nuvio.app.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.desktop.DesktopPreferences
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val DefaultGifDelayCentiseconds = 10
private const val DecodeSizeBucketPx = 32
private const val FallbackDecodeDimensionPx = 360
private const val MaxDecodedGifEntries = 3
private const val MaxDecodedDimensionPx = 1920
private const val MaxGifSourceBytes = 16L * 1024 * 1024
private const val MaxDecodedGifBytes = 64L * 1024 * 1024
private const val MaxDecodedGifBytesTotal = 128L * 1024 * 1024
private const val MaxLogicalGifPixels = 4096L * 4096L
private const val MaxGifDecodeUpscale = 2.0

private data class DesktopGifCacheKey(
    val url: String,
    val widthPx: Int,
    val heightPx: Int,
)

private data class GifDecodeTarget(
    val widthPx: Int,
    val heightPx: Int,
)

private data class DecodedDesktopGif(
    val frames: List<ImageBitmap>,
    val delaysMs: IntArray,
    val approxBytes: Long,
)

private data class GifFrameMeta(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val delayCs: Int,
    val disposalMethod: String,
)

private object DesktopDecodedGifCache {
    private var totalBytes: Long = 0
    private val map = LinkedHashMap<DesktopGifCacheKey, DecodedDesktopGif>(16, 0.75f, true)

    @Synchronized
    fun get(key: DesktopGifCacheKey): DecodedDesktopGif? = map[key]

    @Synchronized
    fun put(key: DesktopGifCacheKey, gif: DecodedDesktopGif) {
        if (gif.approxBytes > MaxDecodedGifBytes) return
        map.remove(key)?.let { totalBytes -= it.approxBytes }
        map[key] = gif
        totalBytes += gif.approxBytes
        while ((map.size > MaxDecodedGifEntries || totalBytes > MaxDecodedGifBytesTotal) && map.isNotEmpty()) {
            val eldest = map.entries.first()
            map.remove(eldest.key)
            totalBytes -= eldest.value.approxBytes
        }
    }
}

private object DesktopGifInFlight {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = mutableMapOf<DesktopGifCacheKey, Deferred<DecodedDesktopGif?>>()

    suspend fun getOrDecode(
        key: DesktopGifCacheKey,
        decode: suspend () -> DecodedDesktopGif?,
    ): DecodedDesktopGif? {
        DesktopDecodedGifCache.get(key)?.let { return it }

        val request = synchronized(requests) {
            requests[key] ?: scope.async {
                DesktopDecodedGifCache.get(key) ?: decode()?.also { decoded ->
                    DesktopDecodedGifCache.put(key, decoded)
                }
            }.also { deferred ->
                requests[key] = deferred
                deferred.invokeOnCompletion {
                    synchronized(requests) {
                        if (requests[key] === deferred) {
                            requests.remove(key)
                        }
                    }
                }
            }
        }

        return request.await()
    }
}

private val desktopGifHttpClient by lazy { HttpClient(Java) }

private sealed interface DesktopGifState {
    data object Loading : DesktopGifState
    data class Ready(val gif: DecodedDesktopGif) : DesktopGifState
    data object UseStaticCoil : DesktopGifState
}

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
        val decodeTarget = remember(targetWidthPx, targetHeightPx) {
            GifDecodeTarget(
                widthPx = targetWidthPx.roundUpToDecodeBucket().coerceIn(1, MaxDecodedDimensionPx),
                heightPx = targetHeightPx.roundUpToDecodeBucket().coerceIn(1, MaxDecodedDimensionPx),
            )
        }
        val staticRequest = remember(platformContext, imageUrl, targetWidthPx, targetHeightPx) {
            ImageRequest.Builder(platformContext)
                .data(imageUrl)
                .size(Size(targetWidthPx.coerceAtLeast(1), targetHeightPx.coerceAtLeast(1)))
                .precision(Precision.EXACT)
                .memoryCacheKey("home-collection-static:$targetWidthPx:$targetHeightPx:${imageUrl.hashCode()}")
                .diskCacheKey(imageUrl)
                .build()
        }
        val cacheKey = remember(gifUrl, decodeTarget) {
            gifUrl?.let { url ->
                DesktopGifCacheKey(
                    url = url,
                    widthPx = decodeTarget.widthPx,
                    heightPx = decodeTarget.heightPx,
                )
            }
        }
        val cachedGif = remember(cacheKey) {
            cacheKey?.let(DesktopDecodedGifCache::get)
        }
        var state by remember(cacheKey) {
            mutableStateOf<DesktopGifState>(
                cachedGif?.let(DesktopGifState::Ready) ?: DesktopGifState.Loading,
            )
        }

        LaunchedEffect(cacheKey, gifUrl, shouldAnimate) {
            if (cacheKey == null || gifUrl == null) {
                state = DesktopGifState.UseStaticCoil
                return@LaunchedEffect
            }
            if (!shouldAnimate) {
                state = DesktopDecodedGifCache.get(cacheKey)?.let(DesktopGifState::Ready) ?: DesktopGifState.Loading
                return@LaunchedEffect
            }
            cachedGif?.let {
                state = DesktopGifState.Ready(it)
                return@LaunchedEffect
            }

            state = DesktopGifState.Loading
            val decoded = DesktopGifInFlight.getOrDecode(cacheKey) {
                downloadAndDecodeGif(gifUrl, decodeTarget)
            }

            state = if (decoded != null) {
                DesktopGifState.Ready(decoded)
            } else {
                DesktopGifState.UseStaticCoil
            }
        }

                // Always show static poster as base layer
        AsyncImage(
            model = staticRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
        // Overlay GIF on top with fade-in when hovered or always-animate enabled
        if (shouldAnimate && state is DesktopGifState.Ready) {
            val readyState = state as DesktopGifState.Ready
            var gifLoaded by remember { mutableStateOf(false) }
            val gifAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (gifLoaded) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(200),
                label = "gifFadeIn",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = gifAlpha }
            ) {
                AnimatedComposeGif(
                    gif = readyState.gif,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    isAnimating = true,
                )
            }
            LaunchedEffect(readyState) {
                gifLoaded = true
            }
        }
    }
}

@Composable
private fun AnimatedComposeGif(
    gif: DecodedDesktopGif,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    isAnimating: Boolean = true,
) {
    if (gif.frames.isEmpty()) return
    var frameIndex by remember(gif) { mutableIntStateOf(0) }

    if (isAnimating && gif.frames.size > 1) {
        LaunchedEffect(gif) {
            var nextFrameAtNanos = System.nanoTime()
            while (isActive) {
                val delayMs = gif.delaysMs.getOrElse(frameIndex) { DefaultGifDelayCentiseconds * 10 }.coerceAtLeast(10)
                nextFrameAtNanos += delayMs * 1_000_000L
                val waitNanos = nextFrameAtNanos - System.nanoTime()
                if (waitNanos > 0L) {
                    delay(max(1L, waitNanos / 1_000_000L))
                }
                frameIndex = (frameIndex + 1) % gif.frames.size
                if (System.nanoTime() - nextFrameAtNanos > 250_000_000L) {
                    nextFrameAtNanos = System.nanoTime()
                }
            }
        }
    }

    Image(
        bitmap = gif.frames[frameIndex],
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

private suspend fun downloadAndDecodeGif(
    imageUrl: String,
    target: GifDecodeTarget,
): DecodedDesktopGif? = withContext(Dispatchers.IO) {
    runCatching {
        val response = desktopGifHttpClient.get(imageUrl)
        if (!response.status.isSuccess()) return@runCatching null
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MaxGifSourceBytes) return@runCatching null
        val bytes = response.body<ByteArray>()
        if (bytes.size.toLong() > MaxGifSourceBytes) return@runCatching null
        decodeGifForCompose(bytes, target)
    }.getOrNull()
}

private fun decodeGifForCompose(
    bytes: ByteArray,
    target: GifDecodeTarget,
): DecodedDesktopGif? {
    if (!bytes.isGifHeader()) return null
    val imageInputStream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
    imageInputStream.use { input ->
        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input

            val frameCount = reader.getNumImages(true)
            if (frameCount <= 0) return null

            val streamRoot = (reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0") as? IIOMetadataNode)
            val logicalDescriptor = streamRoot?.getElementsByTagName("LogicalScreenDescriptor")?.item(0) as? IIOMetadataNode
            val logicalWidth = logicalDescriptor?.getAttribute("logicalScreenWidth")?.toIntOrNull()?.coerceAtLeast(1)
            val logicalHeight = logicalDescriptor?.getAttribute("logicalScreenHeight")?.toIntOrNull()?.coerceAtLeast(1)

            val firstImage = reader.read(0) ?: return null
            val baseW = logicalWidth ?: firstImage.width
            val baseH = logicalHeight ?: firstImage.height
            if (baseW <= 0 || baseH <= 0) return null
            if (baseW.toLong() * baseH.toLong() > MaxLogicalGifPixels) return null

            val coverScale = max(
                target.widthPx.toDouble() / baseW.toDouble(),
                target.heightPx.toDouble() / baseH.toDouble(),
            )
            val scale = coverScale.coerceAtMost(MaxGifDecodeUpscale)
            val canvasW = max(1, (baseW * scale).toInt())
            val canvasH = max(1, (baseH * scale).toInt())
            val approxBytes = frameCount.toLong() * canvasW.toLong() * canvasH.toLong() * 4L
            if (approxBytes > MaxDecodedGifBytes) return null

            val logicalCanvas = BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_ARGB)
            val previousLogicalCanvas = BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_ARGB)
            val outputCanvas = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)

            val outFrames = ArrayList<ImageBitmap>(frameCount)
            val outDelays = IntArray(frameCount)

            val gLogicalCanvas = logicalCanvas.createGraphics().apply {
                composite = AlphaComposite.SrcOver
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            }
            val gPreviousLogicalCanvas = previousLogicalCanvas.createGraphics().apply {
                composite = AlphaComposite.Src
            }
            val gOutputCanvas = outputCanvas.createGraphics().apply {
                composite = AlphaComposite.SrcOver
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            }

            try {
                for (i in 0 until frameCount) {
                    val frame = if (i == 0) firstImage else reader.read(i) ?: return null
                    val metadataRoot = reader.getImageMetadata(i)
                        .getAsTree("javax_imageio_gif_image_1.0") as? IIOMetadataNode
                        ?: return null
                    val meta = parseFrameMetadata(metadataRoot)

                    val disposal = meta.disposalMethod
                    val needsRestorePrevious = disposal.equals("restoretoprevious", ignoreCase = true)
                    if (needsRestorePrevious) {
                        gPreviousLogicalCanvas.drawImage(logicalCanvas, 0, 0, null)
                    }

                    val frameLeft = meta.left.coerceIn(0, baseW)
                    val frameTop = meta.top.coerceIn(0, baseH)
                    val frameRight = (meta.left + meta.width).coerceIn(0, baseW)
                    val frameBottom = (meta.top + meta.height).coerceIn(0, baseH)
                    val frameWidth = frameRight - frameLeft
                    val frameHeight = frameBottom - frameTop
                    if (frameWidth > 0 && frameHeight > 0) {
                        gLogicalCanvas.drawImage(
                            frame,
                            frameLeft,
                            frameTop,
                            frameRight,
                            frameBottom,
                            0,
                            0,
                            frame.width,
                            frame.height,
                            null,
                        )
                    }

                    val previousOutputComposite = gOutputCanvas.composite
                    gOutputCanvas.composite = AlphaComposite.Clear
                    gOutputCanvas.fillRect(0, 0, canvasW, canvasH)
                    gOutputCanvas.composite = previousOutputComposite
                    gOutputCanvas.drawImage(logicalCanvas, 0, 0, canvasW, canvasH, null)

                    outFrames.add(deepCopy(outputCanvas).toComposeImageBitmap())
                    outDelays[i] = max(1, meta.delayCs) * 10

                    when {
                        disposal.equals("restoretobackgroundcolor", ignoreCase = true) -> {
                            val clearX = frameLeft.coerceIn(0, baseW)
                            val clearY = frameTop.coerceIn(0, baseH)
                            val clearW = frameRight.coerceAtMost(baseW) - clearX
                            val clearH = frameBottom.coerceAtMost(baseH) - clearY
                            if (clearW > 0 && clearH > 0) {
                                val oldComposite = gLogicalCanvas.composite
                                gLogicalCanvas.composite = AlphaComposite.Clear
                                gLogicalCanvas.fillRect(clearX, clearY, clearW, clearH)
                                gLogicalCanvas.composite = oldComposite
                            }
                        }
                        disposal.equals("restoretoprevious", ignoreCase = true) -> {
                            val oldComposite = gLogicalCanvas.composite
                            gLogicalCanvas.composite = AlphaComposite.Src
                            gLogicalCanvas.drawImage(previousLogicalCanvas, 0, 0, null)
                            gLogicalCanvas.composite = oldComposite
                        }
                    }
                }
            } finally {
                gLogicalCanvas.dispose()
                gPreviousLogicalCanvas.dispose()
                gOutputCanvas.dispose()
            }

            if (outFrames.isEmpty()) return null
            return DecodedDesktopGif(
                frames = outFrames,
                delaysMs = outDelays,
                approxBytes = approxBytes,
            )
        } finally {
            reader.dispose()
        }
    }
}

private fun Int.roundUpToDecodeBucket(): Int {
    if (this <= 0) return FallbackDecodeDimensionPx
    return (((this + DecodeSizeBucketPx - 1) / DecodeSizeBucketPx) * DecodeSizeBucketPx)
        .coerceAtLeast(DecodeSizeBucketPx)
}

private fun parseFrameMetadata(root: IIOMetadataNode): GifFrameMeta {
    val imageDescriptor = root.getElementsByTagName("ImageDescriptor").item(0) as? IIOMetadataNode
    val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as? IIOMetadataNode

    return GifFrameMeta(
        left = imageDescriptor?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0,
        top = imageDescriptor?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0,
        width = imageDescriptor?.getAttribute("imageWidth")?.toIntOrNull() ?: 1,
        height = imageDescriptor?.getAttribute("imageHeight")?.toIntOrNull() ?: 1,
        delayCs = gce?.getAttribute("delayTime")?.toIntOrNull()?.coerceAtLeast(1) ?: DefaultGifDelayCentiseconds,
        disposalMethod = gce?.getAttribute("disposalMethod") ?: "none",
    )
}

private fun deepCopy(source: BufferedImage): BufferedImage {
    val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val g = copy.createGraphics()
    try {
        g.composite = AlphaComposite.Src
        g.drawImage(source, 0, 0, null)
    } finally {
        g.dispose()
    }
    return copy
}

private fun ByteArray.isGifHeader(): Boolean =
    size >= 6 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() &&
        (this[4] == '7'.code.toByte() || this[4] == '9'.code.toByte()) &&
        this[5] == 'a'.code.toByte()
