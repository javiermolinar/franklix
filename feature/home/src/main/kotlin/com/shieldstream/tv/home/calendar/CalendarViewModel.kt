package com.shieldstream.tv.home.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CalendarDay
import com.shieldstream.tv.domain.CalendarDot
import com.shieldstream.tv.domain.CalendarFilters
import com.shieldstream.tv.domain.CalendarRepository
import com.shieldstream.tv.domain.CalendarRules
import com.shieldstream.tv.domain.EpisodeTrackingRepository
import com.shieldstream.tv.domain.MinRating
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.domain.NewEpisodes
import com.shieldstream.tv.domain.ShowCalendarEpisodes
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.domain.batched
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarMonth
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.HomeFilter
import com.shieldstream.tv.model.WeekStart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Everything the screen draws for the visible month under the current filters. */
data class CalendarContent(
    val month: CalendarMonth,
    val days: Map<CalendarDate, CalendarDay>,
    val dots: Map<CalendarDate, List<CalendarDot>>,
    val episodesLoading: Boolean,
    val releasesLoading: Boolean,
) {
    fun day(date: CalendarDate): CalendarDay = days[date] ?: CalendarDay.EMPTY
}

data class CalendarSelection(
    val today: CalendarDate,
    val visibleMonth: CalendarMonth,
    val selectedDate: CalendarDate,
)

/**
 * The month each kind of data finished loading for. A month counts as loading until its own load
 * completes, so the debounce after a month change never shows the previous month's "done" state.
 */
private data class LoadState(val episodesDone: CalendarMonth? = null, val releasesDone: CalendarMonth? = null)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository,
    watchProgress: WatchProgressRepository,
    myList: MyListRepository,
    tracking: EpisodeTrackingRepository,
) : ViewModel() {

    /** Follows the device locale; Monday unless the locale starts weeks on Sunday. */
    val weekStart: WeekStart =
        if (java.util.Calendar.getInstance(Locale.getDefault()).firstDayOfWeek == java.util.Calendar.SUNDAY) WeekStart.SUNDAY else WeekStart.MONDAY

    private val _selection = CalendarDate.today().let { MutableStateFlow(CalendarSelection(it, it.calendarMonth, it)) }
    val selection: StateFlow<CalendarSelection> = _selection.asStateFlow()

    /**
     * The last focused element. The screen's composition is disposed while a details page is open;
     * the view model outlives it, so returning can restore focus on the poster that was opened.
     */
    internal var lastFocus: CalendarFocusTarget? = null

    private val _filters = MutableStateFlow(CalendarFilters())
    val filters: StateFlow<CalendarFilters> = _filters.asStateFlow()

    // Accumulated across months: an episode's air date and a release's date do not depend on which
    // month asked for them, so keeping earlier results lets a month change show cached data at once.
    private val episodesByShow = MutableStateFlow<Map<String, List<CalendarEpisode>>>(emptyMap())
    private val releasesByKey = MutableStateFlow<Map<String, CalendarRelease>>(emptyMap())
    private val loading = MutableStateFlow(LoadState())

    // Same candidate set as the Episodes tab: followed shows, shows with watch history, and shows in My List.
    // TODO(calendar): undecided whether movies saved to My List belong here; series episodes only for now.
    // A set, not a list: playback moves the current show to the front of the candidates every few
    // seconds, and a reordering alone must not reload anything. Computed off the main thread.
    private val showIds = combine(watchProgress.progress, myList.entries, tracking.followedShowIds) { progress, list, followed ->
        NewEpisodes.candidateIds(progress, list, HomeFilter.ALL, followed, maxItems = MAX_SHOWS).toSet()
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Playback writes progress every few seconds; only the watched set matters here.
    private val watchedKeys = watchProgress.progress
        .map { progress -> progress.filterValues { it.isWatched }.keys }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Whether the screen is shown and resumed. Nothing loads while it is not. */
    private val visible = MutableStateFlow(false)

    /**
     * When each show's episodes were loaded, per month. A month change fetches only months not yet
     * loaded (one new month instead of three); entries expire with the catalog's six-hour caches.
     */
    private val loadedAt = ConcurrentHashMap<Pair<String, CalendarMonth>, Long>()

    init {
        viewModelScope.launch {
            visible.collectLatest { shown ->
                if (!shown) return@collectLatest
                // Fast key repeats on the month buttons or across month edges settle before loading.
                fun month() = _selection.map { it.visibleMonth to it.today }.distinctUntilChanged().settled()
                coroutineScope {
                    launch {
                        combine(month(), showIds) { (visible, _), ids -> visible to ids }
                            .distinctUntilChanged()
                            .collectLatest { (visible, ids) -> loadEpisodes(visible, ids) }
                    }
                    launch {
                        month().collectLatest { (visible, today) -> loadReleases(visible, today) }
                    }
                }
            }
        }
    }

    /** Call when the screen is resumed and when it is paused or left. */
    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    // Depends on the month and today, not the selected day: moving within a month rebuilds nothing.
    val content: StateFlow<CalendarContent> = combine(
        combine(_selection.map { it.visibleMonth to it.today }.distinctUntilChanged(), _filters) { month, filters -> month to filters },
        episodesByShow,
        releasesByKey,
        watchedKeys,
        loading,
    ) { (month, filters), episodes, releases, watched, load ->
        build(month.first, month.second, filters, episodes, releases, watched, load)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            build(_selection.value.visibleMonth, _selection.value.today, _filters.value, emptyMap(), emptyMap(), emptySet(), LoadState()),
        )

    // Off the main thread. Shows arrive one by one; batching them means one content build per
    // window instead of one per show (up to 50 on a cold open), and shows that add nothing (no
    // episodes, or the ones a previous month already loaded) trigger no build at all.
    private suspend fun loadEpisodes(month: CalendarMonth, ids: Set<String>) = withContext(Dispatchers.Default) {
        // A removed show's episodes go, and so does its record, so re-adding it loads it again.
        episodesByShow.update { current -> current.filterKeys { it in ids } }
        loadedAt.keys.removeAll { it.first !in ids }
        val now = System.currentTimeMillis()
        val missing = CalendarRules.missingMonths(ids, CalendarRules.episodeMonths(month)) { id, loaded ->
            loadedAt[id to loaded]?.let { now - it < LOADED_TTL_MS } == true
        }
        if (missing.isEmpty()) {
            loading.update { it.copy(episodesDone = month) }
            return@withContext
        }
        loading.update { it.copy(episodesDone = null) }
        missing.forEach { (group, groupIds) ->
            repository.showEpisodes(groupIds, group.first().first, group.last().last)
                // Failed or timed-out shows send nothing and are retried on the next load.
                .onEach { result -> group.forEach { loadedAt[result.showId to it] = now } }
                .filter { it.episodes.isNotEmpty() }
                .batched(BATCH_MS)
                .collect { batch -> episodesByShow.update { current -> merge(current, batch) } }
        }
        loading.update { it.copy(episodesDone = month) }
    }

    /** [current] with [batch] merged in, or [current] itself when nothing changed. */
    private fun merge(
        current: Map<String, List<CalendarEpisode>>,
        batch: List<ShowCalendarEpisodes>,
    ): Map<String, List<CalendarEpisode>> {
        var result = current
        batch.forEach { show ->
            val existing = result[show.showId].orEmpty()
            val merged = (existing + show.episodes).associateBy { it.key }.values.toList()
            if (merged != existing) result = result + (show.showId to merged)
        }
        return result
    }

    private suspend fun loadReleases(month: CalendarMonth, today: CalendarDate) = withContext(Dispatchers.Default) {
        loading.update { it.copy(releasesDone = null) }
        CalendarRules.releaseWindow(month, today)?.let { (start, end) -> addReleases(repository.releases(start, end)) }
        loading.update { it.copy(releasesDone = month) }
        // Prefetch both neighbours so the next month change is served from memory, but only once
        // this month's episodes are in: the visible month's requests go first.
        loading.first { it.episodesDone == month }
        coroutineScope {
            listOf(month.plusMonths(-1), month.plusMonths(1)).forEach { neighbour ->
                launch {
                    CalendarRules.releaseWindow(neighbour, today)?.let { (start, end) -> addReleases(repository.releases(start, end)) }
                }
            }
        }
    }

    /** The first value passes at once; later ones wait [DEBOUNCE_MS] for key repeats to settle. */
    private fun <T> kotlinx.coroutines.flow.Flow<T>.settled(): kotlinx.coroutines.flow.Flow<T> {
        var first = true
        return debounce {
            if (first) {
                first = false
                0L
            } else {
                DEBOUNCE_MS
            }
        }
    }

    private fun addReleases(releases: List<CalendarRelease>) {
        if (releases.isEmpty()) return
        releasesByKey.update { current -> current + releases.associateBy { it.item.key } }
    }

    /** Refreshes "today" from the clock; call when the screen is shown. */
    fun refreshToday() {
        val today = CalendarDate.today()
        _selection.update { if (it.today == today) it else it.copy(today = today) }
    }

    /** Selects a day; a day of another month makes that month visible. */
    fun select(date: CalendarDate) {
        _selection.update { it.copy(selectedDate = date, visibleMonth = date.calendarMonth) }
    }

    fun previousMonth() = showMonth(_selection.value.visibleMonth.plusMonths(-1))

    fun nextMonth() = showMonth(_selection.value.visibleMonth.plusMonths(1))

    fun goToToday() {
        refreshToday()
        select(_selection.value.today)
    }

    /** Shows [month] and selects today when it is in that month, otherwise the 1st. */
    private fun showMonth(month: CalendarMonth) {
        _selection.update { it.copy(visibleMonth = month, selectedDate = if (it.today in month) it.today else month.first) }
    }

    fun setMinRating(rating: MinRating) = _filters.update { it.copy(minRating = rating) }

    private fun build(
        month: CalendarMonth,
        today: CalendarDate,
        filters: CalendarFilters,
        episodesByShow: Map<String, List<CalendarEpisode>>,
        releases: Map<String, CalendarRelease>,
        watched: Set<String>,
        load: LoadState,
    ): CalendarContent {
        val episodesByDate = HashMap<CalendarDate, MutableList<CalendarEpisode>>()
        episodesByShow.values.forEach { list ->
            list.forEach { episode ->
                if (episode.date in month) {
                    episodesByDate.getOrPut(episode.date) { mutableListOf() } += episode.copy(watched = episode.key in watched)
                }
            }
        }
        val releasesByDate = releases.values.filter { it.date in month }.groupBy { it.date }
        val days = HashMap<CalendarDate, CalendarDay>()
        val dots = HashMap<CalendarDate, List<CalendarDot>>()
        // Adjacent-month cells have no dots and their own month shows their items.
        for (day in 1..month.length) {
            val date = CalendarDate(month.year, month.month, day)
            val result = CalendarRules.day(
                date = date,
                today = today,
                episodes = episodesByDate[date].orEmpty(),
                releases = releasesByDate[date].orEmpty(),
                filters = filters,
            )
            if (!result.isEmpty) {
                days[date] = result
                dots[date] = CalendarRules.dots(result)
            }
        }
        return CalendarContent(month, days, dots, episodesLoading = load.episodesDone != month, releasesLoading = load.releasesDone != month)
    }

    private companion object {
        const val MAX_SHOWS = 50
        const val DEBOUNCE_MS = 150L
        const val BATCH_MS = 200L
        const val LOADED_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
