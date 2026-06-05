package com.nuvio.app.features.player.skip

/**
 * Pure decision logic for the autoplay "Still Watching?" guard, ported from NuvioTV
 * (`PlayerAutoplaySessionRules` / `shouldEnterStillWatchingPrompt`).
 *
 * The player tracks how many episodes in a row have started via auto-play (as opposed
 * to a manual pick). After enough consecutive auto-plays, the player can pause and ask
 * "Still watching?" instead of silently continuing a runaway binge session. All of this
 * is platform-independent state math, so it lives in commonMain and is unit-tested
 * directly; the desktop player UI/overlay wires into these decisions separately.
 */
object PlayerAutoplaySessionRules {

    /** Default number of consecutive auto-plays before the prompt is offered. */
    const val DEFAULT_STILL_WATCHING_THRESHOLD: Int = 3

    /**
     * Advance the consecutive auto-play counter for the next episode start.
     *
     * @param currentCount the running count of back-to-back auto-played episodes.
     * @param isAutoPlay whether the next episode started via auto-play (`true`) or a
     *        manual selection (`false`). A manual selection resets the streak to zero.
     */
    fun nextConsecutiveAutoPlayCount(
        currentCount: Int,
        isAutoPlay: Boolean,
    ): Int = if (isAutoPlay) currentCount + 1 else 0

    /**
     * Whether the player should interrupt auto-play with a "Still watching?" prompt
     * before starting the next episode.
     *
     * Mirrors NuvioTV's gating: the feature must be enabled, auto-play-next must be on,
     * the next episode must have actually aired (never prompt for an unaired episode),
     * and the consecutive auto-play streak must have reached the threshold.
     */
    fun shouldEnterStillWatchingPrompt(
        stillWatchingEnabled: Boolean,
        autoPlayNextEpisodeEnabled: Boolean,
        nextEpisodeHasAired: Boolean,
        consecutiveAutoPlayCount: Int,
        threshold: Int = DEFAULT_STILL_WATCHING_THRESHOLD,
    ): Boolean {
        if (!stillWatchingEnabled) return false
        if (!autoPlayNextEpisodeEnabled) return false
        if (!nextEpisodeHasAired) return false
        if (threshold <= 0) return false
        return consecutiveAutoPlayCount >= threshold
    }
}
