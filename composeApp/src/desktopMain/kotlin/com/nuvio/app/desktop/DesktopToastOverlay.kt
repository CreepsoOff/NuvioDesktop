package com.nuvio.app.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastMessage
import kotlinx.coroutines.delay

@Composable
fun DesktopToastOverlay(modifier: Modifier = Modifier) {
    val currentToast by androidx.compose.runtime.collectAsState(NuvioToastController.currentToast)
    var renderedToast by remember { mutableStateOf<NuvioToastMessage?>(null) }
    var visibility by remember { mutableStateOf(false) }

    LaunchedEffect(currentToast) {
        if (currentToast != null) {
            renderedToast = currentToast
            visibility = true
            delay(currentToast.durationMillis)
            visibility = false
            delay(300)
            if (NuvioToastController.currentToast.value?.id == currentToast.id) {
                renderedToast = null
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(
            visible = visibility && renderedToast != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            renderedToast?.let { toast ->
                Surface(
                    modifier = Modifier
                        .padding(bottom = 24.dp, end = 24.dp)
                        .fillMaxWidth(0.4f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                ) {
                    Text(
                        text = toast.message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
    }
}
