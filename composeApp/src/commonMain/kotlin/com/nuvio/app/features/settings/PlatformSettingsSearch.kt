package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Platform-specific entries contributed to the Settings Search index.
 *
 * Desktop contributes rows for Desktop-only surfaces (Keybinds, Debug Logs, Desktop decoder,
 * Always Animate GIFs) so they are searchable from the Settings root. Mobile platforms
 * contribute nothing here — their Settings Search index is entirely defined by
 * [settingsSearchEntries].
 */
@Composable
internal expect fun platformSettingsSearchEntries(): List<SettingsSearchEntry>
