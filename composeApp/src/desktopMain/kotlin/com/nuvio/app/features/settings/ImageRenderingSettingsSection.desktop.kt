package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.WindowsImageRenderingPreference
import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.desktop.restartDesktopApp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_image_rendering_high_quality_description
import nuvio.composeapp.generated.resources.settings_image_rendering_high_quality_title
import nuvio.composeapp.generated.resources.settings_image_rendering_restart_cancel
import nuvio.composeapp.generated.resources.settings_image_rendering_restart_confirm
import nuvio.composeapp.generated.resources.settings_image_rendering_restart_disable_message
import nuvio.composeapp.generated.resources.settings_image_rendering_restart_enable_message
import nuvio.composeapp.generated.resources.settings_image_rendering_restart_title
import nuvio.composeapp.generated.resources.settings_image_rendering_section
import org.jetbrains.compose.resources.stringResource

private val isWindowsDesktop: Boolean by lazy {
    System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
}

@Composable
internal actual fun ImageRenderingSettingsSection(isTablet: Boolean) {
    // Desktop-only, and only meaningful on Windows (the WIC decoder + OpenGL-backend
    // resampler are Windows-specific). macOS/Linux keep their fixed path, so hide it there.
    if (!isWindowsDesktop) return

    var useNativeWic by remember { mutableStateOf(WindowsImageRenderingPreference.load()) }
    var pendingValue by remember { mutableStateOf<Boolean?>(null) }

    SettingsSection(
        title = stringResource(Res.string.settings_image_rendering_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_image_rendering_high_quality_title),
                description = stringResource(Res.string.settings_image_rendering_high_quality_description),
                checked = useNativeWic,
                isTablet = isTablet,
                onCheckedChange = { requested ->
                    // Defer persisting until the user confirms the restart, so the switch
                    // and the on-disk preference never disagree if they cancel.
                    pendingValue = requested
                },
            )
        }
    }

    val target = pendingValue
    NuvioStatusModal(
        title = stringResource(Res.string.settings_image_rendering_restart_title),
        message = if (target == true) {
            stringResource(Res.string.settings_image_rendering_restart_enable_message)
        } else {
            stringResource(Res.string.settings_image_rendering_restart_disable_message)
        },
        isVisible = target != null,
        confirmText = stringResource(Res.string.settings_image_rendering_restart_confirm),
        dismissText = stringResource(Res.string.settings_image_rendering_restart_cancel),
        onConfirm = {
            if (target != null) {
                WindowsImageRenderingPreference.save(target)
                useNativeWic = target
                DesktopRuntimeLog.info("Image rendering mode changed: nativeWic=$target; restarting app")
                pendingValue = null
                restartDesktopApp()
            }
        },
        onDismiss = {
            // Revert the visual switch to the persisted value; nothing was saved.
            useNativeWic = WindowsImageRenderingPreference.load()
            pendingValue = null
        },
    )
}
