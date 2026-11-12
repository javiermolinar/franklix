package com.shieldstream.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDateTest {

    @Test
    fun `epoch days round-trip across leap years and centuries`() {
        listOf(
            CalendarDate(1970, 1, 1) to 0L,
            CalendarDate(2000, 2, 29) to 11016L,
            CalendarDate(2026, 11, 12) to 20769L,
            CalendarDate(1969, 12, 31) to -1L,
        ).forEach { (date, epochDay) ->
            assertEquals(date.toString(), epochDay, date.epochDay)
            assertEquals(date, CalendarDate.fromEpochDay(epochDay))
        }
        var date = CalendarDate(2023, 12, 25)
        repeat(800) {
            val next = date.plusDays(1)
            assertEquals(date.epochDay + 1, next.epochDay)
            date = next
        }
    }

    @Test
    fun `day of week is ISO`() {
        assertEquals(4, CalendarDate(2026, 11, 12).dayOfWeek) // Thursday
        assertEquals(7, CalendarDate(2026, 11, 1).dayOfWeek) // Sunday
        assertEquals(1, CalendarDate(2024, 1, 1).dayOfWeek) // Monday
    }

    @Test
    fun `parse accepts only full valid dates`() {
        assertEquals(CalendarDate(2026, 2, 28), CalendarDate.parse("2026-02-28"))
        assertNull(CalendarDate.parse("2026-02-29"))
        assertNull(CalendarDate.parse("2026-13-01"))
        assertNull(CalendarDate.parse("2026-11"))
        assertNull(CalendarDate.parse(""))
        assertNull(CalendarDate.parse(null))
        assertEquals("2026-03-05", CalendarDate(2026, 3, 5).iso())
    }

    @Test
    fun `months wrap across years`() {
        assertEquals(CalendarMonth(2027, 1), CalendarMonth(2026, 12).plusMonths(1))
        assertEquals(CalendarMonth(2025, 12), CalendarMonth(2026, 1).plusMonths(-1))
        assertEquals(29, CalendarMonth(2024, 2).length)
        assertEquals(28, CalendarMonth(2100, 2).length)
    }

    @Test
    fun `Monday-first November 2026 has six rows starting 26 October`() {
        val grid = MonthGrid(CalendarMonth(2026, 11), WeekStart.MONDAY)
        assertEquals(6, grid.rows)
        assertEquals(CalendarDate(2026, 10, 26), grid.start)
        assertEquals(CalendarDate(2026, 12, 6), grid.end)
        assertEquals(6, grid.columnOf(CalendarDate(2026, 11, 1)))
        assertEquals(3, grid.columnOf(CalendarDate(2026, 11, 12)))
        assertEquals(2, grid.rowOf(CalendarDate(2026, 11, 12)))
        assertEquals(-1, grid.indexOf(CalendarDate(2026, 12, 7)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), grid.weekdays)
    }

    @Test
    fun `Sunday-first moves the first column`() {
        val grid = MonthGrid(CalendarMonth(2026, 11), WeekStart.SUNDAY)
        assertEquals(CalendarDate(2026, 11, 1), grid.start)
        assertEquals(5, grid.rows)
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), grid.weekdays)
    }

    @Test
    fun `grids hold whole weeks and the whole month`() {
        val rowCounts = mutableSetOf<Int>()
        for (offset in 0 until 48) {
            val month = CalendarMonth(2025, 1).plusMonths(offset)
            WeekStart.entries.forEach { start ->
                val grid = MonthGrid(month, start)
                assertTrue(month.first in grid)
                assertTrue(month.last in grid)
                assertEquals(0, grid.columnOf(grid.start))
                assertEquals(grid.rows * 7, grid.dates.size)
                assertFalse(grid.rows > MonthGrid.MAX_ROWS)
                rowCounts += grid.rows
            }
        }
        assertTrue(5 in rowCounts && 6 in rowCounts)
    }
}
