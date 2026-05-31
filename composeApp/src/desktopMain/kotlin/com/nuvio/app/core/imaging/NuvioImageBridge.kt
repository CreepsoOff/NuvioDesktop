package com.nuvio.app.core.imaging

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * JNA binding for `NuvioImageBridge.dll` — the native Windows Imaging Component (WIC)
 * still-image decoder that lets Skia receive a bitmap already at the draw size so it
 * blits ~1:1 instead of resampling on the OpenGL backend (the source of the crispy /
 * ringy artifact on Windows Desktop).
 *
 * Windows-only: the JNA load is guarded by [isWindows] so the binding never tries to
 * resolve the DLL on Android, iOS, macOS, or Linux. When the DLL is missing (or this is
 * not Windows) [isAvailable] is `false` — it never throws — and callers fall back to the
 * default Coil decoder. A warning is logged once via [DesktopRuntimeLog]; the app still
 * starts.
 *
 * The native ABI mirrored here (see `mediamp/mediamp-mpv/src/cpp/nuvio_image_bridge.cpp`):
 *
 * ```c
 * int32_t nuvio_image_probe_size(const uint8_t* input, int32_t input_size,
 *                                int32_t* out_width, int32_t* out_height);
 * int32_t nuvio_image_decode_scaled(const uint8_t* input, int32_t input_size,
 *                                   int32_t target_width, int32_t target_height,
 *                                   uint8_t* out_buffer, int32_t out_buffer_size,
 *                                   int32_t* out_row_stride_bytes);
 * ```
 *
 * On [ResultCode.OK] the decode output buffer holds PBGRA (B, G, R, A premultiplied),
 * 32bpp, top-down, tightly packed with `row_stride == width * 4` — the exact layout
 * `org.jetbrains.skia.Bitmap` expects for `BGRA_8888` / `PREMUL` / sRGB.
 */
internal object NuvioImageBridge {

    /** DLL base name passed to [Native.load]; resolves `NuvioImageBridge.dll` on Windows. */
    private const val LIBRARY_NAME = "NuvioImageBridge"

    /**
     * Mirrors the native `NuvioImageResult` enum (`int32_t`). The negative codes match the
     * C++ contract one-for-one; [UNAVAILABLE] and [UNKNOWN] are JVM-side sentinels that
     * never cross the ABI (the DLL never returns them).
     */
    internal enum class ResultCode(val code: Int) {
        OK(0),
        ERR_INVALID_ARG(-1),
        ERR_FACTORY(-2),
        ERR_DECODE(-3),
        ERR_FRAME(-4),
        ERR_SCALE(-5),
        ERR_CONVERT(-6),
        ERR_COPY(-7),
        ERR_BUFFER_TOO_SMALL(-8),

        /** JVM-side only: the DLL is not loaded (missing or non-Windows). Never returned by native code. */
        UNAVAILABLE(1),

        /** JVM-side only: native returned a code not present in this enum. */
        UNKNOWN(Int.MIN_VALUE),
        ;

        companion object {
            fun fromCode(code: Int): ResultCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /** Source pixel dimensions returned by [probeSize]. */
    internal data class ProbeResult(val width: Int, val height: Int)

    /**
     * Outcome of [decodeScaled]. On [ResultCode.OK], [rowStrideBytes] is the actual row
     * stride WIC wrote into the output buffer (`width * 4` in the normal case).
     */
    internal data class DecodeResult(val resultCode: ResultCode, val rowStrideBytes: Int) {
        val isOk: Boolean get() = resultCode == ResultCode.OK
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    /**
     * Lazily resolves the native library. Kept behind the [isWindows] guard so the binding
     * never loads off Windows, and wrapped in [runCatching] so a missing DLL degrades to a
     * logged warning + `null` (graceful fallback) instead of throwing at startup.
     */
    private val library: NuvioImageBridgeLib? by lazy {
        if (!isWindows) {
            return@lazy null
        }
        runCatching { Native.load(LIBRARY_NAME, NuvioImageBridgeLib::class.java) }.fold(
            onSuccess = { loaded ->
                DesktopRuntimeLog.info("NuvioImageBridge loaded")
                loaded
            },
            onFailure = { throwable ->
                DesktopRuntimeLog.warn(
                    "NuvioImageBridge.dll unavailable; falling back to the default Coil decoder " +
                        "(${throwable.message ?: throwable.javaClass.simpleName})",
                )
                null
            },
        )
    }

    /** `true` only when running on Windows and the DLL loaded successfully. Never throws. */
    val isAvailable: Boolean
        get() = library != null

    /**
     * Reads the source image dimensions without a full decode. Mirrors
     * `nuvio_image_probe_size`. Returns `null` when the bridge is unavailable or the native
     * call reports an error.
     */
    fun probeSize(input: ByteArray): ProbeResult? {
        val lib = library ?: return null
        if (input.isEmpty()) return null
        val outWidth = IntByReference()
        val outHeight = IntByReference()
        val code = lib.nuvio_image_probe_size(input, input.size, outWidth, outHeight)
        if (ResultCode.fromCode(code) != ResultCode.OK) return null
        return ProbeResult(outWidth.value, outHeight.value)
    }

    /**
     * Decodes [input] and scales it to [targetWidth] x [targetHeight] (pass `<= 0` for
     * either dimension to skip scaling and use the source size), writing PBGRA pixels into
     * the caller-allocated [outBuffer]. Mirrors `nuvio_image_decode_scaled`.
     *
     * [outBuffer] must be at least `targetWidth * 4 * targetHeight` bytes; [outBufferSize]
     * is its capacity and is checked natively. Returns [ResultCode.UNAVAILABLE] without
     * touching [outBuffer] when the bridge is not loaded.
     */
    fun decodeScaled(
        input: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        outBuffer: Pointer,
        outBufferSize: Int,
    ): DecodeResult {
        val lib = library ?: return DecodeResult(ResultCode.UNAVAILABLE, 0)
        val outRowStride = IntByReference()
        val code = lib.nuvio_image_decode_scaled(
            input,
            input.size,
            targetWidth,
            targetHeight,
            outBuffer,
            outBufferSize,
            outRowStride,
        )
        return DecodeResult(ResultCode.fromCode(code), outRowStride.value)
    }

    /**
     * Raw JNA mapping of the `extern "C"` exports. Method names match the native symbols
     * exactly so JNA resolves them by name (cdecl, the default for [Library]).
     */
    private interface NuvioImageBridgeLib : Library {
        fun nuvio_image_probe_size(
            input: ByteArray,
            inputSize: Int,
            outWidth: IntByReference,
            outHeight: IntByReference,
        ): Int

        fun nuvio_image_decode_scaled(
            input: ByteArray,
            inputSize: Int,
            targetWidth: Int,
            targetHeight: Int,
            outBuffer: Pointer,
            outBufferSize: Int,
            outRowStrideBytes: IntByReference,
        ): Int
    }
}
