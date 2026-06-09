package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraktRelatedRepositoryTest {
    @Test
    fun `related path id resolves direct imdb trakt and numeric ids without tmdb lookup`() {
        assertEquals("tt1234567", TraktRelatedRepository.resolveRelatedPathId("tt1234567:movie"))
        assertEquals("tt7654321", TraktRelatedRepository.resolveRelatedPathId("https://trakt.tv/movies/tt7654321"))
        assertEquals("99", TraktRelatedRepository.resolveRelatedPathId("trakt:99"))
        assertEquals("77", TraktRelatedRepository.resolveRelatedPathId("77:show"))
        assertEquals("12345", TraktRelatedRepository.resolveRelatedPathId("12345"))
    }

    @Test
    fun `related path id does not treat tmdb ids as trakt path ids`() {
        assertNull(TraktRelatedRepository.resolveRelatedPathId("tmdb:550"))
        assertNull(TraktRelatedRepository.resolveRelatedPathId("tmdb:550:movie"))
        assertNull(TraktRelatedRepository.resolveRelatedPathId("not-a-supported-id"))
        assertNull(TraktRelatedRepository.resolveRelatedPathId(" "))
    }

    @Test
    fun `related tmdb candidate resolves tmdb aliases only`() {
        assertEquals(550, TraktRelatedRepository.resolveRelatedTmdbCandidate("tmdb:550"))
        assertEquals(550, TraktRelatedRepository.resolveRelatedTmdbCandidate("tmdb:550:movie"))
        assertEquals(550, TraktRelatedRepository.resolveRelatedTmdbCandidate("tmdb:550/path"))
        assertNull(TraktRelatedRepository.resolveRelatedTmdbCandidate("trakt:550"))
        assertNull(TraktRelatedRepository.resolveRelatedTmdbCandidate("tt0137523"))
    }

    @Test
    fun `related urls are encoded and include pagination defaults`() {
        assertEquals(
            "https://api.trakt.tv/search/tmdb/550?type=movie&page=1&limit=20",
            TraktRelatedRepository.buildTraktUrl(
                endpoint = "/search/tmdb/550/",
                query = mapOf("type" to "movie"),
            ),
        )
        assertEquals(
            "https://api.trakt.tv/movies/tt0137523/related?extended=full%2Cimages&page=1&limit=20",
            TraktRelatedRepository.buildTraktUrl(
                endpoint = "movies/tt0137523/related",
                query = mapOf("extended" to "full,images"),
            ),
        )
    }
}
