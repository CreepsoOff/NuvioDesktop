package com.nuvio.app.features.player

import androidx.compose.ui.Modifier

internal expect fun Modifier.playerSurfacePointerBoundsEvents(
    hoverDrivenChrome: Boolean,
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier
