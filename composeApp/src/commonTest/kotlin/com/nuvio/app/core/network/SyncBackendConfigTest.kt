package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncBackendConfigTest {
    @Test
    fun nuvioCloudApiBaseUrlIsUsable() {
        val config = SyncBackendConfig(
            id = SYNC_BACKEND_NUVIO_ID,
            displayName = "Nuvio",
            supabaseUrl = " https://api.nuvio.tv/ ",
            anonKey = " sb_publishable_test ",
            avatarPublicBaseUrl = " https://api.nuvio.tv/storage/v1/object/public/avatars/ ",
        ).normalized()

        assertTrue(config.isUsableClientConfig())
        assertEquals("https://api.nuvio.tv", config.normalizedSupabaseUrl)
        assertEquals("sb_publishable_test", config.anonKey)
        assertEquals(
            "https://api.nuvio.tv/storage/v1/object/public/avatars/profile.png",
            config.avatarStorageUrl("/profile.png"),
        )
    }

    @Test
    fun unusableBackendsAreRejected() {
        val insecureBackend = SyncBackendConfig(
            id = SYNC_BACKEND_NUVIO_ID,
            displayName = "Nuvio",
            supabaseUrl = "http://api.nuvio.tv",
            anonKey = "sb_publishable_test",
            avatarPublicBaseUrl = "https://api.nuvio.tv/storage/v1/object/public/avatars",
        )
        val placeholderKey = insecureBackend.copy(
            supabaseUrl = "https://api.nuvio.tv",
            anonKey = "<set-me>",
        )
        val unknownBackend = insecureBackend.copy(
            id = "custom",
            supabaseUrl = "https://api.nuvio.tv",
        )

        assertFalse(insecureBackend.isUsableClientConfig())
        assertFalse(placeholderKey.isUsableClientConfig())
        assertFalse(unknownBackend.isUsableClientConfig())
    }

    @Test
    fun connectionIdentityTracksBackendEndpointAndSchema() {
        val base = SyncBackendConfig(
            id = SYNC_BACKEND_NUVIO_ID,
            displayName = "Nuvio",
            supabaseUrl = "https://api.nuvio.tv/",
            anonKey = "sb_publishable_test",
            avatarPublicBaseUrl = "https://api.nuvio.tv/storage/v1/object/public/avatars",
            schemaVersion = 1,
        )

        assertTrue(base.hasSameConnectionIdentity(base.copy(supabaseUrl = "https://api.nuvio.tv")))
        assertFalse(base.hasSameConnectionIdentity(base.copy(supabaseUrl = "https://example.test")))
        assertFalse(base.hasSameConnectionIdentity(base.copy(schemaVersion = 2)))
    }
}
