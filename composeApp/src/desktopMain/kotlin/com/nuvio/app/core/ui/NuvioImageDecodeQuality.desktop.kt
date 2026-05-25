package com.nuvio.app.core.ui

private const val DesktopQualityDecodeMultiplier = 2.0f
private const val DesktopQualityDecodeBucketPx = 64
private const val DesktopQualityDecodeMaxDimensionPx = 2560

/**
 * Desktop images are decoded above their exact layout size to keep posters sharp under
 * high-DPI scaling, crop transforms, rounded clipping, and fractional window sizes.
 */
internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx
        .coerceAtLeast(1)
        .scaleQualityDimension(
            multiplier = DesktopQualityDecodeMultiplier,
            maxPx = DesktopQualityDecodeMaxDimensionPx,
        )
        .roundUpToQualityBucket(DesktopQualityDecodeBucketPx)
