package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.KeybindEntry
import com.nuvio.app.features.player.KeybindsConfig
import com.nuvio.app.features.player.KeybindsStorage
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_keybind_action_cycle_speed
import nuvio.composeapp.generated.resources.settings_keybind_action_cycle_speed_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_exit_fullscreen
import nuvio.composeapp.generated.resources.settings_keybind_action_exit_fullscreen_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_mute
import nuvio.composeapp.generated.resources.settings_keybind_action_mute_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_next_episode
import nuvio.composeapp.generated.resources.settings_keybind_action_next_episode_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_play_pause
import nuvio.composeapp.generated.resources.settings_keybind_action_play_pause_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_seek_backward
import nuvio.composeapp.generated.resources.settings_keybind_action_seek_backward_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_seek_forward
import nuvio.composeapp.generated.resources.settings_keybind_action_seek_forward_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_skip_intro
import nuvio.composeapp.generated.resources.settings_keybind_action_skip_intro_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_toggle_app_fullscreen
import nuvio.composeapp.generated.resources.settings_keybind_action_toggle_app_fullscreen_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_toggle_fullscreen
import nuvio.composeapp.generated.resources.settings_keybind_action_toggle_fullscreen_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_volume_down
import nuvio.composeapp.generated.resources.settings_keybind_action_volume_down_desc
import nuvio.composeapp.generated.resources.settings_keybind_action_volume_up
import nuvio.composeapp.generated.resources.settings_keybind_action_volume_up_desc
import nuvio.composeapp.generated.resources.settings_keybind_recording
import nuvio.composeapp.generated.resources.settings_keybind_reset_defaults
import nuvio.composeapp.generated.resources.settings_keybinds_description
import nuvio.composeapp.generated.resources.settings_keybinds_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class KeybindActionDescriptor(
    val action: String,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
)

private val KeybindActionDescriptors = listOf(
    KeybindActionDescriptor(
        "toggle_fullscreen",
        Res.string.settings_keybind_action_toggle_fullscreen,
        Res.string.settings_keybind_action_toggle_fullscreen_desc,
    ),
    KeybindActionDescriptor(
        "toggle_app_fullscreen",
        Res.string.settings_keybind_action_toggle_app_fullscreen,
        Res.string.settings_keybind_action_toggle_app_fullscreen_desc,
    ),
    KeybindActionDescriptor(
        "exit_fullscreen",
        Res.string.settings_keybind_action_exit_fullscreen,
        Res.string.settings_keybind_action_exit_fullscreen_desc,
    ),
    KeybindActionDescriptor(
        "play_pause",
        Res.string.settings_keybind_action_play_pause,
        Res.string.settings_keybind_action_play_pause_desc,
    ),
    KeybindActionDescriptor(
        "seek_forward_10s",
        Res.string.settings_keybind_action_seek_forward,
        Res.string.settings_keybind_action_seek_forward_desc,
    ),
    KeybindActionDescriptor(
        "seek_backward_10s",
        Res.string.settings_keybind_action_seek_backward,
        Res.string.settings_keybind_action_seek_backward_desc,
    ),
    KeybindActionDescriptor(
        "volume_up",
        Res.string.settings_keybind_action_volume_up,
        Res.string.settings_keybind_action_volume_up_desc,
    ),
    KeybindActionDescriptor(
        "volume_down",
        Res.string.settings_keybind_action_volume_down,
        Res.string.settings_keybind_action_volume_down_desc,
    ),
    KeybindActionDescriptor(
        "mute",
        Res.string.settings_keybind_action_mute,
        Res.string.settings_keybind_action_mute_desc,
    ),
    KeybindActionDescriptor(
        "cycle_speed",
        Res.string.settings_keybind_action_cycle_speed,
        Res.string.settings_keybind_action_cycle_speed_desc,
    ),
    KeybindActionDescriptor(
        "next_episode",
        Res.string.settings_keybind_action_next_episode,
        Res.string.settings_keybind_action_next_episode_desc,
    ),
    KeybindActionDescriptor(
        "skip_intro",
        Res.string.settings_keybind_action_skip_intro,
        Res.string.settings_keybind_action_skip_intro_desc,
    ),
)

@Composable
internal actual fun KeybindsSettingsContent(isTablet: Boolean) {
    var config by remember { mutableStateOf(KeybindsStorage.load()) }
    var recordingAction by remember { mutableStateOf<String?>(null) }
    val currentRecordingAction by rememberUpdatedState(recordingAction)

    fun saveBinding(action: String, keyCode: Int, modifiers: Int) {
        val defaults = KeybindsConfig.defaultKeybinds().associateBy { it.action }
        val nextBinds = config.binds.map { entry ->
            when {
                entry.action == action -> entry.copy(keyCode = keyCode, modifiers = modifiers)
                entry.keyCode == keyCode && entry.modifiers == modifiers -> defaults[entry.action] ?: entry
                else -> entry
            }
        }
        KeybindsStorage.save(KeybindsConfig(nextBinds))
        config = KeybindsStorage.load()
        recordingAction = null
    }

    DisposableEffect(recordingAction) {
        if (recordingAction == null) {
            return@DisposableEffect onDispose { }
        }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            val action = currentRecordingAction ?: return@KeyEventDispatcher false
            if (event.id != AwtKeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.keyCode.isModifierOnlyKey()) return@KeyEventDispatcher true
            val modifiers = event.modifiersEx and SupportedModifierMask
            saveBinding(action, event.keyCode, modifiers)
            true
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose {
            focusManager.removeKeyEventDispatcher(dispatcher)
        }
    }

    SettingsSection(
        title = stringResource(Res.string.settings_keybinds_title),
        isTablet = isTablet,
        actions = {
            TextButton(
                onClick = {
                    KeybindsStorage.save(KeybindsConfig())
                    config = KeybindsStorage.load()
                    recordingAction = null
                },
            ) {
                Text(stringResource(Res.string.settings_keybind_reset_defaults))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_keybinds_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 720.dp),
            )
            SettingsGroup(isTablet = isTablet) {
                KeybindActionDescriptors.forEachIndexed { index, descriptor ->
                    val entry = config.binds.firstOrNull { it.action == descriptor.action }
                        ?: KeybindsConfig.defaultKeybinds().first { it.action == descriptor.action }
                    KeybindRow(
                        descriptor = descriptor,
                        entry = entry,
                        isTablet = isTablet,
                        isRecording = recordingAction == descriptor.action,
                        onClick = { recordingAction = descriptor.action },
                    )
                    if (index < KeybindActionDescriptors.lastIndex) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeybindRow(
    descriptor: KeybindActionDescriptor,
    entry: KeybindEntry,
    isTablet: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 14.dp else 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(descriptor.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(descriptor.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isRecording) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            },
            contentColor = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        ) {
            Text(
                text = if (isRecording) {
                    stringResource(Res.string.settings_keybind_recording)
                } else {
                    entry.displayLabel()
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private const val SupportedModifierMask =
    AwtKeyEvent.CTRL_DOWN_MASK or
        AwtKeyEvent.ALT_DOWN_MASK or
        AwtKeyEvent.SHIFT_DOWN_MASK or
        AwtKeyEvent.META_DOWN_MASK

private fun Int.isModifierOnlyKey(): Boolean =
    this == AwtKeyEvent.VK_SHIFT ||
        this == AwtKeyEvent.VK_CONTROL ||
        this == AwtKeyEvent.VK_ALT ||
        this == AwtKeyEvent.VK_META ||
        this == AwtKeyEvent.VK_ALT_GRAPH

private fun KeybindEntry.displayLabel(): String = buildString {
    if (modifiers and AwtKeyEvent.CTRL_DOWN_MASK != 0) append("Ctrl+")
    if (modifiers and AwtKeyEvent.ALT_DOWN_MASK != 0) append("Alt+")
    if (modifiers and AwtKeyEvent.SHIFT_DOWN_MASK != 0) append("Shift+")
    if (modifiers and AwtKeyEvent.META_DOWN_MASK != 0) append("Meta+")
    append(AwtKeyEvent.getKeyText(keyCode))
}
