package com.shieldstream.tv.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldstream.tv.domain.CalendarDay
import com.shieldstream.tv.domain.CalendarFilters
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.MonthGrid
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedPluralResource
import com.shieldstream.tv.strings.localizedResources
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.requestFocusWithRetry
import com.shieldstream.tv.ui.theme.CalendarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val CHIP_COUNT = 4
private const val PANEL_DEBOUNCE_MS = 150L

/**
 * Calendar of the user's show episodes and the best-rated releases, always shown together. The
 * only filter is the minimum rating, which applies to releases. Operated with the D-pad only; see
 * [CalendarFocusNavigator] for the rules. Back from the month bar, the chips or the grid is not
 * consumed, so the caller's Back handling leaves the screen.
 */
@Composable
fun CalendarScreen(
    onOpenEpisode: (CalendarEpisode) -> Unit = {},
    onOpenRelease: (CalendarRelease) -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    // The root reads only the month and today, so moving between days of a month does not
    // recompose it; the selected date is read by the grid's cells and by key handling.
    val selectionState = viewModel.selection.collectAsStateWithLifecycle()
    val visibleMonth by remember { derivedStateOf { selectionState.value.visibleMonth } }
    val today by remember { derivedStateOf { selectionState.value.today } }
    val selectedDate = remember { { selectionState.value.selectedDate } }
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    // Loading runs only while the calendar is on screen: leaving the tab, opening a details page
    // or pausing the app cancels it; cached results are kept and the next visit resumes.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }

    // The grid follows the selection at once, so a month change shows its structure immediately;
    // dots and posters follow from [content] as the data arrives.
    val grid = remember(visibleMonth, viewModel.weekStart) { MonthGrid(visibleMonth, viewModel.weekStart) }
    val contentCurrent = content.month == visibleMonth

    // The panel follows the selected day after a short pause, so held arrows do not recompose
    // posters for every day they pass.
    var panelDate by remember { mutableStateOf(selectedDate()) }
    LaunchedEffect(Unit) {
        viewModel.selection.map { it.selectedDate }.distinctUntilChanged().collectLatest { date ->
            if (date != panelDate) {
                delay(PANEL_DEBOUNCE_MS)
                panelDate = date
            }
        }
    }

    val focus = remember { CalendarFocusHolder(onFocused = { viewModel.lastFocus = it }) }
    val scope = rememberCoroutineScope()
    fun dayFor(date: CalendarDate): CalendarDay = if (contentCurrent) content.day(date) else CalendarDay.EMPTY
    val panelSections = remember(panelDate, content, contentCurrent) { panelSections(dayFor(panelDate)) }
    // A new day starts its rows at the first poster.
    val rows = remember(panelDate) { List(CalendarSection.entries.size) { LazyListState() } }
    SideEffect { focus.rows = rows }

    fun layout(): CalendarFocusLayout = CalendarFocusLayout(
        chipCount = CHIP_COUNT,
        selectedChip = filters.minRating.ordinal,
        grid = grid,
        selectedDate = selectedDate(),
        // Each section is a single row. Counts only: no tile models per key press.
        sections = dayFor(selectedDate()).let { day -> listOf(day.shows.size, day.releases.size) }
            .map { PanelSectionLayout(it, columns = maxOf(it, 1)) },
    )

    // Opening the calendar (from the rail or when the app restores this tab) moves focus to the
    // selected day, otherwise the rail stays expanded over the screen. Coming back from a details
    // page returns to the poster that opened it.
    LaunchedEffect(Unit) {
        val poster = viewModel.lastFocus as? CalendarFocusTarget.Poster
        val target = poster?.let { focus.navigator.clamp(it, layout()) }
        if (target is CalendarFocusTarget.Poster) {
            focus.focused(target)
            focus.focusPoster(target)
        } else {
            val index = grid.indexOf(viewModel.selection.value.selectedDate)
            if (index >= 0) focus.days[index].requestFocusWithRetry()
        }
    }

    var pendingDay by remember { mutableStateOf<CalendarDate?>(null) }
    LaunchedEffect(grid, pendingDay) {
        val date = pendingDay ?: return@LaunchedEffect
        val index = grid.indexOf(date)
        if (date in grid.month && index >= 0) {
            focus.days[index].requestFocusWithRetry()
            pendingDay = null
        }
    }

    fun focusTarget(target: CalendarFocusTarget) {
        focus.current = target
        when (target) {
            is CalendarFocusTarget.Chip -> runCatching { focus.chips[target.index].requestFocus() }
            is CalendarFocusTarget.MonthBar -> runCatching { focus.monthBar[target.control.ordinal].requestFocus() }
            is CalendarFocusTarget.Day -> {
                viewModel.select(target.date)
                if (target.date in grid.month) {
                    runCatching { focus.days[grid.indexOf(target.date)].requestFocus() }
                } else {
                    // Another month: focus its cell once that month's grid is composed.
                    pendingDay = target.date
                }
            }
            is CalendarFocusTarget.Poster -> when {
                // The panel may still show the previous day; show the selected one first.
                panelDate != selectedDate() -> {
                    panelDate = selectedDate()
                    scope.launch { focus.focusPoster(target) }
                }
                // Moves within the panel focus at once, so held keys step one poster per press.
                focus.isComposed(target) -> runCatching { focus.poster(target.section, target.index).requestFocus() }
                else -> scope.launch { focus.focusPoster(target) }
            }
        }
    }

    val navigate by rememberUpdatedState { key: CalendarKey ->
        val current = focus.current
        if (current == null) {
            false
        } else {
            when (val result = focus.navigator.move(current, key, layout())) {
                is CalendarFocusResult.Move -> {
                    focusTarget(result.target)
                    true
                }
                CalendarFocusResult.Stay -> true
                CalendarFocusResult.Exit -> false
            }
        }
    }

    // Keep focus valid when the focused poster disappears (a stricter rating, a changed day).
    LaunchedEffect(panelSections.map { it.tiles.size }) {
        val current = focus.current as? CalendarFocusTarget.Poster ?: return@LaunchedEffect
        val clamped = focus.navigator.clamp(current, layout())
        if (clamped != current) focusTarget(clamped)
    }

    val names = remember(Lang.current) { CalendarNames(Lang.locale) }
    // Raw templates: they are formatted below with localized month names.
    // getString(id) does not format; localizedStringResource(id) would, and throw on the placeholders.
    val resources = localizedResources()
    val monthFormat = remember(resources) { resources.getString(StringsR.string.calendar_month_title) }
    val dayLabelFormat = remember(resources) { resources.getString(StringsR.string.calendar_day_label) }
    // Spoken labels, built once per month and data change rather than per cell on each key press.
    val labels = remember(grid, content, contentCurrent, names, resources) {
        grid.dates.map { date ->
            val count = if (contentCurrent && date in grid.month) content.day(date).size else 0
            dayLabelFormat.format(date.day, names.month(date.month), resources.getQuantityString(StringsR.plurals.calendar_items, count, count))
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(CalendarColors.Canvas)
            .onPreviewKeyEvent { event -> handleKey(event, focus, navigate) }
            .focusProperties {
                // Entering from the navigation rail lands on the selected day.
                onEnter = {
                    val index = grid.indexOf(selectedDate())
                    if (index >= 0) focus.days[index].requestFocus()
                }
            }
            .focusGroup()
            // No end padding: the panel's rows run to the screen edge, where the next poster peeks.
            .padding(start = 28.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            MonthBar(
                title = remember(names, monthFormat, visibleMonth) { names.monthTitle(monthFormat, visibleMonth) },
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::goToToday,
                requesters = focus.monthBar,
                onFocused = { focus.focused(CalendarFocusTarget.MonthBar(it)) },
            )
            Spacer(Modifier.height(10.dp))
            WeekdayLabels(remember(names, grid.weekStart) { grid.weekdays.map(names::shortWeekday) })
            Spacer(Modifier.height(6.dp))
            DayGrid(
                grid = grid,
                today = today,
                selected = selectedDate,
                dots = if (contentCurrent) content.dots else emptyMap(),
                labels = labels,
                requesters = focus.days,
                onFocused = { date ->
                    focus.focused(CalendarFocusTarget.Day(date))
                    // Focus is selection in the grid. While a month change is pending, the old
                    // cell can still hold focus for a frame; it must not select its new date.
                    if (pendingDay == null) viewModel.select(date)
                },
                onClick = { navigate(CalendarKey.OK) },
            )
            Legend()
            Spacer(Modifier.height(12.dp))
            // The rating filter applies to releases only, never to the user's episodes.
            RatingChips(
                selected = filters.minRating,
                onSelect = viewModel::setMinRating,
                requesters = focus.chips,
                onFocused = { focus.focused(CalendarFocusTarget.Chip(it)) },
            )
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            DayPanel(
                today = today,
                sections = panelSections,
                episodesLoading = !contentCurrent || content.episodesLoading,
                releasesLoading = !contentCurrent || content.releasesLoading,
                rows = rows,
                focus = focus,
                onOpenEpisode = onOpenEpisode,
                onOpenRelease = onOpenRelease,
            )
        }
    }
}

/** Arrow keys and Back in the panel are routed through the navigator; everything else passes. */
private fun handleKey(event: KeyEvent, focus: CalendarFocusHolder, navigate: (CalendarKey) -> Boolean): Boolean {
    val key = when (event.key) {
        Key.DirectionLeft -> CalendarKey.LEFT
        Key.DirectionRight -> CalendarKey.RIGHT
        Key.DirectionUp -> CalendarKey.UP
        Key.DirectionDown -> CalendarKey.DOWN
        Key.Back, Key.Escape -> CalendarKey.BACK
        else -> return false
    }
    if (key == CalendarKey.BACK) {
        // Only the panel handles Back (back to the grid); elsewhere it leaves the screen.
        if (focus.current !is CalendarFocusTarget.Poster) return false
        if (event.type == KeyEventType.KeyUp) navigate(key)
        return true
    }
    return if (event.type == KeyEventType.KeyDown) navigate(key) else true
}

/** Focus requesters for every focusable element and the last focused target. Not snapshot state. */
internal class CalendarFocusHolder(private val onFocused: (CalendarFocusTarget) -> Unit = {}) {
    val navigator = CalendarFocusNavigator()
    var current: CalendarFocusTarget? = null
    val chips = List(CHIP_COUNT) { FocusRequester() }
    val monthBar = List(MonthControl.entries.size) { FocusRequester() }
    val days = List(MonthGrid.MAX_ROWS * 7) { FocusRequester() }

    /** The panel's row states, replaced for each day. */
    var rows: List<LazyListState> = emptyList()

    // Rows have no fixed length; requesters are created as indexes are first used and reused after.
    private val posters = List(CalendarSection.entries.size) { mutableListOf<FocusRequester>() }

    fun poster(section: Int, index: Int): FocusRequester {
        val list = posters[section]
        while (list.size <= index) list += FocusRequester()
        return list[index]
    }

    /**
     * Focuses a poster, scrolling its row first when the poster is not composed. A composed poster
     * (visible, or the one peeking at the edge) brings itself into view when focused.
     */
    /** Whether the poster's row has it laid out, so it can take focus now. */
    fun isComposed(target: CalendarFocusTarget.Poster): Boolean =
        rows.getOrNull(target.section)?.layoutInfo?.visibleItemsInfo?.any { it.index == target.index } == true

    suspend fun focusPoster(target: CalendarFocusTarget.Poster) {
        withFrameNanos { }
        val state = rows.getOrNull(target.section)
        if (state != null && state.layoutInfo.visibleItemsInfo.none { it.index == target.index }) {
            state.scrollToItem(target.index)
        }
        poster(target.section, target.index).requestFocusWithRetry()
    }

    fun focused(target: CalendarFocusTarget) {
        current = target
        navigator.onFocused(target)
        onFocused(target)
    }
}

internal data class PanelSectionModel(
    val section: CalendarSection,
    val tiles: List<PanelTile>,
    val total: Int,
)

/** Your episodes, then the best releases. */
internal fun panelSections(day: CalendarDay): List<PanelSectionModel> = listOf(
    PanelSectionModel(CalendarSection.EPISODES, day.shows.map { PanelTile.ShowTile(it) }, day.episodes.size),
    PanelSectionModel(CalendarSection.RELEASES, day.releases.map { PanelTile.ReleaseTile(it) }, day.releases.size),
)

@Composable
private fun DayPanel(
    sections: List<PanelSectionModel>,
    episodesLoading: Boolean,
    releasesLoading: Boolean,
    today: CalendarDate,
    rows: List<LazyListState>,
    focus: CalendarFocusHolder,
    onOpenEpisode: (CalendarEpisode) -> Unit,
    onOpenRelease: (CalendarRelease) -> Unit,
) {
    if (sections.all { it.total == 0 }) {
        // Keep the space blank until the day's data has arrived; then centre the message so the
        // empty panel reads as intentional.
        if (!episodesLoading && !releasesLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PanelMessage(localizedStringResource(StringsR.string.calendar_empty_all))
            }
        }
        return
    }
    // Only sections with something to show appear; an empty or still-loading one takes no space.
    // Section indexes stay fixed (0 = your shows, 1 = releases) for the focus requesters.
    var first = true
    sections.forEachIndexed { index, section ->
        if (section.tiles.isEmpty()) return@forEachIndexed
        if (!first) Spacer(Modifier.height(6.dp))
        first = false
        val isEpisodes = section.section == CalendarSection.EPISODES
        SectionLabel(
            title = localizedStringResource(if (isEpisodes) StringsR.string.calendar_section_shows else StringsR.string.calendar_section_releases),
            count = localizedPluralResource(
                if (isEpisodes) StringsR.plurals.calendar_episodes else StringsR.plurals.calendar_releases,
                section.total,
                section.total,
            ),
        )
        // The row's top inset (room for the focus scale) is the gap below the label.
        PosterRow(
            tiles = section.tiles,
            today = today,
            state = rows[index],
            requester = { focus.poster(index, it) },
            onFocused = { focus.focused(CalendarFocusTarget.Poster(index, it)) },
            onOpenEpisode = onOpenEpisode,
            onOpenRelease = onOpenRelease,
        )
    }
}
