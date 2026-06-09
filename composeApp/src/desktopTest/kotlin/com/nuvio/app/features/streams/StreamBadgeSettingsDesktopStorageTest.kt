package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamBadgeSettingsDesktopStorageTest {
    @BeforeTest
    fun setUp() {
        clearDesktopBadgeStorage()
    }

    @AfterTest
    fun tearDown() {
        clearDesktopBadgeStorage()
    }

    @Test
    fun desktopStoragePersistsBadgePlacementAndFileSizeVisibility() {
        StreamBadgeSettingsRepository.ensureLoaded()

        StreamBadgeSettingsRepository.setBadgePlacement(StreamBadgePlacement.TOP)
        StreamBadgeSettingsRepository.setShowFileSizeBadges(false)
        StreamBadgeSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.ensureLoaded()

        val state = StreamBadgeSettingsRepository.uiState.value
        assertEquals(StreamBadgePlacement.TOP, state.badgePlacement)
        assertEquals(false, state.showFileSizeBadges)
    }

    @Test
    fun desktopStorageMigratesLegacyDebridBadgeRulesOnce() {
        val legacyPayload = """
            {
              "sourceUrl": "https://example.test/legacy-badges.json",
              "filters": [
                {
                  "name": "REMUX",
                  "pattern": "(?i)remux",
                  "imageURL": "https://example.test/remux.png"
                }
              ],
              "groups": []
            }
        """.trimIndent()

        DesktopPreferences.putString(
            "nuvio_debrid_settings",
            ProfileScopedKey.of("debrid_stream_badge_rules"),
            legacyPayload,
        )

        StreamBadgeSettingsRepository.ensureLoaded()

        val rules = StreamBadgeSettingsRepository.uiState.value.rules
        assertTrue(rules.hasImport)
        assertEquals("https://example.test/legacy-badges.json", rules.imports.single().sourceUrl)
        assertEquals("REMUX", rules.imports.single().filters.single().name)
        assertNull(
            DesktopPreferences.getString(
                "nuvio_debrid_settings",
                ProfileScopedKey.of("debrid_stream_badge_rules"),
            ),
        )
        assertTrue(
            StreamBadgeSettingsStorage.loadStreamBadgeRules()
                ?.contains("legacy-badges.json") == true,
        )
    }

    private fun clearDesktopBadgeStorage() {
        StreamBadgeSettingsRepository.clearLocalState()
        DesktopPreferences.clearNode("nuvio_stream_badge_settings")
        DesktopPreferences.clearNode("nuvio_debrid_settings")
    }
}
