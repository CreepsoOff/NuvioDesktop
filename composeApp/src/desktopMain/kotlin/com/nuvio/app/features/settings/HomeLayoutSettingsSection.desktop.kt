package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.desktop.DesktopPreferences

private const val homePrefsNamespace = "nuvio_home_settings"
private const val alwaysAnimateGifKey = "always_animate_gif"

@Composable
internal actual fun HomeLayoutSettingsSection(isTablet: Boolean) {
    var alwaysAnimate by remember {
        mutableStateOf(
            DesktopPreferences.getBoolean(homePrefsNamespace, alwaysAnimateGifKey) ?: false
        )
    }

    SettingsSection(
        title = "Home",
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Always animate GIF (experimental)",
                description = "When enabled, collection GIFs animate continuously. When disabled, GIFs only animate on hover.",
                checked = alwaysAnimate,
                isTablet = isTablet,
                onCheckedChange = { checked ->
                    alwaysAnimate = checked
                    DesktopPreferences.putBoolean(homePrefsNamespace, alwaysAnimateGifKey, checked)
                },
            )
        }
    }
}
