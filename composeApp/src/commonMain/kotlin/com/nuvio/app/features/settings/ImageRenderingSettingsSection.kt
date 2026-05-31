package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Desktop-only settings section letting the user choose the still-image rendering mode
 * (native WIC vs. legacy Skia). No-op on Android / iOS.
 */
@Composable
internal expect fun ImageRenderingSettingsSection(isTablet: Boolean)
