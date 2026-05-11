package com.nuvio.app.features.settings

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.desktop.DesktopRuntimeLog

private const val debugPrefsNamespace = "nuvio_debug"
private const val debugLogsEnabledKey = "debug_logs_enabled"

@Composable
internal actual fun DebugLogsSettingsSection(isTablet: Boolean) {
    var enabled by remember {
        mutableStateOf(
            DesktopPreferences.getBoolean(debugPrefsNamespace, debugLogsEnabledKey) ?: false
        )
    }

    SettingsSection(
        title = "Debugging",
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Enable Debug Logs",
                description = "Writes detailed debug information to desktop-runtime.log for troubleshooting player, UI, and performance issues. Log file location: %LOCALAPPDATA%/Nuvio/cache/logs/",
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = { checked ->
                    enabled = checked
                    DesktopPreferences.putBoolean(debugPrefsNamespace, debugLogsEnabledKey, checked)
                    if (!checked) {
                        DesktopRuntimeLog.info("Debug logs disabled by user")
                    }
                    DesktopRuntimeLog.debugEnabled = checked
                    if (checked) {
                        DesktopRuntimeLog.info("Debug logs enabled by user")
                    }
                },
            )
        }
    }
}
