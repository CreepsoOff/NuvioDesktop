package com.nuvio.app.core.imaging

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.nio.ByteBuffer

/**
 * JNA mapping over `NuvioImageBridge.dll` (built from
 * `mediamp/mediamp-mpv/src/cpp/nuvio_image_bridge.cpp`).
 *
 * The native side decodes any image format WIC understands (JPEG, PNG,
 * BMP, TIFF, HEIF on Windows 11+, ICO, …) and downscales it via WIC's
 * high-quality cubic resampler — the same pipeline Microsoft Edge, the
 * Photos app, and File Explorer use to render images on Windows. Output
 * is pre-multiplied BGRA (PBGRA), tightly packed, ready to wrap as a
 * Skia bitmap on the JVM side.
 */
internal object NuvioImageBridge {

    /**
     * Result codes returned by [Native.nuvio_image_decode_scaled].
     * Mirror of `NuvioImageResult` in `nuvio_image_bridge.cpp`.
     */
    object ResultCode {
        const val OK: Int = 0
        const val ERR_INVALID_ARG: Int = -1
        const val ERR_FACTORY: Int = -2
        const val ERR_DECODE: Int = -3
        const val ERR_FRAME: Int = -4
        const val ERR_SCALE: Int = -5
        const val ERR_CONVERT: Int = -6
        const val ERR_COPY: Int = -7
        const val ERR_BUFFER_TOO_SMALL: Int = -8

        fun describe(code: Int): String = when (code) {
            OK -> "ok"
            ERR_INVALID_ARG -> "invalid-arg"
            ERR_FACTORY -> "wic-factory"
            ERR_DECODE -> "decode"
            ERR_FRAME -> "frame"
            ERR_SCALE -> "scale"
            ERR_CONVERT -> "format-convert"
            ERR_COPY -> "copy-pixels"
            ERR_BUFFER_TOO_SMALL -> "buffer-too-small"
            else -> "unknown($code)"
        }
    }

    /**
     * Native bindings. JNA loads the DLL by short name; resolution relies
     * on `WindowsNativeBootstrap.bootstrap()` having added the runtime's
     * `native/` directory via `SetDefaultDllDirectories` /
     * `AddDllDirectory` / `SetDllDirectoryW` before the first call here.
     */
    private interface Native : Library {
        fun nuvio_image_probe_size(
            input: ByteBuffer,
            inputSize: Int,
            outWidth: IntByReference,
            outHeight: IntByReference,
        ): Int

        fun nuvio_image_decode_scaled(
            input: ByteBuffer,
            inputSize: Int,
            targetWidth: Int,
            targetHeight: Int,
            outBuffer: ByteBuffer,
            outBufferSize: Int,
            outRowStrideBytes: IntByReference,
        ): Int
    }

    @Volatile
    private var nativeLib: Native? = null

    @Volatile
    private var loadAttempted: Boolean = false

    @Synchronized
    private fun load(): Native? {
        if (loadAttempted) return nativeLib
        loadAttempted = true
        nativeLib = runCatching { com.sun.jna.Native.load("NuvioImageBridge", Native::class.java) }
            .onFailure {
                DesktopRuntimeLog.warn(
                    "NuvioImageBridge load failed (image decoding will fall back to Skia): " +
                        "${it::class.simpleName}:${it.message}",
                )
            }
            .onSuccess {
                DesktopRuntimeLog.info("NuvioImageBridge loaded")
            }
            .getOrNull()
        return nativeLib
    }

    val isAvailable: Boolean
        get() = load() != null

    /**
     * Read source dimensions from encoded bytes without paying for a full
     * decode. Useful when the caller wants to clamp the target size to
     * the source size to avoid up-scaling.
     *
     * Returns `null` if the bridge is unavailable or the probe fails.
     */
    fun probeSize(encoded: ByteBuffer, encodedLen: Int): Pair<Int, Int>? {
        val lib = load() ?: return null
        val w = IntByReference(0)
        val h = IntByReference(0)
        val rc = runCatching { lib.nuvio_image_probe_size(encoded, encodedLen, w, h) }
            .getOrElse {
                DesktopRuntimeLog.warn("NuvioImageBridge probe threw ${it::class.simpleName}:${it.message}")
                return null
            }
        if (rc != ResultCode.OK || w.value <= 0 || h.value <= 0) {
            return null
        }
        return w.value to h.value
    }

    /**
     * Result of a successful decode: a direct buffer with PBGRA pixels
     * and the row stride WIC reported. The buffer is tightly packed so
     * `rowStrideBytes == widthPx * 4` in normal cases; honor the
     * reported stride anyway.
     */
    data class DecodedBitmap(
        val pixels: ByteBuffer,
        val widthPx: Int,
        val heightPx: Int,
        val rowStrideBytes: Int,
    )

    /**
     * Decode + downscale + colorspace-convert in one shot. The caller
     * provides a target draw size; the native side runs WIC's
     * `WICBitmapInterpolationModeHighQualityCubic` to land at exactly
     * that size. Pass `targetWidthPx`/`targetHeightPx` <= 0 to keep the
     * source size.
     */
    fun decodeScaled(
        encoded: ByteBuffer,
        encodedLen: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): DecodedBitmap? {
        val lib = load() ?: return null

        val outW = if (targetWidthPx > 0) targetWidthPx else 0
        val outH = if (targetHeightPx > 0) targetHeightPx else 0
        val resolvedW: Int
        val resolvedH: Int
        if (outW > 0 && outH > 0) {
            resolvedW = outW
            resolvedH = outH
        } else {
            val (sw, sh) = probeSize(encoded, encodedLen) ?: return null
            resolvedW = if (outW > 0) outW else sw
            resolvedH = if (outH > 0) outH else sh
        }

        val outSizeBytes = resolvedW.toLong() * resolvedH.toLong() * 4L
        if (outSizeBytes <= 0L || outSizeBytes > Int.MAX_VALUE.toLong()) return null

        val outBuffer = ByteBuffer.allocateDirect(outSizeBytes.toInt())
        val rowStride = IntByReference(0)
        val rc = runCatching {
            lib.nuvio_image_decode_scaled(
                encoded,
                encodedLen,
                resolvedW,
                resolvedH,
                outBuffer,
                outSizeBytes.toInt(),
                rowStride,
            )
        }.getOrElse {
            DesktopRuntimeLog.warn("NuvioImageBridge decode threw ${it::class.simpleName}:${it.message}")
            return null
        }

        if (rc != ResultCode.OK) {
            DesktopRuntimeLog.warn("NuvioImageBridge decode failed rc=${ResultCode.describe(rc)}")
            return null
        }

        outBuffer.position(0)
        outBuffer.limit(outSizeBytes.toInt())
        return DecodedBitmap(
            pixels = outBuffer,
            widthPx = resolvedW,
            heightPx = resolvedH,
            rowStrideBytes = rowStride.value.takeIf { it > 0 } ?: (resolvedW * 4),
        )
    }
}
