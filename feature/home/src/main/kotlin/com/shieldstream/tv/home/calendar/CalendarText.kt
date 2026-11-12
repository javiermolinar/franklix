package com.shieldstream.tv.home.calendar

import com.shieldstream.tv.model.CalendarMonth
import java.text.DateFormatSymbols
import java.util.Locale

/**
 * Month and weekday names for the app language. Built once per locale; the grid and the day title
 * read from arrays instead of constructing a date formatter during recomposition.
 */
internal class CalendarNames(private val locale: Locale) {
    private val symbols = DateFormatSymbols.getInstance(locale)
    private val months: Array<String> = symbols.months
    private val shortWeekdays: Array<String> = symbols.shortWeekdays

    fun month(month: Int): String = months[month - 1]

    /** [isoDay] is 1 = Monday … 7 = Sunday. */
    fun shortWeekday(isoDay: Int): String = shortWeekdays[isoDay % 7 + 1].removeSuffix(".").capitalized()

    fun String.capitalized(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** `%1$s %2$d` month title, e.g. "November 2026". */
internal fun CalendarNames.monthTitle(format: String, month: CalendarMonth): String =
    format.format(month(month.month), month.year).capitalized()
