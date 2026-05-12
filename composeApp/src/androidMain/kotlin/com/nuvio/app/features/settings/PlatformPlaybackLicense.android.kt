package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_licenses_attributions_exoplayer_body
import nuvio.composeapp.generated.resources.settings_licenses_attributions_exoplayer_license
import nuvio.composeapp.generated.resources.settings_licenses_attributions_exoplayer_title
import org.jetbrains.compose.resources.stringResource

private const val ApacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"

@Composable
internal actual fun platformPlaybackLicense(): PlatformPlaybackLicense =
    PlatformPlaybackLicense(
        title = stringResource(Res.string.settings_licenses_attributions_exoplayer_title),
        body = stringResource(Res.string.settings_licenses_attributions_exoplayer_body),
        license = stringResource(Res.string.settings_licenses_attributions_exoplayer_license),
        link = ApacheLicenseUrl,
    )
