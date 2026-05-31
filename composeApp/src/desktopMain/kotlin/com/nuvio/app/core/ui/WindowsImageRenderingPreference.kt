package com.nuvio.app.core.ui

import com.nuvio.app.desktop.DesktopPreferences

/**
 * Desktop-only user preference for the still-image rendering mode on Windows.
 *
 * `true`  (default) — **native WIC rendering**: the decode dimension is matched to the
 *                     measured pixel draw size and the native Windows Imaging Component
 *                     decoder owns the downscale, so Skia blits ~1:1 (clean render).
 * `false`           — **legacy Skia rendering**: the previous 2x over-decode + 64px bucket
 *                     path with Skia's own draw-time resampler. Kept as an escape hatch
 *                     because the OpenGL backend resampler can behave differently across
 *                     GPUs / drivers, so some PCs may prefer the legacy look.
 *
 * The value is read on hot paths (`nuvioQualityDecodeDimensionPx`) and once at startup
 * (decoder registration in `configurePlatformImageLoader`), so it is cached for the life
 * of the process; changing it requires an app restart to take effect cleanly (the Coil
 * [coil3.ImageLoader] and its decoded-bitmap caches are built once at launch). The UI
 * reads the freshly persisted value via [load] for its switch state.
 */
internal object WindowsImageRenderingPreference {
    private const val NAMESPACE = "nuvio_image_settings"
    private const val USE_NATIVE_WIC_KEY = "use_native_wic_rendering"

    /** Default ON: the native WIC path is the fix and the recommended mode. */
    private const val DEFAULT_ENABLED = true

    /** Persisted value (defaults to [DEFAULT_ENABLED] when unset). Use for UI state. */
    fun load(): Boolean =
        DesktopPreferences.getBoolean(NAMESPACE, USE_NATIVE_WIC_KEY) ?: DEFAULT_ENABLED

    fun save(enabled: Boolean) {
        DesktopPreferences.putBoolean(NAMESPACE, USE_NATIVE_WIC_KEY, enabled)
    }

    /**
     * Process-lifetime cached value for hot-path / startup reads. A change made via the
     * settings toggle is persisted immediately but only observed after a restart, which is
     * exactly the contract the toggle enforces.
     */
    val nativeWicEnabled: Boolean by lazy { load() }
}
