package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopPreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSettingsRepositoryDesktopTest {
    @AfterTest
    fun tearDown() {
        DesktopPreferences.clearNode("nuvio_player_settings")
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun externalPlayerTogglePublishesImmediatelyAndPersists() {
        DesktopPreferences.clearNode("nuvio_player_settings")
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()

        assertFalse(PlayerSettingsRepository.uiState.value.externalPlayerEnabled)

        PlayerSettingsRepository.setExternalPlayerEnabled(true)

        assertTrue(PlayerSettingsRepository.uiState.value.externalPlayerEnabled)
        assertTrue(PlayerSettingsStorage.loadExternalPlayerEnabled() == true)

        PlayerSettingsRepository.setExternalPlayerEnabled(false)

        assertFalse(PlayerSettingsRepository.uiState.value.externalPlayerEnabled)
        assertFalse(PlayerSettingsStorage.loadExternalPlayerEnabled() == true)
    }
}
