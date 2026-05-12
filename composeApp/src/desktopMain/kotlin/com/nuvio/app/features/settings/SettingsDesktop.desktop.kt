package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.introdb_favicon
import nuvio.composeapp.generated.resources.mdblist_logo
import nuvio.composeapp.generated.resources.rating_tmdb
import nuvio.composeapp.generated.resources.trakt_tv_favicon
import org.jetbrains.compose.resources.painterResource
import java.util.Locale

internal actual object ThemeSettingsStorage {
    private const val preferencesName = "nuvio_theme_settings"
    private const val selectedThemeKey = "selected_theme"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val selectedAppLanguageKey = "selected_app_language"
    private const val lastSelectedAppLanguageKey = "last_selected_app_language"
    private val profileScopedSyncKeys = listOf(
        selectedThemeKey,
        amoledEnabledKey,
        liquidGlassNativeTabBarEnabledKey,
    )
    private val globalSyncKeys = listOf(selectedAppLanguageKey)

    actual fun loadSelectedTheme(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)
    }

    actual fun loadSelectedAppLanguage(): String? {
        val profileValue = loadProfileSelectedAppLanguage()
        if (profileValue != null) return profileValue

        val lastValue = DesktopPreferences.getString(preferencesName, lastSelectedAppLanguageKey)
        if (lastValue != null) return lastValue

        val legacyGlobal = DesktopPreferences.getString(preferencesName, selectedAppLanguageKey)
        if (legacyGlobal != null) {
            saveSelectedAppLanguage(legacyGlobal)
            return legacyGlobal
        }

        return AppLanguageDefaults.systemLanguageCode()
    }

    actual fun saveSelectedAppLanguage(languageCode: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(selectedAppLanguageKey), languageCode)
        DesktopPreferences.putString(preferencesName, lastSelectedAppLanguageKey, languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) {
        val normalizedCode = languageCode
            .trim()
            .takeIf { it.isNotBlank() }
            ?: AppLanguage.ENGLISH.code
        val locale = Locale.forLanguageTag(normalizedCode)
        Locale.setDefault(locale)
        Locale.setDefault(Locale.Category.DISPLAY, locale)
        Locale.setDefault(Locale.Category.FORMAT, locale)
        System.setProperty("user.language", locale.language)
        if (locale.country.isNotBlank()) {
            System.setProperty("user.country", locale.country)
        } else {
            System.clearProperty("user.country")
        }
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadProfileSelectedAppLanguage()?.let { put(selectedAppLanguageKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        profileScopedSyncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }
        globalSyncKeys.forEach { DesktopPreferences.remove(preferencesName, it) }

        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncString(selectedAppLanguageKey)?.let(::saveSelectedAppLanguage)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }

    private fun loadProfileSelectedAppLanguage(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(selectedAppLanguageKey))
}

@Composable
internal actual fun integrationLogoPainter(logo: IntegrationLogo): Painter =
    when (logo) {
        IntegrationLogo.Tmdb -> painterResource(Res.drawable.rating_tmdb)
        IntegrationLogo.Trakt -> painterResource(Res.drawable.trakt_tv_favicon)
        IntegrationLogo.MdbList -> painterResource(Res.drawable.mdblist_logo)
        IntegrationLogo.IntroDb -> painterResource(Res.drawable.introdb_favicon)
    }
