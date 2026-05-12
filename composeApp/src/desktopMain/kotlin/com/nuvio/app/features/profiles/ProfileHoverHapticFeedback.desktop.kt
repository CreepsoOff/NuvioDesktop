package com.nuvio.app.features.profiles

/**
 * Desktop actual for [ProfileHoverHapticFeedback].
 *
 * Desktop has no haptic hardware (no taptic engine, no vibration motor) and no
 * equivalent OS API on Windows/macOS/Linux, so every member is a no-op. This
 * mirrors the "Platform additions from upstream (mobile)" policy from design
 * Part 2: mobile-only surfaces on Desktop keep a no-op actual so the shared
 * composable sites that invoke the helper compile and run without pulling any
 * mobile-only APIs into desktopMain.
 *
 * The Android actual is also a no-op today; the iOS actual wraps
 * `UISelectionFeedbackGenerator`. If haptics are ever added to Desktop, this
 * file is the single place to wire them.
 */
internal actual object ProfileHoverHapticFeedback {
    actual fun prepare() = Unit
    actual fun perform() = Unit
    actual fun release() = Unit
}
