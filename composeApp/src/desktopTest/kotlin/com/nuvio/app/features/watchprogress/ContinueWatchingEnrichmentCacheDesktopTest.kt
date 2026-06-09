package com.nuvio.app.features.watchprogress

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinueWatchingEnrichmentCacheDesktopTest {
    @BeforeTest
    fun setUp() {
        ContinueWatchingEnrichmentCache.clearAll()
    }

    @AfterTest
    fun tearDown() {
        ContinueWatchingEnrichmentCache.clearAll()
    }

    @Test
    fun desktopCachePersistsNextUpReleaseAlertFieldsAndInProgressItems() {
        val nextUp = CachedNextUpItem(
            contentId = "series:show",
            contentType = "series",
            name = "Show",
            videoId = "series:show:2:1",
            season = 2,
            episode = 1,
            episodeTitle = "Premiere",
            released = "2026-06-09",
            hasAired = true,
            lastWatched = 1_000L,
            sortTimestamp = 2_000L,
            seedSeason = 1,
            seedEpisode = 8,
            isReleaseAlert = true,
            isNewSeasonRelease = true,
        )
        val inProgress = CachedInProgressItem(
            contentId = "movie:film",
            contentType = "movie",
            name = "Film",
            videoId = "movie:film",
            position = 42_000L,
            duration = 100_000L,
            lastWatched = 3_000L,
            progressPercent = 42f,
        )

        ContinueWatchingEnrichmentCache.saveSnapshots(
            nextUp = listOf(nextUp),
            inProgress = listOf(inProgress),
        )

        val (storedNextUp, storedInProgress) = ContinueWatchingEnrichmentCache.getSnapshots()
        assertEquals(listOf(nextUp), storedNextUp)
        assertEquals(listOf(inProgress), storedInProgress)
        assertTrue(storedNextUp.single().isReleaseAlert)
        assertTrue(storedNextUp.single().isNewSeasonRelease)
    }

    @Test
    fun desktopCacheClearRemovesSavedSnapshots() {
        ContinueWatchingEnrichmentCache.saveSnapshots(
            nextUp = listOf(
                CachedNextUpItem(
                    contentId = "series:show",
                    contentType = "series",
                    name = "Show",
                    videoId = "series:show:1:2",
                    lastWatched = 1L,
                    sortTimestamp = 2L,
                ),
            ),
            inProgress = emptyList(),
        )

        assertEquals(1, ContinueWatchingEnrichmentCache.getNextUpSnapshot().size)

        ContinueWatchingEnrichmentCache.clearAll()

        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot().isEmpty())
        assertTrue(ContinueWatchingEnrichmentCache.getInProgressSnapshot().isEmpty())
    }
}
