package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun DetailPosterRailSection(
    title: String,
    items: List<MetaPreview>,
    watchedKeys: Set<String>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    sourceLabel: String? = null,
    headerHorizontalPadding: Dp = 0.dp,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    NuvioShelfSection(
        title = if (showHeader) listOfNotNull(title, sourceLabel).joinToString(" - ") else "",
        entries = items,
        modifier = modifier,
        headerHorizontalPadding = headerHorizontalPadding,
        rowContentPadding = PaddingValues(horizontal = headerHorizontalPadding),
        showHeaderAccent = false,
        key = { item -> item.stableKey() },
    ) { item ->
        HomePosterCard(
            item = item,
            isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item,
            ),
            onClick = onPosterClick?.let { { it(item) } },
            onLongClick = onPosterLongClick?.let { { it(item) } },
        )
    }
}
