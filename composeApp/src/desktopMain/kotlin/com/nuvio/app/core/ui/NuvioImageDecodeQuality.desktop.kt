package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality

private const val DefaultDesktopQualityDecodeMultiplier = 2.0f
private const val WindowsDesktopQualityDecodeMultiplier = 1.35f
private const val DesktopQualityDecodeBucketPx = 64
private const val DefaultDesktopQualityDecodeMaxDimensionPx = 2560
private const val WindowsDesktopQualityDecodeMaxDimensionPx = 2048

private val isWindowsDesktop: Boolean =
    System.getProperty("os.name")
        ?.contains("windows", ignoreCase = true) == true

internal actual val NuvioImageFilterQuality: FilterQuality =
    if (isWindowsDesktop) FilterQuality.Medium else FilterQuality.High

/**
 * Keep the existing high-quality Desktop decode path on macOS/Linux, while
 * using a softer Windows budget to avoid heavy 2x downscales that make posters
 * look harsher than native web/macOS rendering and inflate image memory.
 */
internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx
        .coerceAtLeast(1)
        .scaleQualityDimension(
            multiplier = if (isWindowsDesktop) {
                WindowsDesktopQualityDecodeMultiplier
            } else {
                DefaultDesktopQualityDecodeMultiplier
            },
            maxPx = if (isWindowsDesktop) {
                WindowsDesktopQualityDecodeMaxDimensionPx
            } else {
                DefaultDesktopQualityDecodeMaxDimensionPx
            },
        )
        .roundUpToQualityBucket(DesktopQualityDecodeBucketPx)
