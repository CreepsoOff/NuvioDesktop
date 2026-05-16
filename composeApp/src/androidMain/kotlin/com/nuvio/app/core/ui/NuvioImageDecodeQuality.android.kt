package com.nuvio.app.core.ui

internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx.coerceAtLeast(1)
