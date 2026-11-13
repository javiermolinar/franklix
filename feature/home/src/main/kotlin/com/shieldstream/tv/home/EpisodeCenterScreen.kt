package com.shieldstream.tv.home

import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.ShowEpisodeStatus
import com.shieldstream.tv.strings.localizedCode
import com.shieldstream.tv.ui.component.ActionMenuDialog
import com.shieldstream.tv.ui.component.ActionMenuItem
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.NewEpisodeCard
import com.shieldstream.tv.ui.component.formatEpisodeDate
import com.shieldstream.tv.ui.component.requestFocusWithRetry

private data class EpisodeMenu(
    val status: ShowEpisodeStatus,
    val episode: Episode,
    val watched: Boolean,
    val inMyList: Boolean,
)

@Composable
fun EpisodeCenterScreen(
    onOpenEpisode: (CatalogItem, Episode) -> Unit,
    viewModel: EpisodeCenterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cardRequester = remember { FocusRequester() }
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cardToRestore by remember { mutableStateOf<String?>(null) }
    var episodeMenu by remember { mutableStateOf<EpisodeMenu?>(null) }
    LaunchedEffect(Unit) { viewModel.activate() }
    LaunchedEffect(cardToRestore) {
        if (cardToRestore == null) return@LaunchedEffect
        cardRequester.requestFocusWithRetry()
        cardToRestore = null
    }

    fun closeMenu() {
        cardToRestore = lastCardKey
        episodeMenu = null
    }

    fun closeToNeighbour(status: ShowEpisodeStatus) {
        val items = (state as? EpisodeCenterUiState.Content)?.available.orEmpty()
        val index = items.indexOfFirst { it.item.key == status.item.key }
        val neighbour = if (index < 0) null else items.getOrNull(index + 1) ?: items.getOrNull(index - 1)
        lastCardKey = neighbour?.item?.key
        cardToRestore = lastCardKey
        episodeMenu = null
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 36.dp, top = 24.dp)) {
        Text(
            text = localizedStringResource(StringsR.string.home_episodes),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            EpisodeCenterUiState.Loading -> Text(localizedStringResource(StringsR.string.home_finding_episodes), color = Color.White.copy(alpha = 0.6f))
            is EpisodeCenterUiState.Content -> {
                if (s.available.isEmpty() && s.upcoming.isEmpty()) {
                    Text(
                        text = localizedStringResource(StringsR.string.home_no_recent_or_upcoming_episodes_in_your),
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                        if (s.available.isNotEmpty()) {
                            item(key = "available_header") {
                                SectionTitle(localizedStringResource(StringsR.string.home_available_now) + " · ${s.newEpisodeCount}")
                            }
                            item(key = "available_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(s.available, key = { it.item.key }) { status ->
                                        val episode = status.availableEpisodes.first()
                                        val episodeCode = episode.localizedCode()
                                        val label = remember(episode, episodeCode) { episodeLabel(episode, episodeCode) }
                                        NewEpisodeCard(
                                            item = status.item,
                                            label = label,
                                            count = status.availableEpisodes.size,
                                            onClick = { onOpenEpisode(status.item, episode) },
                                            onFocused = { lastCardKey = status.item.key },
                                            onLongClick = {
                                                episodeMenu = EpisodeMenu(
                                                    status = status,
                                                    episode = episode,
                                                    watched = viewModel.isEpisodeWatched(status.item, episode),
                                                    inMyList = viewModel.isInMyList(status.item),
                                                )
                                            },
                                            modifier = if (status.item.key == lastCardKey) Modifier.focusRequester(cardRequester) else Modifier,
                                        )
                                    }
                                }
                            }
                            item(key = "dismiss_hint") {
                                Text(
                                    text = localizedStringResource(StringsR.string.details_hold_ok_for_episode_options),
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (s.upcoming.isNotEmpty()) {
                            item(key = "upcoming_header") {
                                Spacer(Modifier.height(20.dp))
                                SectionTitle(localizedStringResource(StringsR.string.home_next_30_days))
                            }
                            item(key = "upcoming_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(s.upcoming, key = { it.item.key }) { status ->
                                        val episode = status.upcomingEpisode!!
                                        val episodeCode = episode.localizedCode()
                                        val label = remember(episode, episodeCode) { episodeLabel(episode, episodeCode) }
                                        NewEpisodeCard(
                                            item = status.item,
                                            label = label,
                                            count = 0,
                                            onClick = { onOpenEpisode(status.item, episode) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    episodeMenu?.let { menu ->
        ActionMenuDialog(
            title = "${menu.status.item.title} · ${menu.episode.localizedCode()}",
            onDismiss = { closeMenu() },
            consumeOpeningKeyRelease = true,
            actions = listOf(
                ActionMenuItem(localizedStringResource(StringsR.string.home_cancel), LineIcons.Close) { closeMenu() },
                ActionMenuItem(
                    text = localizedStringResource(
                        if (menu.watched) StringsR.string.details_episode_mark_unwatched else StringsR.string.details_episode_mark_watched,
                    ),
                    icon = if (menu.watched) AppIcons.Check else LineIcons.Eye,
                    onClick = {
                        if (menu.watched || menu.status.availableEpisodes.size > 1) closeMenu() else closeToNeighbour(menu.status)
                        viewModel.toggleEpisodeWatched(menu.status.item, menu.episode)
                    },
                ),
                ActionMenuItem(
                    text = localizedStringResource(
                        if (menu.inMyList) StringsR.string.details_remove_from_my_list else StringsR.string.details_add_to_my_list,
                    ),
                    icon = if (menu.inMyList) LineIcons.Delete else AppIcons.Add,
                    onClick = {
                        closeMenu()
                        viewModel.toggleMyList(menu.status.item)
                    },
                ),
                ActionMenuItem(
                    text = localizedStringResource(StringsR.string.home_dismiss),
                    icon = LineIcons.Close,
                    onClick = {
                        closeToNeighbour(menu.status)
                        viewModel.dismiss(menu.status)
                    },
                ),
            ),
        )
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
}

private fun episodeLabel(episode: Episode, episodeCode: String): String =
    listOf(episodeCode, formatEpisodeDate(episode.airDate)).filter { it.isNotBlank() }.joinToString(" · ")
