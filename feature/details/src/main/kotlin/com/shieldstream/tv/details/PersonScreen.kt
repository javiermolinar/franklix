package com.shieldstream.tv.details

import com.shieldstream.tv.strings.R as StringsR
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.PersonDetails
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.ActionMenuDialog
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.CatalogPosterCard
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.TvButton
import com.shieldstream.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun PersonScreen(
    personId: Int,
    onBack: () -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
    viewModel: PersonViewModel = hiltViewModel(key = "person_$personId"),
) {
    LaunchedEffect(personId) { viewModel.load(personId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var menuItem by remember { mutableStateOf<CatalogItem?>(null) }

    BackHandler {
        if (menuItem != null) menuItem = null else onBack()
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        when (val current = state) {
            PersonUiState.Loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            PersonUiState.Error -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    localizedStringResource(StringsR.string.details_person_load_error),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                TvButton(
                    text = localizedStringResource(StringsR.string.details_retry),
                    onClick = { viewModel.load(personId, force = true) },
                )
            }
            is PersonUiState.Content -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(start = 52.dp, top = 28.dp, end = 52.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                item(key = "person_header", span = { GridItemSpan(maxLineSpan) }) {
                    PersonHeader(current.person)
                }
                items(current.person.filmography, key = { it.key }) { item ->
                    CatalogPosterCard(
                        item = item,
                        onClick = { onOpenItem(item) },
                        onLongClick = { menuItem = item },
                        width = 140.dp,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionUp || event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            val firstFilmographyRow = gridState.layoutInfo.visibleItemsInfo.any {
                                it.key == item.key && it.row == 1
                            }
                            if (!firstFilmographyRow) return@onPreviewKeyEvent false
                            coroutineScope.launch { gridState.animateScrollToItem(0) }
                            true
                        },
                    )
                }
            }
        }

        menuItem?.let { item ->
            val inMyList = (state as? PersonUiState.Content)?.myListKeys?.contains(item.key) == true
            ActionMenuDialog(
                title = item.title,
                onDismiss = { menuItem = null },
                consumeOpeningKeyRelease = true,
                actions = listOf(
                    ActionMenuItem(localizedStringResource(StringsR.string.details_cancel), LineIcons.Close) { menuItem = null },
                    ActionMenuItem(localizedStringResource(StringsR.string.home_view_details), AppIcons.List) {
                        menuItem = null
                        onOpenItem(item)
                    },
                    ActionMenuItem(
                        text = localizedStringResource(
                            if (inMyList) StringsR.string.details_remove_from_my_list else StringsR.string.details_add_to_my_list,
                        ),
                        icon = if (inMyList) LineIcons.Delete else AppIcons.Add,
                    ) {
                        viewModel.toggleMyList(item)
                        menuItem = null
                    },
                ),
            )
        }
    }
}

@Composable
private fun PersonHeader(person: PersonDetails) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = person.profileUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(AppColors.Placeholder),
            )
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    text = person.name,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localizedStringResource(StringsR.string.details_filmography) + " · ${person.filmography.size}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (person.biography.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = person.biography,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = localizedStringResource(StringsR.string.details_hold_ok_for_title_options),
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
