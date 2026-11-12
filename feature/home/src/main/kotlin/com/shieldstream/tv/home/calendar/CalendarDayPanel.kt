package com.shieldstream.tv.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.domain.CalendarRules
import com.shieldstream.tv.domain.CalendarShowEpisodes
import com.shieldstream.tv.domain.CalendarTag
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedCode
import com.shieldstream.tv.strings.localizedPluralResource
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.PosterCard
import com.shieldstream.tv.ui.component.StatusPill
import com.shieldstream.tv.ui.theme.AppColors
import com.shieldstream.tv.ui.theme.AppShapes
import com.shieldstream.tv.ui.theme.CalendarColors

/**
 * Panel sizes. Each section is one row that scrolls sideways. The row runs to the screen edge, so
 * after four posters (4 × 108 + 3 × 16 = 480 dp of the 496 dp panel) the fifth shows 28 dp at the
 * edge, a sign there are more. Height is the limit: two sections, each a label, a 162 dp poster and a
 * caption with a two-line title, fill the 504 dp column.
 */
internal object PanelMetrics {
    val PosterWidth = 108.dp
    val Gap = 16.dp
    val TitleSize = 15.sp
    val MetaSize = 13.sp

    /** Two title lines and the meta line. */
    val CaptionHeight = 60.dp

    /** Room for the 1.04× focus scale, which the row would otherwise clip. */
    val FocusInset = 4.dp
}

enum class CalendarSection { EPISODES, RELEASES }

/** One tile of a section: one show's episodes of the day, or a release. */
internal sealed interface PanelTile {
    data class ShowTile(val show: CalendarShowEpisodes) : PanelTile
    data class ReleaseTile(val release: CalendarRelease) : PanelTile
}

@Composable
internal fun SectionLabel(title: String, count: String) {
    // Aligned with the posters, which start after the row's focus inset.
    Row(Modifier.padding(start = PanelMetrics.FocusInset), verticalAlignment = Alignment.Bottom) {
        Text(title, color = CalendarColors.Text, style = calendarText(18.sp, medium = true), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(count, color = CalendarColors.TextSecondary, style = calendarText(15.sp), maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
    }
}

@Composable
internal fun PanelMessage(text: String) {
    Text(
        text,
        color = CalendarColors.TextSecondary,
        style = calendarText(22.sp),
        modifier = Modifier.widthIn(max = 400.dp),
    )
}

/**
 * All of a section's posters in one lazy row. Focus moves through the navigator; a focused poster
 * brings itself into view, and [CalendarFocusHolder.focusPoster] scrolls first for items that are
 * not composed (restoring focus after details).
 */
@Composable
internal fun PosterRow(
    tiles: List<PanelTile>,
    today: CalendarDate,
    state: LazyListState,
    requester: (Int) -> FocusRequester,
    onFocused: (Int) -> Unit,
    onOpenEpisode: (CalendarEpisode) -> Unit,
    onOpenRelease: (CalendarRelease) -> Unit,
) {
    LazyRow(
        state = state,
        // The row's own focus search is not used; keys are routed by the navigator.
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = PanelMetrics.FocusInset, top = PanelMetrics.FocusInset, end = PanelMetrics.FocusInset),
        horizontalArrangement = Arrangement.spacedBy(PanelMetrics.Gap),
    ) {
        itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
            val modifier = Modifier.focusRequester(requester(index))
            when (tile) {
                is PanelTile.ShowTile -> ShowPoster(tile.show, today, { onFocused(index) }, { onOpenEpisode(tile.show.target) }, modifier)
                is PanelTile.ReleaseTile -> ReleasePoster(tile.release, { onFocused(index) }, { onOpenRelease(tile.release) }, modifier)
            }
        }
    }
}

private val PanelTile.key: String
    get() = when (this) {
        is PanelTile.ShowTile -> "s:" + show.show.key
        is PanelTile.ReleaseTile -> "r:" + release.item.key
    }

/** One card per show: "S2:E8" for one episode, "S2:E1–8" for a run, "3 episodes" otherwise. */
@Composable
private fun ShowPoster(
    show: CalendarShowEpisodes,
    today: CalendarDate,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val first = show.episodes.first().episode
    val range = remember(show) { CalendarRules.episodeRange(show) }
    val meta = when {
        show.episodes.size == 1 -> first.localizedCode()
        range != null -> localizedStringResource(StringsR.string.calendar_episode_range, range.first, range.second, range.third)
        else -> localizedPluralResource(StringsR.plurals.calendar_episodes, show.episodes.size, show.episodes.size)
    }
    val tag = remember(show, today) { CalendarRules.tag(show, today) }
    CalendarPosterCard(
        item = show.show,
        meta = meta,
        dimmed = show.watched,
        tag = tag,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier,
        topEnd = if (show.watched) {
            { WatchedBadge() }
        } else {
            null
        },
    )
}

@Composable
private fun ReleasePoster(
    release: CalendarRelease,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    CalendarPosterCard(
        item = release.item,
        meta = null,
        dimmed = false,
        tag = CalendarRules.tag(release),
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier,
        topEnd = { RatingBadge(release.rating) },
    )
}

/**
 * The shared poster card with calendar badges. Missing or loading artwork falls back to the
 * card's placeholder with the title, as everywhere else in the app.
 */
@Composable
internal fun CalendarPosterCard(
    item: CatalogItem,
    meta: String?,
    dimmed: Boolean,
    tag: CalendarTag?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topEnd: (@Composable () -> Unit)?,
) {
    // The caption sits outside the shared card so titles can wrap to two lines; the card's own
    // caption is a fixed single-line slot.
    Column(Modifier.width(PanelMetrics.PosterWidth)) {
        PosterCard(
            item = item,
            onClick = onClick,
            modifier = modifier,
            onFocused = onFocused,
            width = PanelMetrics.PosterWidth,
            overlay = {
                // Watched: the artwork at ~45% over the black canvas; badges stay at full strength.
                if (dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                if (topEnd != null) Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { topEnd() }
                if (tag != null) {
                    StatusPill(
                        text = tagLabel(tag),
                        dot = tag == CalendarTag.NEW,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }
            },
        )
        Column(Modifier.height(PanelMetrics.CaptionHeight).padding(top = 5.dp)) {
            Text(
                item.title,
                color = CalendarColors.Text,
                style = calendarText(PanelMetrics.TitleSize, medium = true),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(
                    meta,
                    color = CalendarColors.TextSecondary,
                    style = calendarText(PanelMetrics.MetaSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun tagLabel(tag: CalendarTag): String = localizedStringResource(
    when (tag) {
        CalendarTag.PREMIERE -> StringsR.string.calendar_tag_premiere
        CalendarTag.FINALE -> StringsR.string.calendar_tag_finale
        CalendarTag.NEW -> StringsR.string.calendar_tag_new
        CalendarTag.NEW_SERIES -> StringsR.string.calendar_tag_new_series
    },
)

/** Star plus rating. Gold from 8.0, so the star and number carry the meaning, not only the colour. */
@Composable
private fun RatingBadge(rating: Float) {
    val text = remember(rating, Lang.current) { String.format(Lang.locale, "%.1f", rating) }
    val label = localizedStringResource(StringsR.string.calendar_rating_label, text)
    val color = if (rating >= HIGH_RATING) CalendarColors.Release else CalendarColors.Text
    Row(
        Modifier
            .background(AppColors.PillScrim, AppShapes.Pill)
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Star, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = color, style = calendarText(14.sp, medium = true), maxLines = 1)
    }
}

private const val HIGH_RATING = 8f

@Composable
private fun WatchedBadge() {
    val label = localizedStringResource(StringsR.string.calendar_legend_watched)
    Box(
        Modifier
            .size(28.dp)
            .background(AppColors.PillScrim, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Check, contentDescription = null, tint = CalendarColors.Watched, modifier = Modifier.size(18.dp))
    }
}
