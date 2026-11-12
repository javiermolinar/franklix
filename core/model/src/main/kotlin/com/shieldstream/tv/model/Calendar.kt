package com.shieldstream.tv.model

/**
 * A local calendar date without time zone. `java.time` needs API 26 and the app supports API 21,
 * so the calendar screen does its date arithmetic on epoch days here.
 */
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {

    val epochDay: Long get() = toEpochDay(year, month, day)

    /** ISO day of week: 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int get() = (epochDay + 3).mod(7L).toInt() + 1

    val calendarMonth: CalendarMonth get() = CalendarMonth(year, month)

    fun plusDays(days: Long): CalendarDate = fromEpochDay(epochDay + days)

    /** `YYYY-MM-DD`, the format TMDB uses for air and release dates. */
    // Locale.US: TMDB needs ASCII digits whatever the device locale (Arabic, Persian…).
    fun iso(): String = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day)

    override fun compareTo(other: CalendarDate): Int = epochDay.compareTo(other.epochDay)

    override fun toString(): String = iso()

    companion object {
        fun of(year: Int, month: Int, day: Int): CalendarDate {
            require(month in 1..12 && day in 1..CalendarMonth(year, month).length) { "Invalid date $year-$month-$day" }
            return CalendarDate(year, month, day)
        }

        /** Parses `YYYY-MM-DD`; returns null for blank, partial or invalid dates. */
        fun parse(value: String?): CalendarDate? {
            if (value == null || value.length != 10 || value[4] != '-' || value[7] != '-') return null
            val year = value.substring(0, 4).toIntOrNull() ?: return null
            val month = value.substring(5, 7).toIntOrNull() ?: return null
            val day = value.substring(8, 10).toIntOrNull() ?: return null
            if (month !in 1..12 || day !in 1..CalendarMonth(year, month).length) return null
            return CalendarDate(year, month, day)
        }

        /** Today in the device's local time zone. */
        fun today(nowMs: Long = System.currentTimeMillis()): CalendarDate {
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            return CalendarDate(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

        // Howard Hinnant's days-from-civil algorithm.
        private fun toEpochDay(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        fun fromEpochDay(epochDay: Long): CalendarDate {
            val z = epochDay + 719468
            val era = z.floorDiv(146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
            return CalendarDate(year, month, day)
        }
    }
}

data class CalendarMonth(val year: Int, val month: Int) : Comparable<CalendarMonth> {
    init {
        require(month in 1..12) { "Invalid month $month" }
    }

    val length: Int
        get() = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    val first: CalendarDate get() = CalendarDate(year, month, 1)
    val last: CalendarDate get() = CalendarDate(year, month, length)

    operator fun contains(date: CalendarDate): Boolean = date.year == year && date.month == month

    fun plusMonths(months: Int): CalendarMonth {
        val index = year * 12 + (month - 1) + months
        return CalendarMonth(index.floorDiv(12), index.mod(12) + 1)
    }

    override fun compareTo(other: CalendarMonth): Int = compareValuesBy(this, other, { it.year }, { it.month })
}

enum class WeekStart(val isoDay: Int) {
    MONDAY(1),
    SUNDAY(7),
}

/** The visible days of a month: whole weeks from the one containing the 1st to the one containing the last day. */
class MonthGrid(val month: CalendarMonth, val weekStart: WeekStart) {
    val start: CalendarDate = month.first.plusDays(-columnOf(month.first).toLong())

    val rows: Int = ((month.last.epochDay - start.epochDay) / 7 + 1).toInt()

    val dates: List<CalendarDate> = List(rows * 7) { start.plusDays(it.toLong()) }

    val end: CalendarDate get() = dates.last()

    fun columnOf(date: CalendarDate): Int = (date.dayOfWeek - weekStart.isoDay).mod(7)

    /** Index in [dates], or -1 when the date is outside this grid. */
    fun indexOf(date: CalendarDate): Int {
        val offset = date.epochDay - start.epochDay
        return if (offset in 0 until dates.size) offset.toInt() else -1
    }

    fun rowOf(date: CalendarDate): Int = indexOf(date).let { if (it < 0) -1 else it / 7 }

    operator fun contains(date: CalendarDate): Boolean = indexOf(date) >= 0

    /** ISO days of week in column order. */
    val weekdays: List<Int> get() = List(7) { (weekStart.isoDay - 1 + it) % 7 + 1 }

    override fun equals(other: Any?): Boolean = other is MonthGrid && other.month == month && other.weekStart == weekStart

    override fun hashCode(): Int = month.hashCode() * 31 + weekStart.hashCode()

    companion object {
        const val MAX_ROWS = 6
    }
}

/** An episode of one of the user's shows on the calendar. The poster is the show's poster. */
data class CalendarEpisode(
    val show: CatalogItem,
    val episode: Episode,
    val date: CalendarDate,
    val lastInSeason: Boolean,
    val watched: Boolean = false,
) {
    val key: String get() = WatchProgress.episodeKey(show.id, episode.season, episode.number)
}

/** A movie or new series released on [date]. [rating] is TMDB's average rounded to one decimal. */
data class CalendarRelease(
    val item: CatalogItem,
    val date: CalendarDate,
    val rating: Float,
    val isNewSeries: Boolean,
)
