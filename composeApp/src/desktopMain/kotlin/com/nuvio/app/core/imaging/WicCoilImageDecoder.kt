package com.nuvio.app.core.imaging

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import coil3.size.pxOrElse
import com.nuvio.app.desktop.DesktopRuntimeLog
import okio.BufferedSource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coil 3 [Decoder] that bypasses Skia for the decode + downscale step
 * and routes through Windows Imaging Component (WIC) instead.
 *
 * Why: on Windows the Skiko backend is forced onto OpenGL (libmpv shares
 * its GL context with Skia, see `composeApp/build.gradle.kts`
 * `-Dskiko.renderApi=OPENGL`). Skia's bicubic Mitchell sampler on the GL
 * backend visibly aliases on the >2x downscales we routinely do for
 * shelf posters, collection covers and circular avatars (TMDB sources
 * are 780–3840 px; targets are 48–260 px). WIC's
 * `WICBitmapInterpolationModeHighQualityCubic` adapts its kernel size
 * to the scale factor and is what Microsoft Edge, the Photos app, and
 * Explorer use to render images on Windows — visually that's exactly
 * the iOS / macOS reference users compare against.
 *
 * The decoder hands Skia a pre-sampled, pre-multiplied BGRA bitmap at
 * the exact target draw size, so Compose's draw-time sampler becomes a
 * no-op and the FilterQuality knob no longer matters.
 *
 * Animated formats (GIF, animated WebP, animated PNG) deliberately fall
 * through to the next decoder in the chain — those are still handled by
 * Coil's GIF decoder / our Skia GIF helper. WIC supports animated GIF
 * but the Compose host already has a dedicated path for them with
 * proper memory bounds.
 */
internal class WicCoilImageDecoder(
    private val source: BufferedSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        // Read all encoded bytes once; WIC needs random access.
        val encoded = source.readByteArray()
        if (encoded.isEmpty()) return null

        // Allocate a direct buffer JNA can pass to native without an extra
        // copy. Coil's Source can be backed by a file, gunzip, etc; once
        // we have raw bytes the rest is in user space.
        val inputBuffer = ByteBuffer.allocateDirect(encoded.size)
        inputBuffer.put(encoded)
        inputBuffer.position(0)

        // Probe the source dimensions so the resampling target preserves
        // the source aspect ratio. Without this, callers that pass a
        // box-shaped target (e.g. a hero `Size(1024, 256)` for a 16:9
        // backdrop) would have their image squashed because WIC scales
        // to the exact requested dimensions.
        val (sourceW, sourceH) = NuvioImageBridge.probeSize(inputBuffer, encoded.size)
            ?: return null
        val (targetW, targetH) = WicResolveTargetSize.resolve(
            requested = options.size,
            scale = options.scale,
            sourceW = sourceW,
            sourceH = sourceH,
        )

        // Don't burn cycles upscaling on the CPU; only pay for downscale.
        // Compose will up-sample at draw time if the layout is bigger.
        val finalW = min(targetW, sourceW).coerceAtLeast(1)
        val finalH = min(targetH, sourceH).coerceAtLeast(1)

        val decoded = NuvioImageBridge.decodeScaled(
            encoded = inputBuffer,
            encodedLen = encoded.size,
            targetWidthPx = finalW,
            targetHeightPx = finalH,
        ) ?: return null

        val bitmap = decoded.toSkiaBitmap() ?: return null
        val isSampled = decoded.widthPx < sourceW || decoded.heightPx < sourceH
        return DecodeResult(image = bitmap.asImage(), isSampled = isSampled)
    }

    private fun NuvioImageBridge.DecodedBitmap.toSkiaBitmap(): Bitmap? {
        // The bytes from the bridge are pre-multiplied BGRA, top-down,
        // tightly packed. That's exactly the layout the SRGB BGRA_8888
        // / PREMUL bitmap config expects.
        val info = ImageInfo(
            ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            widthPx,
            heightPx,
        )
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(info)) {
            DesktopRuntimeLog.warn("WIC decoder: allocPixels failed for ${widthPx}x$heightPx")
            return null
        }

        // Skia wants a contiguous byte array; copy the direct buffer once.
        // Could be elided with installPixels(addr) but JNA-allocated
        // direct buffer addresses are not stable across GC, so a copy is
        // the predictable choice.
        val byteCount = rowStrideBytes.toLong() * heightPx.toLong()
        if (byteCount <= 0L || byteCount > Int.MAX_VALUE.toLong()) return null
        val pixelBytes = ByteArray(byteCount.toInt())
        pixels.position(0)
        pixels.get(pixelBytes, 0, pixelBytes.size)
        bitmap.installPixels(pixelBytes)
        bitmap.setImmutable()
        return bitmap
    }

    /**
     * Coil 3 [Decoder.Factory] that produces [WicCoilImageDecoder] for
     * still images. Animated streams (GIF, WebP-animated, APNG) and
     * formats WIC cannot handle (SVG) fall through to the next factory.
     */
    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!NuvioImageBridge.isAvailable) return null
            val source = result.source.source()

            // Sniff the magic header so animated formats stay on the GIF
            // / animated decoder path. WIC can decode GIF but only the
            // first frame, which would silently drop the animation.
            if (!isStaticImageHeader(source)) return null

            return WicCoilImageDecoder(source, options)
        }

        /**
         * Returns true if the source's magic header identifies a format
         * we want to handle: still JPEG, PNG, BMP, TIFF, ICO, HEIF.
         * Returns false for GIF (animated) and WebP (which can be
         * animated; we leave the decision to the dedicated decoder so
         * animated WebP keeps animating).
         */
        private fun isStaticImageHeader(source: BufferedSource): Boolean {
            // BufferedSource.peek() returns a snapshot view; consuming
            // the peek does not advance the underlying source.
            val peek = source.peek()
            if (!peek.request(12L)) return false

            if (peek.rangeEquals(0, JPEG_MAGIC)) return true
            if (peek.rangeEquals(0, PNG_MAGIC)) return true
            if (peek.rangeEquals(0, BMP_MAGIC)) return true
            if (peek.rangeEquals(0, TIFF_LE_MAGIC) || peek.rangeEquals(0, TIFF_BE_MAGIC)) return true
            if (peek.rangeEquals(0, ICO_MAGIC)) return true
            if (peek.rangeEquals(4, HEIF_FTYP_MAGIC)) return true

            return false
        }

        private companion object {
            val JPEG_MAGIC = okio.ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val PNG_MAGIC = okio.ByteString.of(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            )
            val BMP_MAGIC = okio.ByteString.of(0x42, 0x4D)
            val TIFF_LE_MAGIC = okio.ByteString.of(0x49, 0x49, 0x2A, 0x00)
            val TIFF_BE_MAGIC = okio.ByteString.of(0x4D, 0x4D, 0x00, 0x2A)
            val ICO_MAGIC = okio.ByteString.of(0x00, 0x00, 0x01, 0x00)
            // 'ftyp' at offset 4-7
            val HEIF_FTYP_MAGIC = okio.ByteString.of(0x66, 0x74, 0x79, 0x70)
        }
    }
}

/**
 * Aspect-ratio-preserving target sizing used by [WicCoilImageDecoder].
 * Extracted into a top-level object so unit tests can exercise it
 * without instantiating the full Decoder.
 *
 * - [Scale.FILL] (default for `ContentScale.Crop`): pick the smallest
 *   output that fully covers the requested box, so the cropper has
 *   pixels to crop from at draw time. Output is >= the requested box on
 *   at least one axis. This matches the behavior of Coil's
 *   `BitmapFactoryDecoder` on Android.
 * - [Scale.FIT] (default for `ContentScale.Fit`): pick the largest
 *   output that fits entirely inside the requested box. Output is <=
 *   the requested box on both axes.
 *
 * If only one dimension is specified, scale the other to keep the
 * source aspect ratio. If none is specified, return the source size
 * untouched so the caller's `ContentScale` decides at draw time.
 */
internal object WicResolveTargetSize {
    fun resolve(
        requested: Size,
        scale: Scale,
        sourceW: Int,
        sourceH: Int,
    ): Pair<Int, Int> {
        val rw = requested.width.pxOrElse { 0 }
        val rh = requested.height.pxOrElse { 0 }

        if (rw <= 0 && rh <= 0) return sourceW to sourceH

        if (rw > 0 && rh <= 0) {
            val ratio = rw.toDouble() / sourceW.toDouble()
            return rw to max(1, (sourceH * ratio).roundToInt())
        }
        if (rh > 0 && rw <= 0) {
            val ratio = rh.toDouble() / sourceH.toDouble()
            return max(1, (sourceW * ratio).roundToInt()) to rh
        }

        val widthRatio = rw.toDouble() / sourceW.toDouble()
        val heightRatio = rh.toDouble() / sourceH.toDouble()
        val multiplier = when (scale) {
            Scale.FILL -> max(widthRatio, heightRatio)
            Scale.FIT -> min(widthRatio, heightRatio)
        }
        val outW = (sourceW * multiplier).roundToInt().coerceAtLeast(1)
        val outH = (sourceH * multiplier).roundToInt().coerceAtLeast(1)
        return outW to outH
    }
}
