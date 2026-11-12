package com.shieldstream.tv.domain

import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarEpisode
import com.shieldstream.tv.model.CalendarMonth
import com.shieldstream.tv.model.CalendarRelease
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.Season
import com.shieldstream.tv.model.ShowAiring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRulesTest {
    private val today = CalendarDate(2026, 11, 12)

    private fun item(id: String, title: String, type: MediaType = MediaType.TV) =
        CatalogItem(id, null, type, title, "", null, null, null, null)

    private fun episode(title: String, number: Int = 2, watched: Boolean = false, date: CalendarDate = today, last: Boolean = false) =
        CalendarEpisode(item(title, title), Episode(1, number, "E", "", null, 45, date.iso()), date, last, watched)

    private fun release(title: String, rating: Float, series: Boolean = false) =
        CalendarRelease(item(title, title, if (series) MediaType.TV else MediaType.MOVIE), today, rating, series)

    @Test
    fun `episodes sort by show title and releases by rating then title`() {
        val day = CalendarRules.day(
            today, today,
            listOf(episode("wren"), episode("Brass"), episode("copper")),
            listOf(release("B", 8.1f), release("A", 8.1f), release("C", 9.0f)),
            CalendarFilters(minRating = MinRating.ANY),
        )
        assertEquals(listOf("Brass", "copper", "wren"), day.episodes.map { it.show.title })
        assertEquals(listOf("C", "A", "B"), day.releases.map { it.item.title })
    }

    @Test
    fun `rating filter applies to releases only`() {
        val day = CalendarRules.day(
            today, today,
            listOf(episode("Show")),
            listOf(release("Good", 8.0f), release("Fine", 7.9f)),
            CalendarFilters(minRating = MinRating.EIGHT),
        )
        assertEquals(1, day.episodes.size)
        assertEquals(listOf("Good"), day.releases.map { it.item.title })
    }

    @Test
    fun `watched episodes stay in the day`() {
        val day = CalendarRules.day(today, today, listOf(episode("Seen", watched = true), episode("New")), emptyList(), CalendarFilters())
        assertEquals(listOf("New", "Seen"), day.episodes.map { it.show.title })
    }

    @Test
    fun `future days show upcoming episodes but no releases`() {
        val tomorrow = today.plusDays(1)
        val day = CalendarRules.day(tomorrow, today, listOf(episode("Show", date = tomorrow)), listOf(release("Film", 9f)), CalendarFilters())
        assertEquals(1, day.episodes.size)
        assertTrue(day.releases.isEmpty())
    }

    @Test
    fun `a day with both kinds keeps a slot for releases`() {
        val episodes = List(5) { episode("S$it") }
        val withRelease = CalendarDay(episodes, listOf(release("R", 9f), release("Q", 8f)))
        assertEquals(
            listOf(CalendarDot.EPISODE, CalendarDot.EPISODE, CalendarDot.RELEASE),
            CalendarRules.dots(withRelease),
        )
        val oneEpisode = CalendarDay(listOf(episode("S", watched = true)), List(4) { release("R$it", 9f) })
        assertEquals(
            listOf(CalendarDot.WATCHED, CalendarDot.RELEASE, CalendarDot.RELEASE),
            CalendarRules.dots(oneEpisode),
        )
        assertEquals(List(3) { CalendarDot.EPISODE }, CalendarRules.dots(CalendarDay(episodes)))
        assertEquals(emptyList<CalendarDot>(), CalendarRules.dots(CalendarDay.EMPTY))
    }

    private fun showEpisode(show: String, season: Int, number: Int, watched: Boolean = false, last: Boolean = false) =
        CalendarEpisode(item(show, show), Episode(season, number, "E", "", null, 45, today.iso()), today, last, watched)

    @Test
    fun `episodes of one show on a day become one card`() {
        val day = CalendarRules.day(
            today, today,
            List(8) { showEpisode("Terminal", 2, it + 1) } + showEpisode("Brass", 1, 4),
            emptyList(),
            CalendarFilters(),
        )
        assertEquals(listOf("Brass", "Terminal"), day.shows.map { it.show.title })
        assertEquals(8, day.shows[1].episodes.size)
        assertEquals(2, day.size)
        assertEquals(listOf(CalendarDot.EPISODE, CalendarDot.EPISODE), CalendarRules.dots(day))
    }

    @Test
    fun `a show card is watched only when all its episodes are, and opens the first unwatched`() {
        val partly = CalendarShowEpisodes(item("S", "S"), listOf(showEpisode("S", 1, 1, watched = true), showEpisode("S", 1, 2)))
        assertEquals(false, partly.watched)
        assertEquals(2, partly.target.episode.number)
        val all = CalendarShowEpisodes(item("S", "S"), listOf(showEpisode("S", 1, 1, watched = true), showEpisode("S", 1, 2, watched = true)))
        assertEquals(true, all.watched)
        assertEquals(1, all.target.episode.number)
        assertEquals(listOf(CalendarDot.WATCHED), CalendarRules.dots(CalendarDay(all.episodes)))
    }

    @Test
    fun `consecutive episodes of one season form a range`() {
        fun group(vararg episodes: CalendarEpisode) = CalendarShowEpisodes(item("S", "S"), episodes.toList())
        assertEquals(Triple(2, 1, 8), CalendarRules.episodeRange(group(*Array(8) { showEpisode("S", 2, it + 1) })))
        assertNull(CalendarRules.episodeRange(group(showEpisode("S", 2, 1))))
        assertNull(CalendarRules.episodeRange(group(showEpisode("S", 2, 1), showEpisode("S", 2, 3))))
        assertNull(CalendarRules.episodeRange(group(showEpisode("S", 1, 8), showEpisode("S", 2, 1))))
    }

    @Test
    fun `a show card is a premiere or finale when any of its episodes is`() {
        val drop = CalendarShowEpisodes(item("S", "S"), listOf(showEpisode("S", 2, 1), showEpisode("S", 2, 8, last = true)))
        assertEquals(CalendarTag.PREMIERE, CalendarRules.tag(drop, today))
        val end = CalendarShowEpisodes(item("S", "S"), listOf(showEpisode("S", 2, 7), showEpisode("S", 2, 8, last = true)))
        assertEquals(CalendarTag.FINALE, CalendarRules.tag(end, today))
    }

    @Test
    fun `tags prefer premiere, then finale, then new`() {
        assertEquals(CalendarTag.PREMIERE, CalendarRules.tag(episode("S", number = 1, last = true), today))
        assertEquals(CalendarTag.FINALE, CalendarRules.tag(episode("S", number = 8, last = true), today))
        assertEquals(CalendarTag.NEW, CalendarRules.tag(episode("S", number = 3), today))
        assertNull(CalendarRules.tag(episode("S", number = 3, date = today.plusDays(-1)), today))
        assertEquals(CalendarTag.NEW_SERIES, CalendarRules.tag(release("R", 8f, series = true)))
        assertNull(CalendarRules.tag(release("R", 8f)))
    }

    @Test
    fun `release window stops at today and is empty for future months`() {
        val november = CalendarMonth(2026, 11)
        assertEquals(CalendarDate(2026, 10, 25) to today, CalendarRules.releaseWindow(november, today))
        assertNull(CalendarRules.releaseWindow(CalendarMonth(2027, 1), today))
    }

    @Test
    fun `a month step loads only the newly exposed month, and new shows load everything`() {
        val october = CalendarMonth(2026, 10)
        val november = CalendarMonth(2026, 11)
        val december = CalendarMonth(2026, 12)
        assertEquals(listOf(october, november, december), CalendarRules.episodeMonths(november))

        // Shows a and b were loaded for September to November; c is new.
        val loaded = setOf("a", "b").flatMap { id -> listOf(CalendarMonth(2026, 9), october, november).map { id to it } }.toSet()
        val missing = CalendarRules.missingMonths(listOf("a", "b", "c"), CalendarRules.episodeMonths(november)) { id, month -> (id to month) in loaded }

        assertEquals(mapOf(listOf(december) to listOf("a", "b"), listOf(october, november, december) to listOf("c")), missing)
        assertEquals(emptyMap<List<CalendarMonth>, List<String>>(), CalendarRules.missingMonths(listOf("a"), listOf(october)) { _, _ -> true })
    }

    private fun airing(latest: Episode?, next: Episode?, seasons: List<Season> = emptyList()) =
        ShowAiring(item("1", "Show"), latest, next, seasons)

    private fun ep(season: Int, number: Int, date: String) = Episode(season, number, "", "", null, null, date)

    @Test
    fun `ended shows need no season request`() {
        val start = CalendarDate(2026, 10, 1)
        val end = CalendarDate(2026, 12, 31)
        assertEquals(emptyList<Int>(), CalendarRules.seasonsToLoad(airing(ep(3, 10, "2021-05-01"), null), start, end))
        assertEquals(emptyList<Int>(), CalendarRules.seasonsToLoad(airing(null, ep(1, 1, "2027-06-01")), start, end))
        assertEquals(emptyList<Int>(), CalendarRules.seasonsToLoad(airing(null, null), start, end))
    }

    @Test
    fun `airing and upcoming seasons load`() {
        val start = CalendarDate(2026, 10, 1)
        val end = CalendarDate(2026, 12, 31)
        assertEquals(listOf(2), CalendarRules.seasonsToLoad(airing(ep(2, 4, "2026-11-05"), ep(2, 5, "2026-11-12")), start, end))
        assertEquals(listOf(3), CalendarRules.seasonsToLoad(airing(ep(2, 10, "2025-06-01"), ep(3, 1, "2026-12-01")), start, end))
    }

    @Test
    fun `earlier seasons load when their premieres overlap the window`() {
        val seasons = listOf(Season(1, "1", 10, "2025-09-01"), Season(2, "2", 10, "2026-09-15"), Season(3, "3", 10, "2027-03-01"))
        val start = CalendarDate(2026, 8, 1)
        val end = CalendarDate(2026, 10, 31)
        // Season 3 premiered after the window; seasons 1 and 2 overlap it.
        val result = CalendarRules.seasonsToLoad(airing(ep(3, 2, "2027-03-08"), ep(3, 3, "2027-03-15"), seasons), start, end)
        assertEquals(listOf(2, 1), result)
    }
}
