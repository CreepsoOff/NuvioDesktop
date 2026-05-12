package com.nuvio.app.features.collection

/**
 * Desktop actual for [CollectionMobileSettingsStorage].
 *
 * The "mobile collection settings" surface (folder-level GIF animation
 * overrides, keyed by `mobileFocusGifEnabled` on each [CollectionFolder])
 * is explicitly mobile-scoped in build 61 — there is no Desktop UI to
 * toggle it, and Desktop already has the standalone "Always Animate GIFs"
 * Desktop setting for GIF behavior.
 *
 * Per design Part 2 "Platform additions from upstream (mobile)": "On
 * Desktop: keep no-op or existing actuals. Do not introduce mobile-only
 * APIs into desktopMain." This actual is an inert stub that satisfies the
 * expect interface for any shared call site that happens to reach it.
 *
 * Behavior:
 *   - `loadPayload()` returns `null` → [CollectionMobileSettingsRepository]
 *     treats the payload as empty, leaving the folder override map empty,
 *     and [CollectionMobileSettingsRepository.isFolderGifEnabled] falls
 *     back to `true` for every folder. This matches the default Desktop
 *     product behavior (GIFs enabled, modulated by the existing Desktop
 *     "Always Animate GIFs" setting).
 *   - `savePayload(...)` is a no-op. There is no Desktop UI that writes
 *     here today; if the shared Repository ever calls `persist()` on
 *     Desktop, the payload is silently discarded without regressing
 *     Android/iOS persistence (their actuals are independent).
 *
 * If Desktop ever gains a mobile-style folder-GIF editor, swap this stub
 * for a `DesktopPreferences`-backed implementation following the same
 * pattern as [com.nuvio.app.desktop.DesktopPreferences] usage elsewhere.
 */
internal actual object CollectionMobileSettingsStorage {
    actual fun loadPayload(): String? = null

    actual fun savePayload(payload: String) = Unit
}
