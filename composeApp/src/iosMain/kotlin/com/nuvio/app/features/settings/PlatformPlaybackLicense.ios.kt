package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_licenses_attributions_mpvkit_body
import nuvio.composeapp.generated.resources.settings_licenses_attributions_mpvkit_license
import nuvio.composeapp.generated.resources.settings_licenses_attributions_mpvkit_title
import org.jetbrains.compose.resources.stringResource

private const val MpvKitUrl = "https://github.com/mpvkit/MPVKit"

@Composable
internal actual fun platformPlaybackLicense(): PlatformPlaybackLicense =
    PlatformPlaybackLicense(
        title = stringResource(Res.string.settings_licenses_attributions_mpvkit_title),
        body = stringResource(Res.string.settings_licenses_attributions_mpvkit_body),
        license = stringResource(Res.string.settings_licenses_attributions_mpvkit_license),
        link = MpvKitUrl,
    )
