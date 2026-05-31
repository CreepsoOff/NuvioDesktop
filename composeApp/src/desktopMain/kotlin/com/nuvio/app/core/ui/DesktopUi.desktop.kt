package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.nuvio.app.core.imaging.WicCoilImageDecoder
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import kotlin.system.exitProcess
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ic_player_aspect_ratio
import nuvio.composeapp.generated.resources.ic_player_audio_filled
import nuvio.composeapp.generated.resources.ic_player_pause
import nuvio.composeapp.generated.resources.ic_player_play
import nuvio.composeapp.generated.resources.ic_player_subtitles
import nuvio.composeapp.generated.resources.library_add_plus
import org.jetbrains.compose.resources.painterResource

internal actual val nuvioPlatformExtraTopPadding: Dp = 0.dp
internal actual val nuvioPlatformExtraBottomPadding: Dp = 0.dp
internal actual val nuvioBottomNavigationExtraVerticalPadding: Dp = 6.dp

@Composable
internal actual fun nuvioBottomNavigationBarInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

@Composable
actual fun appIconPainter(icon: AppIconResource): Painter =
    painterResource(
        when (icon) {
            AppIconResource.PlayerPlay -> Res.drawable.ic_player_play
            AppIconResource.PlayerPause -> Res.drawable.ic_player_pause
            AppIconResource.PlayerAspectRatio -> Res.drawable.ic_player_aspect_ratio
            AppIconResource.PlayerSubtitles -> Res.drawable.ic_player_subtitles
            AppIconResource.PlayerAudioFilled -> Res.drawable.ic_player_audio_filled
            AppIconResource.LibraryAddPlus -> Res.drawable.library_add_plus
        }
    )

private val isWindowsDesktop: Boolean by lazy {
    System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
}

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    if (!isWindowsDesktop) return this
    // Honor the user's image-rendering choice (default: native WIC). When the user
    // opts into legacy Skia rendering, skip the WIC decoder so Coil uses the built-in
    // Skia decoder path. Read once at startup; toggling requires an app restart.
    if (!WindowsImageRenderingPreference.nativeWicEnabled) return this
    return components {
        // Added last so Coil tries the WIC decoder first; it routes WIC-handled
        // still formats through the native pipeline and returns null otherwise,
        // letting the built-in Skia decoder handle the rest.
        add(WicCoilImageDecoder.Factory())
    }
}

actual fun platformExitApp() {
    exitProcess(0)
}

internal actual object PosterCardStyleStorage {
    private const val preferencesName = "nuvio_poster_card_style"
    private const val payloadKey = "poster_card_style_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}
