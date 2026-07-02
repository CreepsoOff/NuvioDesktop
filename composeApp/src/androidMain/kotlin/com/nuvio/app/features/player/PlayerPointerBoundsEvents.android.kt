package com.nuvio.app.features.player

import androidx.compose.ui.Modifier

internal actual fun Modifier.playerSurfacePointerBoundsEvents(
    hoverDrivenChrome: Boolean,
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier = this
