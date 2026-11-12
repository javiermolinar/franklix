package com.shieldstream.tv.home.calendar

import com.shieldstream.tv.home.calendar.CalendarFocusTarget.Day
import com.shieldstream.tv.home.calendar.CalendarFocusTarget.Chip
import com.shieldstream.tv.home.calendar.CalendarFocusTarget.MonthBar
import com.shieldstream.tv.home.calendar.CalendarFocusTarget.Poster
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.CalendarMonth
import com.shieldstream.tv.model.MonthGrid
import com.shieldstream.tv.model.WeekStart
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarFocusNavigatorTest {
    private val navigator = CalendarFocusNavigator()
    private val november = MonthGrid(CalendarMonth(2026, 11), WeekStart.MONDAY)
    private val selected = CalendarDate(2026, 11, 12)

    /** Four shows and two releases on the selected day, each section one row; 8+ selected. */
    private fun layout(
        sections: List<PanelSectionLayout> = listOf(PanelSectionLayout(4, 4), PanelSectionLayout(2, 2)),
        grid: MonthGrid = november,
        date: CalendarDate = selected,
    ) = CalendarFocusLayout(
        chipCount = 4,
        selectedChip = 2,
        grid = grid,
        selectedDate = date,
        sections = sections,
    )

    private fun move(from: CalendarFocusTarget, key: CalendarKey, layout: CalendarFocusLayout = layout()) =
        navigator.move(from, key, layout)

    private fun to(target: CalendarFocusTarget) = CalendarFocusResult.Move(target)

    private fun date(month: Int, day: Int) = CalendarDate(2026, month, day)

    @Test
    fun `month bar and chips move in order`() {
        assertEquals(to(MonthBar(MonthControl.NEXT)), move(MonthBar(MonthControl.PREVIOUS), CalendarKey.RIGHT))
        assertEquals(CalendarFocusResult.Stay, move(MonthBar(MonthControl.PREVIOUS), CalendarKey.LEFT))
        assertEquals(CalendarFocusResult.Stay, move(MonthBar(MonthControl.NEXT), CalendarKey.UP))
        assertEquals(to(Chip(0)), move(Chip(1), CalendarKey.LEFT))
        assertEquals(CalendarFocusResult.Stay, move(Chip(0), CalendarKey.LEFT))
        assertEquals(to(Chip(3)), move(Chip(2), CalendarKey.RIGHT))
        assertEquals(CalendarFocusResult.Stay, move(Chip(1), CalendarKey.DOWN))
    }

    @Test
    fun `Today and the last chip lead right into the posters`() {
        assertEquals(to(Poster(0, 0)), move(MonthBar(MonthControl.TODAY), CalendarKey.RIGHT))
        assertEquals(to(Poster(0, 0)), move(Chip(3), CalendarKey.RIGHT))
        val empty = layout(sections = listOf(PanelSectionLayout(0, 4), PanelSectionLayout(0, 4)))
        assertEquals(CalendarFocusResult.Stay, move(MonthBar(MonthControl.TODAY), CalendarKey.RIGHT, empty))
        assertEquals(CalendarFocusResult.Stay, move(Chip(3), CalendarKey.RIGHT, empty))
    }

    @Test
    fun `the last week goes down to the chips and the chips go up to the selected day`() {
        assertEquals(to(Chip(2)), move(Day(date(11, 30)), CalendarKey.DOWN))
        // 26 November is in the fifth row; the day below it is December's, so Down leaves the grid.
        assertEquals(to(Chip(2)), move(Day(date(11, 26)), CalendarKey.DOWN))
        navigator.onFocused(Chip(0))
        assertEquals(to(Chip(0)), move(Day(date(11, 30)), CalendarKey.DOWN))
        assertEquals(to(Day(selected)), move(Chip(0), CalendarKey.UP))
    }

    @Test
    fun `month bar goes down to the selected day`() {
        assertEquals(to(Day(selected)), move(MonthBar(MonthControl.NEXT), CalendarKey.DOWN))
    }

    @Test
    fun `days move by one day and one week`() {
        assertEquals(to(Day(date(11, 13))), move(Day(selected), CalendarKey.RIGHT))
        assertEquals(to(Day(date(11, 11))), move(Day(selected), CalendarKey.LEFT))
        assertEquals(to(Day(date(11, 5))), move(Day(selected), CalendarKey.UP))
        assertEquals(to(Day(date(11, 19))), move(Day(selected), CalendarKey.DOWN))
    }

    @Test
    fun `left edge stays and right edge enters the first poster`() {
        assertEquals(CalendarFocusResult.Stay, move(Day(date(11, 9)), CalendarKey.LEFT))
        assertEquals(to(Poster(0, 0)), move(Day(date(11, 15)), CalendarKey.RIGHT))
    }

    @Test
    fun `right edge stays when the day is empty`() {
        val empty = layout(sections = listOf(PanelSectionLayout(0, 4), PanelSectionLayout(0, 4)))
        assertEquals(CalendarFocusResult.Stay, move(Day(date(11, 15)), CalendarKey.RIGHT, empty))
        assertEquals(CalendarFocusResult.Stay, move(Day(date(11, 15)), CalendarKey.OK, empty))
    }

    @Test
    fun `right edge skips an empty first section`() {
        val onlyReleases = layout(sections = listOf(PanelSectionLayout(0, 4), PanelSectionLayout(2, 4)))
        assertEquals(to(Poster(1, 0)), move(Day(date(11, 15)), CalendarKey.RIGHT, onlyReleases))
    }

    @Test
    fun `the first week of the month goes up to the month bar`() {
        // November's second row starts on the 2nd; the days above it are October's.
        assertEquals(to(MonthBar(MonthControl.PREVIOUS)), move(Day(date(11, 2)), CalendarKey.UP))
        assertEquals(to(MonthBar(MonthControl.NEXT)), move(Day(date(11, 5)), CalendarKey.UP))
        assertEquals(to(MonthBar(MonthControl.TODAY)), move(Day(date(11, 1)), CalendarKey.UP))
    }

    @Test
    fun `only Left from the 1st and Right from the last day change month`() {
        // 1 November is a Sunday; Left reaches 31 October, which belongs to October.
        assertEquals(to(Day(date(10, 31))), move(Day(date(11, 1)), CalendarKey.LEFT))
        assertEquals(to(Day(date(12, 1))), move(Day(date(11, 30)), CalendarKey.RIGHT))
        assertEquals(to(MonthBar(MonthControl.PREVIOUS)), move(Day(date(11, 3)), CalendarKey.UP))
        assertEquals(to(Chip(2)), move(Day(date(11, 27)), CalendarKey.DOWN))
    }

    @Test
    fun `after crossing a month, moves use the new month's grid`() {
        val october = MonthGrid(CalendarMonth(2026, 10), WeekStart.MONDAY)
        assertEquals(to(Day(date(10, 19))), move(Day(date(10, 26)), CalendarKey.UP, layout(grid = october, date = date(10, 26))))
        // Still November's grid for a frame: 31 October moves within October.
        assertEquals(to(Day(date(10, 24))), move(Day(date(10, 31)), CalendarKey.UP))
        assertEquals(to(Day(date(10, 30))), move(Day(date(10, 31)), CalendarKey.LEFT))
    }

    @Test
    fun `a day outside the visible grid returns to the selected day`() {
        assertEquals(to(Day(selected)), move(Day(date(8, 1)), CalendarKey.RIGHT))
    }

    @Test
    fun `OK on a day opens the first poster`() {
        assertEquals(to(Poster(0, 0)), move(Day(selected), CalendarKey.OK))
    }

    @Test
    fun `posters move within rows and the first column returns to the selected day`() {
        assertEquals(to(Poster(0, 1)), move(Poster(0, 0), CalendarKey.RIGHT))
        assertEquals(CalendarFocusResult.Stay, move(Poster(0, 3), CalendarKey.RIGHT))
        assertEquals(CalendarFocusResult.Stay, move(Poster(1, 1), CalendarKey.RIGHT))
        assertEquals(to(Poster(0, 2)), move(Poster(0, 3), CalendarKey.LEFT))
        assertEquals(to(Day(selected)), move(Poster(0, 0), CalendarKey.LEFT))
        assertEquals(to(Day(selected)), move(Poster(1, 0), CalendarKey.LEFT))
    }

    @Test
    fun `posters move between sections keeping the column where possible`() {
        assertEquals(to(Poster(1, 1)), move(Poster(0, 3), CalendarKey.DOWN))
        assertEquals(to(Poster(0, 1)), move(Poster(1, 1), CalendarKey.UP))
        assertEquals(CalendarFocusResult.Stay, move(Poster(1, 0), CalendarKey.DOWN))
    }

    @Test
    fun `posters move between rows of a single section`() {
        val single = layout(sections = listOf(PanelSectionLayout(8, 5)))
        assertEquals(to(Poster(0, 7)), move(Poster(0, 4), CalendarKey.DOWN, single))
        assertEquals(to(Poster(0, 6)), move(Poster(0, 1), CalendarKey.DOWN, single))
        assertEquals(to(Poster(0, 1)), move(Poster(0, 6), CalendarKey.UP, single))
        assertEquals(to(Day(selected)), move(Poster(0, 5), CalendarKey.LEFT, single))
    }

    @Test
    fun `the first poster row has nothing above it`() {
        assertEquals(CalendarFocusResult.Stay, move(Poster(0, 3), CalendarKey.UP))
        val onlyReleases = layout(sections = listOf(PanelSectionLayout(0, 4), PanelSectionLayout(2, 4)))
        assertEquals(CalendarFocusResult.Stay, move(Poster(1, 0), CalendarKey.UP, onlyReleases))
    }

    @Test
    fun `Back leaves the panel for the selected day and exits elsewhere`() {
        assertEquals(to(Day(selected)), move(Poster(1, 1), CalendarKey.BACK))
        assertEquals(CalendarFocusResult.Exit, move(Day(selected), CalendarKey.BACK))
        assertEquals(CalendarFocusResult.Exit, move(MonthBar(MonthControl.TODAY), CalendarKey.BACK))
        assertEquals(CalendarFocusResult.Exit, move(Chip(0), CalendarKey.BACK))
    }

    @Test
    fun `clamp keeps focus valid when the panel shrinks or empties`() {
        val shrunk = layout(sections = listOf(PanelSectionLayout(3, 5)))
        assertEquals(Poster(0, 2), navigator.clamp(Poster(0, 7), shrunk))
        val moved = layout(sections = listOf(PanelSectionLayout(0, 4), PanelSectionLayout(2, 4)))
        assertEquals(Poster(1, 0), navigator.clamp(Poster(0, 3), moved))
        val empty = layout(sections = listOf(PanelSectionLayout(0, 5)))
        assertEquals(Day(selected), navigator.clamp(Poster(0, 3), empty))
        assertEquals(to(Day(selected)), move(Poster(0, 3), CalendarKey.RIGHT, empty))
    }

    @Test
    fun `a long row moves through every poster and keeps the column between rows`() {
        val rows = layout(listOf(PanelSectionLayout(9, 9), PanelSectionLayout(3, 3)))
        assertEquals(to(Poster(0, 8)), move(Poster(0, 7), CalendarKey.RIGHT, rows))
        assertEquals(CalendarFocusResult.Stay, move(Poster(0, 8), CalendarKey.RIGHT, rows))
        assertEquals(to(Poster(1, 2)), move(Poster(0, 6), CalendarKey.DOWN, rows))
        assertEquals(to(Poster(0, 1)), move(Poster(1, 1), CalendarKey.UP, rows))
    }
}
