package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.FilterQuality

internal actual val NuvioImageFilterQuality: FilterQuality = FilterQuality.High

internal actual fun nuvioQualityDecodeDimensionPx(displayDimensionPx: Int): Int =
    displayDimensionPx.coerceAtLeast(1)
