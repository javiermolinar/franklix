package com.shieldstream.tv.home.calendar

import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.MonthGrid

/**
 * Focus zones of the calendar. The left column holds the month bar, the day grid and, below it,
 * the rating chips; the right column holds the posters. Compose's geometric focus search picks the
 * wrong neighbour across these zones (the grid's right edge, the panel's first column), so the
 * screen routes D-pad keys through [CalendarFocusNavigator] and requests the target explicitly.
 */
sealed interface CalendarFocusTarget {
    /** A rating chip: 0 = Any … 3 = 9+. */
    data class Chip(val index: Int) : CalendarFocusTarget

    data class MonthBar(val control: MonthControl) : CalendarFocusTarget

    /**
     * A day. Only Left from the 1st and Right from the last day reach another month, which then
     * becomes visible; Up and Down stay in the month and lead to the month bar and the chips.
     */
    data class Day(val date: CalendarDate) : CalendarFocusTarget

    /** [index] within [section]'s row. */
    data class Poster(val section: Int, val index: Int) : CalendarFocusTarget
}

enum class MonthControl { PREVIOUS, NEXT, TODAY }

enum class CalendarKey { LEFT, RIGHT, UP, DOWN, OK, BACK }

data class PanelSectionLayout(val itemCount: Int, val columns: Int)

data class CalendarFocusLayout(
    val chipCount: Int,
    val selectedChip: Int,
    val grid: MonthGrid,
    val selectedDate: CalendarDate,
    val sections: List<PanelSectionLayout>,
)

sealed interface CalendarFocusResult {
    data class Move(val target: CalendarFocusTarget) : CalendarFocusResult

    /** Keep focus where it is and consume the key. */
    data object Stay : CalendarFocusResult

    /** Let the key through so the screen is left (Back outside the panel). */
    data object Exit : CalendarFocusResult
}

/**
 * The navigation rules. Remembers the last focused chip and month-bar control so moving between
 * zones returns to a stable place. Has no Compose dependency.
 */
class CalendarFocusNavigator {
    private var lastChip: Int? = null
    private var lastMonthBar: MonthControl? = null

    /** Records the focused element; call whenever focus lands somewhere. */
    fun onFocused(target: CalendarFocusTarget) {
        when (target) {
            is CalendarFocusTarget.Chip -> lastChip = target.index
            is CalendarFocusTarget.MonthBar -> lastMonthBar = target.control
            else -> Unit
        }
    }

    fun move(from: CalendarFocusTarget, key: CalendarKey, layout: CalendarFocusLayout): CalendarFocusResult = when (from) {
        is CalendarFocusTarget.Chip -> fromChip(from, key, layout)
        is CalendarFocusTarget.MonthBar -> fromMonthBar(from, key, layout)
        is CalendarFocusTarget.Day -> fromDay(from, key, layout)
        is CalendarFocusTarget.Poster -> fromPoster(from, key, layout)
    }

    /** The first poster of the first section that has any, or null when the panel is empty. */
    fun firstPoster(layout: CalendarFocusLayout): CalendarFocusTarget.Poster? =
        layout.sections.indexOfFirst { it.itemCount > 0 }.takeIf { it >= 0 }?.let { CalendarFocusTarget.Poster(it, 0) }

    /** A still-valid poster near [target] after the panel changed, or the selected day when it is empty. */
    fun clamp(target: CalendarFocusTarget.Poster, layout: CalendarFocusLayout): CalendarFocusTarget {
        val section = layout.sections.getOrNull(target.section)
        if (section != null && section.itemCount > 0) {
            return target.copy(index = target.index.coerceAtMost(section.itemCount - 1))
        }
        return firstPoster(layout) ?: CalendarFocusTarget.Day(layout.selectedDate)
    }

    private fun chips(layout: CalendarFocusLayout) = CalendarFocusTarget.Chip(lastChip ?: layout.selectedChip)

    private fun fromChip(from: CalendarFocusTarget.Chip, key: CalendarKey, layout: CalendarFocusLayout): CalendarFocusResult =
        when (key) {
            CalendarKey.LEFT -> if (from.index == 0) CalendarFocusResult.Stay else move(CalendarFocusTarget.Chip(from.index - 1))
            // The posters are to the right of the chips.
            CalendarKey.RIGHT -> if (from.index + 1 < layout.chipCount) {
                move(CalendarFocusTarget.Chip(from.index + 1))
            } else {
                firstPoster(layout)?.let(::move) ?: CalendarFocusResult.Stay
            }
            CalendarKey.UP -> move(CalendarFocusTarget.Day(layout.selectedDate))
            CalendarKey.DOWN, CalendarKey.OK -> CalendarFocusResult.Stay
            CalendarKey.BACK -> CalendarFocusResult.Exit
        }

    private fun fromMonthBar(from: CalendarFocusTarget.MonthBar, key: CalendarKey, layout: CalendarFocusLayout): CalendarFocusResult {
        val controls = MonthControl.entries
        return when (key) {
            CalendarKey.LEFT -> if (from.control.ordinal == 0) {
                CalendarFocusResult.Stay
            } else {
                move(CalendarFocusTarget.MonthBar(controls[from.control.ordinal - 1]))
            }
            CalendarKey.RIGHT -> if (from.control == MonthControl.TODAY) {
                firstPoster(layout)?.let(::move) ?: CalendarFocusResult.Stay
            } else {
                move(CalendarFocusTarget.MonthBar(controls[from.control.ordinal + 1]))
            }
            CalendarKey.DOWN -> move(CalendarFocusTarget.Day(layout.selectedDate))
            CalendarKey.UP, CalendarKey.OK -> CalendarFocusResult.Stay
            CalendarKey.BACK -> CalendarFocusResult.Exit
        }
    }

    private fun fromDay(from: CalendarFocusTarget.Day, key: CalendarKey, layout: CalendarFocusLayout): CalendarFocusResult {
        if (layout.grid.indexOf(from.date) < 0) return move(CalendarFocusTarget.Day(layout.selectedDate))
        // Just after Left/Right crossed into another month, the screen may still pass the old
        // grid for a frame; the day's own month is the one becoming visible.
        val month = from.date.calendarMonth
        val grid = if (layout.grid.month == month) layout.grid else MonthGrid(month, layout.grid.weekStart)
        val column = grid.indexOf(from.date) % 7
        return when (key) {
            CalendarKey.LEFT -> if (column == 0) CalendarFocusResult.Stay else move(CalendarFocusTarget.Day(from.date.plusDays(-1)))
            CalendarKey.RIGHT -> if (column == 6) {
                firstPoster(layout)?.let(::move) ?: CalendarFocusResult.Stay
            } else {
                move(CalendarFocusTarget.Day(from.date.plusDays(1)))
            }
            // The month bar is above the first week of the month, whatever row that is.
            CalendarKey.UP -> from.date.plusDays(-7).let { above ->
                if (above in month) {
                    move(CalendarFocusTarget.Day(above))
                } else {
                    move(CalendarFocusTarget.MonthBar(lastMonthBar ?: monthControlAbove(column)))
                }
            }
            // The rating chips are below the last week of the month.
            CalendarKey.DOWN -> from.date.plusDays(7).let { below ->
                if (below in month) move(CalendarFocusTarget.Day(below)) else move(chips(layout))
            }
            CalendarKey.OK -> firstPoster(layout)?.let(::move) ?: CalendarFocusResult.Stay
            CalendarKey.BACK -> CalendarFocusResult.Exit
        }
    }

    private fun fromPoster(from: CalendarFocusTarget.Poster, key: CalendarKey, layout: CalendarFocusLayout): CalendarFocusResult {
        val section = layout.sections.getOrNull(from.section)
        if (section == null || from.index !in 0 until section.itemCount) return move(clamp(from, layout))
        val columns = section.columns
        val column = from.index % columns
        val row = from.index / columns
        val lastRow = (section.itemCount - 1) / columns
        return when (key) {
            CalendarKey.LEFT -> if (column == 0) {
                move(CalendarFocusTarget.Day(layout.selectedDate))
            } else {
                move(from.copy(index = from.index - 1))
            }
            CalendarKey.RIGHT -> if (column < columns - 1 && from.index + 1 < section.itemCount) {
                move(from.copy(index = from.index + 1))
            } else {
                CalendarFocusResult.Stay
            }
            CalendarKey.UP -> when {
                row > 0 -> move(from.copy(index = (row - 1) * columns + column))
                else -> {
                    val previous = (from.section - 1 downTo 0).firstOrNull { layout.sections[it].itemCount > 0 }
                    if (previous == null) {
                        CalendarFocusResult.Stay
                    } else {
                        val target = layout.sections[previous]
                        val targetRow = (target.itemCount - 1) / target.columns
                        move(CalendarFocusTarget.Poster(previous, (targetRow * target.columns + column).coerceAtMost(target.itemCount - 1)))
                    }
                }
            }
            CalendarKey.DOWN -> when {
                row < lastRow -> move(from.copy(index = ((row + 1) * columns + column).coerceAtMost(section.itemCount - 1)))
                else -> {
                    val next = (from.section + 1 until layout.sections.size).firstOrNull { layout.sections[it].itemCount > 0 }
                    if (next == null) {
                        CalendarFocusResult.Stay
                    } else {
                        move(CalendarFocusTarget.Poster(next, column.coerceAtMost(layout.sections[next].itemCount - 1)))
                    }
                }
            }
            // OK on a poster is the card's own click.
            CalendarKey.OK -> CalendarFocusResult.Stay
            CalendarKey.BACK -> move(CalendarFocusTarget.Day(layout.selectedDate))
        }
    }

    private fun move(target: CalendarFocusTarget) = CalendarFocusResult.Move(target)

    // Previous sits over the first three columns, next over the middle, Today over the last.
    private fun monthControlAbove(column: Int): MonthControl = when (column) {
        in 0..2 -> MonthControl.PREVIOUS
        6 -> MonthControl.TODAY
        else -> MonthControl.NEXT
    }
}
