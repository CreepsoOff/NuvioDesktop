package com.nuvio.app.features.player

internal actual object ExternalPlayerPlatform {
    actual fun defaultPlayerId(): String? = null

    actual fun availablePlayers(): List<ExternalPlayerApp> = emptyList()

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult =
        if (playerId.isNullOrBlank()) {
            ExternalPlayerOpenResult.NotConfigured
        } else {
            ExternalPlayerOpenResult.NoPlayerAvailable
        }
}
