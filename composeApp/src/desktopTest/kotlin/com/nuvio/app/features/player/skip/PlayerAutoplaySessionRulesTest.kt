package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from NuvioTV's AutoplaySessionCountTest + StillWatchingGatingTest, adapted to
 * kotlin.test. Covers the pure "Still Watching?" autoplay-guard decision logic.
 */
class PlayerAutoplaySessionRulesTest {

    // --- nextConsecutiveAutoPlayCount ---

    @Test
    fun `manual selection resets count to zero`() {
        assertEquals(
            0,
            PlayerAutoplaySessionRules.nextConsecutiveAutoPlayCount(currentCount = 5, isAutoPlay = false),
        )
    }

    @Test
    fun `autoplay increments count`() {
        assertEquals(
            4,
            PlayerAutoplaySessionRules.nextConsecutiveAutoPlayCount(currentCount = 3, isAutoPlay = true),
        )
    }

    @Test
    fun `first autoplay from zero increments to one`() {
        assertEquals(
            1,
            PlayerAutoplaySessionRules.nextConsecutiveAutoPlayCount(currentCount = 0, isAutoPlay = true),
        )
    }

    // --- shouldEnterStillWatchingPrompt ---

    @Test
    fun `gating returns false when still-watching setting disabled`() {
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = false,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when auto-play next episode disabled`() {
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = false,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when next episode has not aired`() {
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = false,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when consecutive count below threshold`() {
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 2,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns true when all conditions met`() {
        assertTrue(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 3,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false for non-positive threshold`() {
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 5,
                threshold = 0,
            ),
        )
    }

    @Test
    fun `default threshold gates at three consecutive autoplays`() {
        // Below default threshold.
        assertFalse(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = PlayerAutoplaySessionRules.DEFAULT_STILL_WATCHING_THRESHOLD - 1,
            ),
        )
        // At default threshold.
        assertTrue(
            PlayerAutoplaySessionRules.shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = PlayerAutoplaySessionRules.DEFAULT_STILL_WATCHING_THRESHOLD,
            ),
        )
    }
}
