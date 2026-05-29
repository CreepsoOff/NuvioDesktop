package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.tmdbBucketedUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the bucket selection the collection card uses for its static
 * Coil request. The previous "force /original/" path made Windows download
 * 3840 px JPEGs for ~130 px cards, which is the root cause of the
 * Mitchell-bicubic ringing users saw on the home shelves.
 */
class CollectionCardRemoteImageSourceTest {
    @Test
    fun `shelf-sized collection cover targets the w342 TMDB bucket`() {
        val url = "https://image.tmdb.org/t/p/original/example.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/w342/example.jpg",
            url.tmdbBucketedUrl(targetWidthPx = 130),
        )
    }

    @Test
    fun `tablet-sized collection cover targets the w500 TMDB bucket`() {
        val url = "https://image.tmdb.org/t/p/w1280/example.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/w500/example.jpg",
            url.tmdbBucketedUrl(targetWidthPx = 260),
        )
    }

    @Test
    fun `non TMDB collection covers pass through unchanged`() {
        val url = "https://example.com/images/w1280/example.jpg"
        assertEquals(url, url.tmdbBucketedUrl(targetWidthPx = 130))
    }
}
