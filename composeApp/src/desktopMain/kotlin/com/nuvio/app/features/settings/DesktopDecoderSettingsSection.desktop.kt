package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.PlayerTargetPrimaries
import com.nuvio.app.features.player.PlayerTargetTransfer
import com.nuvio.app.features.player.PlayerToneMappingMode
import com.nuvio.app.features.player.PlayerVideoOutputPreset
import com.nuvio.app.features.player.desktop.mpv.DesktopBrightnessKey
import com.nuvio.app.features.player.desktop.mpv.DesktopContrastKey
import com.nuvio.app.features.player.desktop.mpv.DesktopDebandEnabledKey
import com.nuvio.app.features.player.desktop.mpv.DesktopGammaKey
import com.nuvio.app.features.player.desktop.mpv.DesktopHdrComputePeakKey
import com.nuvio.app.features.player.desktop.mpv.DesktopInterpolationEnabledKey
import com.nuvio.app.features.player.desktop.mpv.DesktopSaturationKey
import com.nuvio.app.features.player.desktop.mpv.loadDesktopMpvVideoTuning
import com.nuvio.app.features.player.desktop.mpv.storeDesktopBooleanTuning
import com.nuvio.app.features.player.desktop.mpv.storeDesktopHardwareDecoderMode
import com.nuvio.app.features.player.desktop.mpv.storeDesktopIntTuning
import com.nuvio.app.features.player.desktop.mpv.storeDesktopTargetPrimaries
import com.nuvio.app.features.player.desktop.mpv.storeDesktopTargetTransfer
import com.nuvio.app.features.player.desktop.mpv.storeDesktopToneMappingMode
import com.nuvio.app.features.player.desktop.mpv.storeDesktopVideoOutputPreset
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_desktop_deband
import nuvio.composeapp.generated.resources.settings_playback_desktop_gamma
import nuvio.composeapp.generated.resources.settings_playback_desktop_hdr_compute_peak
import nuvio.composeapp.generated.resources.settings_playback_desktop_hwdec
import nuvio.composeapp.generated.resources.settings_playback_desktop_interpolation
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_auto
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_bt1886
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_bt2020
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_bt2390
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_bt709
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_clip
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_compatibility
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_custom
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_d3d11va
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_d3d11va_copy
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_display_p3
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_dxva2
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_gamma
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_gamma22
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_gamma24
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_hable
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_hlg
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_mobius
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_native
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_no
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_nvdec
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_nvdec_copy
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_pq
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_reinhard
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_sdr_tone_mapped
import nuvio.composeapp.generated.resources.settings_playback_desktop_option_srgb
import nuvio.composeapp.generated.resources.settings_playback_desktop_section_video
import nuvio.composeapp.generated.resources.settings_playback_desktop_target_primaries
import nuvio.composeapp.generated.resources.settings_playback_desktop_target_transfer
import nuvio.composeapp.generated.resources.settings_playback_desktop_tone_mapping
import nuvio.composeapp.generated.resources.settings_playback_desktop_video_brightness
import nuvio.composeapp.generated.resources.settings_playback_desktop_video_contrast
import nuvio.composeapp.generated.resources.settings_playback_desktop_video_output
import nuvio.composeapp.generated.resources.settings_playback_desktop_video_saturation
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun DesktopDecoderSettingsSection(isTablet: Boolean) {
    var tuning by remember { mutableStateOf(loadDesktopMpvVideoTuning().settings) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showHwdecDialog by remember { mutableStateOf(false) }
    var showToneMappingDialog by remember { mutableStateOf(false) }
    var showPrimariesDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }

    fun refresh() {
        tuning = loadDesktopMpvVideoTuning().settings
    }

    SettingsSection(
        title = stringResource(Res.string.settings_playback_desktop_section_video),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_desktop_video_output),
                description = stringResource(tuning.outputPreset.labelRes()),
                isTablet = isTablet,
                onClick = { showPresetDialog = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_desktop_hwdec),
                description = stringResource(tuning.hardwareDecoderMode.labelRes()),
                isTablet = isTablet,
                onClick = { showHwdecDialog = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_desktop_tone_mapping),
                description = stringResource(tuning.toneMappingMode.labelRes()),
                isTablet = isTablet,
                onClick = { showToneMappingDialog = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_desktop_target_primaries),
                description = stringResource(tuning.targetPrimaries.labelRes()),
                isTablet = isTablet,
                onClick = { showPrimariesDialog = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_desktop_target_transfer),
                description = stringResource(tuning.targetTransfer.labelRes()),
                isTablet = isTablet,
                onClick = { showTransferDialog = true },
            )
        }

        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_desktop_hdr_compute_peak),
                description = null,
                checked = tuning.hdrComputePeakEnabled,
                isTablet = isTablet,
                onCheckedChange = {
                    storeDesktopBooleanTuning(DesktopHdrComputePeakKey, it)
                    refresh()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_desktop_deband),
                description = null,
                checked = tuning.debandEnabled,
                isTablet = isTablet,
                onCheckedChange = {
                    storeDesktopBooleanTuning(DesktopDebandEnabledKey, it)
                    refresh()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_desktop_interpolation),
                description = null,
                checked = tuning.interpolationEnabled,
                isTablet = isTablet,
                onCheckedChange = {
                    storeDesktopBooleanTuning(DesktopInterpolationEnabledKey, it)
                    refresh()
                },
            )
        }

        SettingsGroup(isTablet = isTablet) {
            VideoEqSlider(
                title = stringResource(Res.string.settings_playback_desktop_video_brightness),
                value = tuning.brightness,
                isTablet = isTablet,
                onValueCommitted = {
                    storeDesktopIntTuning(DesktopBrightnessKey, it)
                    refresh()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            VideoEqSlider(
                title = stringResource(Res.string.settings_playback_desktop_video_contrast),
                value = tuning.contrast,
                isTablet = isTablet,
                onValueCommitted = {
                    storeDesktopIntTuning(DesktopContrastKey, it)
                    refresh()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            VideoEqSlider(
                title = stringResource(Res.string.settings_playback_desktop_video_saturation),
                value = tuning.saturation,
                isTablet = isTablet,
                onValueCommitted = {
                    storeDesktopIntTuning(DesktopSaturationKey, it)
                    refresh()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            VideoEqSlider(
                title = stringResource(Res.string.settings_playback_desktop_gamma),
                value = tuning.gamma,
                isTablet = isTablet,
                onValueCommitted = {
                    storeDesktopIntTuning(DesktopGammaKey, it)
                    refresh()
                },
            )
        }
    }

    if (showPresetDialog) {
        OptionDialog(
            title = stringResource(Res.string.settings_playback_desktop_video_output),
            options = PlayerVideoOutputPreset.entries,
            selected = tuning.outputPreset,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                storeDesktopVideoOutputPreset(it)
                refresh()
                showPresetDialog = false
            },
            onDismiss = { showPresetDialog = false },
        )
    }
    if (showHwdecDialog) {
        OptionDialog(
            title = stringResource(Res.string.settings_playback_desktop_hwdec),
            options = PlayerHardwareDecoderMode.entries,
            selected = tuning.hardwareDecoderMode,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                storeDesktopHardwareDecoderMode(it)
                refresh()
                showHwdecDialog = false
            },
            onDismiss = { showHwdecDialog = false },
        )
    }
    if (showToneMappingDialog) {
        OptionDialog(
            title = stringResource(Res.string.settings_playback_desktop_tone_mapping),
            options = PlayerToneMappingMode.entries,
            selected = tuning.toneMappingMode,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                storeDesktopToneMappingMode(it)
                refresh()
                showToneMappingDialog = false
            },
            onDismiss = { showToneMappingDialog = false },
        )
    }
    if (showPrimariesDialog) {
        OptionDialog(
            title = stringResource(Res.string.settings_playback_desktop_target_primaries),
            options = PlayerTargetPrimaries.entries,
            selected = tuning.targetPrimaries,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                storeDesktopTargetPrimaries(it)
                refresh()
                showPrimariesDialog = false
            },
            onDismiss = { showPrimariesDialog = false },
        )
    }
    if (showTransferDialog) {
        OptionDialog(
            title = stringResource(Res.string.settings_playback_desktop_target_transfer),
            options = PlayerTargetTransfer.entries,
            selected = tuning.targetTransfer,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                storeDesktopTargetTransfer(it)
                refresh()
                showTransferDialog = false
            },
            onDismiss = { showTransferDialog = false },
        )
    }
}

@Composable
private fun VideoEqSlider(
    title: String,
    value: Int,
    isTablet: Boolean,
    onValueCommitted: (Int) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ValueBox(text = sliderValue.toInt().toString(), modifier = Modifier.wrapContentWidth())
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueCommitted(sliderValue.toInt().coerceIn(-100, 100)) },
            valueRange = -100f..100f,
            steps = 199,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                        ) {
                            Text(
                                text = label(option),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PlayerVideoOutputPreset.labelRes(): StringResource =
    when (this) {
        PlayerVideoOutputPreset.Native -> Res.string.settings_playback_desktop_option_native
        PlayerVideoOutputPreset.ToneMappedSdr -> Res.string.settings_playback_desktop_option_sdr_tone_mapped
        PlayerVideoOutputPreset.Compatibility -> Res.string.settings_playback_desktop_option_compatibility
        PlayerVideoOutputPreset.Custom -> Res.string.settings_playback_desktop_option_custom
    }

private fun PlayerHardwareDecoderMode.labelRes(): StringResource =
    when (this) {
        PlayerHardwareDecoderMode.Auto -> Res.string.settings_playback_desktop_option_auto
        PlayerHardwareDecoderMode.Off -> Res.string.settings_playback_desktop_option_no
        PlayerHardwareDecoderMode.D3d11va -> Res.string.settings_playback_desktop_option_d3d11va
        PlayerHardwareDecoderMode.D3d11vaCopy -> Res.string.settings_playback_desktop_option_d3d11va_copy
        PlayerHardwareDecoderMode.Dxva2 -> Res.string.settings_playback_desktop_option_dxva2
        PlayerHardwareDecoderMode.Nvdec -> Res.string.settings_playback_desktop_option_nvdec
        PlayerHardwareDecoderMode.NvdecCopy -> Res.string.settings_playback_desktop_option_nvdec_copy
    }

private fun PlayerToneMappingMode.labelRes(): StringResource =
    when (this) {
        PlayerToneMappingMode.Auto -> Res.string.settings_playback_desktop_option_auto
        PlayerToneMappingMode.Bt2390 -> Res.string.settings_playback_desktop_option_bt2390
        PlayerToneMappingMode.Mobius -> Res.string.settings_playback_desktop_option_mobius
        PlayerToneMappingMode.Reinhard -> Res.string.settings_playback_desktop_option_reinhard
        PlayerToneMappingMode.Hable -> Res.string.settings_playback_desktop_option_hable
        PlayerToneMappingMode.Gamma -> Res.string.settings_playback_desktop_option_gamma
        PlayerToneMappingMode.Clip -> Res.string.settings_playback_desktop_option_clip
    }

private fun PlayerTargetPrimaries.labelRes(): StringResource =
    when (this) {
        PlayerTargetPrimaries.Auto -> Res.string.settings_playback_desktop_option_auto
        PlayerTargetPrimaries.Bt709 -> Res.string.settings_playback_desktop_option_bt709
        PlayerTargetPrimaries.DisplayP3 -> Res.string.settings_playback_desktop_option_display_p3
        PlayerTargetPrimaries.Bt2020 -> Res.string.settings_playback_desktop_option_bt2020
    }

private fun PlayerTargetTransfer.labelRes(): StringResource =
    when (this) {
        PlayerTargetTransfer.Auto -> Res.string.settings_playback_desktop_option_auto
        PlayerTargetTransfer.Srgb -> Res.string.settings_playback_desktop_option_srgb
        PlayerTargetTransfer.Bt1886 -> Res.string.settings_playback_desktop_option_bt1886
        PlayerTargetTransfer.Gamma22 -> Res.string.settings_playback_desktop_option_gamma22
        PlayerTargetTransfer.Gamma24 -> Res.string.settings_playback_desktop_option_gamma24
        PlayerTargetTransfer.Pq -> Res.string.settings_playback_desktop_option_pq
        PlayerTargetTransfer.Hlg -> Res.string.settings_playback_desktop_option_hlg
    }
