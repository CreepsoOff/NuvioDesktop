package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Desktop playback attribution — reflects the MPV / MediaMP / libmpv playback stack that
 * powers the Desktop player. Replaces the Android-default ExoPlayer entry and the iOS
 * MPVKit entry on Windows/macOS/Linux Desktop builds.
 *
 * Strings are intentionally kept in English (not resource-keyed) because the shared
 * Licenses & Attributions section does not yet have localized keys for libmpv and
 * MediaMP. Task 9.1 tracks the follow-up to land these as localized resource keys.
 */
@Composable
internal actual fun platformPlaybackLicense(): PlatformPlaybackLicense =
    PlatformPlaybackLicense(
        title = "libmpv / MediaMP",
        body = "Used for playback on Desktop builds. Integrates libmpv via the MediaMP Compose Multiplatform bridge.",
        license = "libmpv is distributed under LGPL-2.1-or-later with portions under GPL-2.0-or-later. MediaMP is licensed under Apache-2.0. FFmpeg components bundled with libmpv are licensed under LGPL-2.1-or-later.",
        link = "https://mpv.io/",
    )
