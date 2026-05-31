package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

@Composable
internal actual fun ImageRenderingSettingsSection(isTablet: Boolean) {
    // No-op on iOS — the Windows image-rendering toggle is desktop-only.
}
