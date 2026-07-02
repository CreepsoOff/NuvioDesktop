package com.nuvio.app.features.player

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.playerSurfacePointerBoundsEvents(
    hoverDrivenChrome: Boolean,
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier {
    if (!hoverDrivenChrome) return this
    return this
        .onPointerEvent(PointerEventType.Enter) { onEnter() }
        .onPointerEvent(PointerEventType.Exit) { onExit() }
}
