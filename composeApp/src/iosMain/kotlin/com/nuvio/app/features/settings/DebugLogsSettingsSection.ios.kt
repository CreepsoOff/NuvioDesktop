package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

@Composable
internal actual fun DebugLogsSettingsSection(isTablet: Boolean) {
    // No-op on iOS — debug logs are desktop-only
}
