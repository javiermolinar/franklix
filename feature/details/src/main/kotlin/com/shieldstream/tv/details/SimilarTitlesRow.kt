package com.shieldstream.tv.details

import com.shieldstream.tv.strings.R as StringsR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.CatalogPosterCard
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.component.requestFocusWithRetry

/** One recommendations rail, loaded only when its Details section approaches the viewport. */
@Composable
fun SimilarTitlesRow(
    mediaType: MediaType,
    itemId: String,
    onOpenItem: (CatalogItem) -> Unit,
    viewModel: SimilarTitlesViewModel = hiltViewModel(key = "similar_${mediaType.name}_$itemId"),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    var loadRequested by rememberSaveable { mutableStateOf(false) }
    val cardRequester = remember { FocusRequester() }
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaType, itemId) {
        if (loadRequested) viewModel.load(mediaType, itemId)
    }
    LaunchedEffect(Unit) {
        if (lastCardKey != null) cardRequester.requestFocusWithRetry()
    }

    Column(
        Modifier.onGloballyPositioned { coordinates ->
            if (loadRequested) return@onGloballyPositioned
            val bounds = coordinates.boundsInWindow()
            if (bounds.bottom >= 0f && bounds.top <= view.height) {
                loadRequested = true
                viewModel.load(mediaType, itemId)
            }
        },
    ) {
        Text(
            text = localizedStringResource(StringsR.string.details_more_like_this),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 56.dp),
        )
        when (val current = state) {
            SimilarTitlesUiState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 56.dp, top = 12.dp, bottom = 12.dp),
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            SimilarTitlesUiState.Error -> Column(Modifier.padding(start = 56.dp, top = 8.dp)) {
                Text(
                    text = localizedStringResource(StringsR.string.details_similar_load_error),
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                TvButton(
                    text = localizedStringResource(StringsR.string.details_retry),
                    onClick = { viewModel.load(mediaType, itemId, force = true) },
                )
            }
            is SimilarTitlesUiState.Content -> {
                if (current.items.isEmpty()) {
                    Text(
                        text = localizedStringResource(StringsR.string.details_no_similar_titles),
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 56.dp, top = 8.dp),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(current.items, key = { it.key }) { item ->
                            CatalogPosterCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                onFocused = { lastCardKey = item.key },
                                width = 120.dp,
                                modifier = if (item.key == lastCardKey) Modifier.focusRequester(cardRequester) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
