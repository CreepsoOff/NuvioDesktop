package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean =
    remember {
        { intentResult ->
            val launch = intentResult.intent as? DesktopExternalPlayerLaunch
            if (launch == null) {
                false
            } else {
                ExternalPlayerPlatform.open(
                    request = launch.request,
                    playerId = launch.playerId,
                ) == ExternalPlayerOpenResult.Opened
            }
        }
    }
