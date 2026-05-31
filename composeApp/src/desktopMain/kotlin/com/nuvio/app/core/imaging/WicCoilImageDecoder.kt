package com.nuvio.app.core.imaging

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.decode.SkiaImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.pxOrElse
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Memory
import okio.Buffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/**
 * Coil 3 [Decoder] that routes still images through the native Windows Imaging Component
 * (WIC) bridge ([NuvioImageBridge]) so the decoded bitmap is already at the measured pixel
 * draw size. Skia then blits ~1:1 on the OpenGL backend instead of resampling at paint time
 * (the "crispy"/ringy artifact on Windows Desktop — see design Property 1).
 *
 * The output buffer the bridge fills is PBGRA (premultiplied B,G,R,A), 32bpp, top-down,
 * tightly packed (`row_stride == width * 4`), which maps directly to a Skia [Bitmap] with
 * [ColorType.BGRA_8888] / [ColorAlphaType.PREMUL] / sRGB.
 *
 * Windows-only and opt-in: the [Factory] only returns this decoder when
 * [NuvioImageBridge.isAvailable] is true (which is itself false off Windows or when the DLL
 * is missing) AND the encoded bytes sniff as a WIC-handled still format. GIF / WebP / SVG —
 * and anything unrecognized — fall through to Coil's default [SkiaImageDecoder] path so
 * animation and vector formats are preserved.
 */
internal class WicCoilImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        // The bridge needs the full encoded buffer for both probe and decode.
        val bytes = source.use { it.source().readByteArray() }

        val probe = NuvioImageBridge.probeSize(bytes)
            ?: return decodeWithSkiaFallback(bytes, reason = "probeSize failed")
        if (probe.width <= 0 || probe.height <= 0) {
            return decodeWithSkiaFallback(bytes, reason = "probeSize returned non-positive dimensions")
        }

        val target = WicResolveTargetSize(
            sourceWidth = probe.width,
            sourceHeight = probe.height,
            targetWidth = options.size.width.pxOrElse { 0 },
            targetHeight = options.size.height.pxOrElse { 0 },
            scale = options.scale,
        )
        if (target.width <= 0 || target.height <= 0) {
            return decodeWithSkiaFallback(bytes, reason = "resolved target size is empty")
        }

        val rowStrideBytes = target.width * BytesPerPixel
        val totalBytes = rowStrideBytes.toLong() * target.height.toLong()
        if (totalBytes <= 0L || totalBytes > MaxDecodeBytes) {
            return decodeWithSkiaFallback(bytes, reason = "decode buffer too large ($totalBytes bytes)")
        }

        val buffer = Memory(totalBytes)
        try {
            val result = NuvioImageBridge.decodeScaled(
                input = bytes,
                targetWidth = target.width,
                targetHeight = target.height,
                outBuffer = buffer,
                outBufferSize = totalBytes.toInt(),
            )
            if (!result.isOk) {
                return decodeWithSkiaFallback(bytes, reason = "decodeScaled returned ${result.resultCode}")
            }

            // Contract: PBGRA, top-down, tightly packed (row_stride == width * 4). WIC reports
            // the stride it wrote; anything other than the tightly-packed stride would not fit
            // the buffer we allocated, so treat that as a fallback rather than risk an overread.
            val stride = result.rowStrideBytes.takeIf { it > 0 } ?: rowStrideBytes
            if (stride != rowStrideBytes) {
                return decodeWithSkiaFallback(bytes, reason = "unexpected row stride $stride (expected $rowStrideBytes)")
            }
            val pixels = buffer.getByteArray(0L, totalBytes.toInt())

            val imageInfo = ImageInfo(
                width = target.width,
                height = target.height,
                colorType = ColorType.BGRA_8888,
                alphaType = ColorAlphaType.PREMUL,
                colorSpace = ColorSpace.sRGB,
            )
            val bitmap = Bitmap()
            if (!bitmap.installPixels(imageInfo, pixels, stride)) {
                bitmap.close()
                return decodeWithSkiaFallback(bytes, reason = "Bitmap.installPixels failed")
            }
            bitmap.setImmutable()

            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = target.width < probe.width || target.height < probe.height,
            )
        } finally {
            buffer.close()
        }
    }

    /**
     * Decode the same in-memory bytes through Coil's default Skia decoder. Used whenever the
     * native bridge cannot produce a bitmap (probe/decode error, oversized buffer, etc.) so a
     * WIC hiccup degrades to the normal decode path instead of a broken image.
     */
    private suspend fun decodeWithSkiaFallback(bytes: ByteArray, reason: String): DecodeResult {
        DesktopRuntimeLog.warn("WIC decoder falling back to Skia decode ($reason)")
        val fallbackSource = ImageSource(
            source = Buffer().write(bytes),
            fileSystem = options.fileSystem,
        )
        return SkiaImageDecoder(fallbackSource, options).decode()
    }

    /**
     * Sniffs the encoded magic header and routes WIC-handled still formats through
     * [WicCoilImageDecoder]; returns `null` (default decoder) for animated / vector formats,
     * when the native bridge is unavailable, or when the format is not recognized.
     */
    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!NuvioImageBridge.isAvailable) return null
            val header = result.source.peekHeader()
            if (!sniffImageFormat(header).isWicHandled) return null
            return WicCoilImageDecoder(result.source, options)
        }
    }

    private companion object {
        const val BytesPerPixel = 4

        /** Guard against pathological allocations (e.g. an undefined-size decode of a huge source). */
        const val MaxDecodeBytes = 256L * 1024 * 1024
    }
}

/** Number of header bytes peeked for format sniffing (enough for HEIF `ftyp` brand + SVG whitespace). */
private const val HeaderProbeBytes = 64

/**
 * Aspect-ratio-preserving target size for the WIC decode, derived from the source resolution
 * and the Coil request size. [Scale.FILL] (= `ContentScale.Crop`) covers the request box;
 * [Scale.FIT] fits inside it. The result is always clamped to the source resolution so the
 * decode never upscales beyond the source (design Property 5 — No Upscaling Beyond Source).
 *
 * Pure and Coil-context-free apart from the [Scale] enum: pass `<= 0` for either target axis
 * to leave it unconstrained (Coil's `Dimension.Undefined`). When neither axis is constrained
 * the target is the source size.
 */
internal fun WicResolveTargetSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    scale: Scale,
): WicTargetSize {
    if (sourceWidth <= 0 || sourceHeight <= 0) return WicTargetSize(0, 0)

    val scaleW = if (targetWidth > 0) targetWidth.toDouble() / sourceWidth else null
    val scaleH = if (targetHeight > 0) targetHeight.toDouble() / sourceHeight else null

    val factor = when {
        scaleW != null && scaleH != null ->
            if (scale == Scale.FILL) maxOf(scaleW, scaleH) else minOf(scaleW, scaleH)
        scaleW != null -> scaleW
        scaleH != null -> scaleH
        else -> 1.0
    }.coerceAtMost(1.0) // never upscale beyond the source

    val width = (sourceWidth * factor).roundToInt().coerceIn(1, sourceWidth)
    val height = (sourceHeight * factor).roundToInt().coerceIn(1, sourceHeight)
    return WicTargetSize(width, height)
}

/** Resolved decode target in pixels, clamped to the source resolution. */
internal data class WicTargetSize(val width: Int, val height: Int)

/** Image formats distinguished by [sniffImageFormat]. Only [isWicHandled] formats go through WIC. */
internal enum class SniffedImageFormat {
    JPEG, PNG, BMP, TIFF, ICO, HEIF, // -> WIC
    GIF, WEBP, SVG, // -> fall through (animation / vector preserved)
    UNKNOWN, // -> fall through (default decoder)
    ;

    /** WIC owns the downscale for still raster formats; everything else stays on the Skia path. */
    val isWicHandled: Boolean
        get() = when (this) {
            JPEG, PNG, BMP, TIFF, ICO, HEIF -> true
            GIF, WEBP, SVG, UNKNOWN -> false
        }
}

/**
 * Classifies encoded image bytes by their magic header. Recognizes the WIC-handled still
 * formats (JPEG/PNG/BMP/TIFF/ICO/HEIF) and the fall-through formats (GIF/WebP/SVG); anything
 * else is [SniffedImageFormat.UNKNOWN].
 */
internal fun sniffImageFormat(header: ByteArray): SniffedImageFormat = when {
    header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> SniffedImageFormat.PNG
    header.startsWith(0xFF, 0xD8, 0xFF) -> SniffedImageFormat.JPEG
    header.startsWith(0x47, 0x49, 0x46, 0x38) -> SniffedImageFormat.GIF // "GIF8"
    header.matchesAscii(0, "RIFF") && header.matchesAscii(8, "WEBP") -> SniffedImageFormat.WEBP
    header.startsWith(0x42, 0x4D) -> SniffedImageFormat.BMP // "BM"
    header.startsWith(0x49, 0x49, 0x2A, 0x00) ||
        header.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> SniffedImageFormat.TIFF
    header.startsWith(0x00, 0x00, 0x01, 0x00) -> SniffedImageFormat.ICO
    header.isHeif() -> SniffedImageFormat.HEIF
    header.isSvg() -> SniffedImageFormat.SVG
    else -> SniffedImageFormat.UNKNOWN
}

/** HEIF/HEIC brands (offset 8, after the `ftyp` box type at offset 4) routed through WIC. */
private val HeifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "heif", "mif1", "msf1")

private fun ImageSource.peekHeader(): ByteArray {
    val peek = source().peek()
    val buffer = ByteArray(HeaderProbeBytes)
    val read = peek.read(buffer)
    return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) {
        if ((this[i].toInt() and 0xFF) != prefix[i]) return false
    }
    return true
}

private fun ByteArray.matchesAscii(offset: Int, ascii: String): Boolean {
    if (size < offset + ascii.length) return false
    for (i in ascii.indices) {
        if ((this[offset + i].toInt() and 0xFF) != ascii[i].code) return false
    }
    return true
}

private fun ByteArray.isHeif(): Boolean {
    if (size < 12) return false
    if (!matchesAscii(4, "ftyp")) return false
    val brand = buildString {
        for (i in 8 until 12) append((this@isHeif[i].toInt() and 0xFF).toChar())
    }
    return brand in HeifBrands
}

private fun ByteArray.isSvg(): Boolean {
    var i = 0
    // Skip a UTF-8 BOM if present.
    if (size >= 3 &&
        (this[0].toInt() and 0xFF) == 0xEF &&
        (this[1].toInt() and 0xFF) == 0xBB &&
        (this[2].toInt() and 0xFF) == 0xBF
    ) {
        i = 3
    }
    // Skip leading whitespace.
    while (i < size) {
        when (this[i].toInt() and 0xFF) {
            ' '.code, '\n'.code, '\r'.code, '\t'.code -> i++
            else -> return (this[i].toInt() and 0xFF) == '<'.code
        }
    }
    return false
}
