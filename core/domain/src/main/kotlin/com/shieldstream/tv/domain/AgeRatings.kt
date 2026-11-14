package com.shieldstream.tv.domain

import java.util.Locale

/**
 * Converts TMDB certifications (movie `release_dates`, show `content_ratings`) to a minimum age.
 * 0 means all audiences; null means unknown or a rating this app does not map.
 */
object AgeRatings {

    /** Countries checked in order: the interface language's own country first. */
    fun countries(english: Boolean): List<String> = if (english) listOf("US", "ES") else listOf("ES", "US")

    /** First country in [countries] whose certification maps to an age. */
    fun pick(countries: List<String>, certificationOf: (String) -> String?): Int? =
        countries.firstNotNullOfOrNull { minimumAge(certificationOf(it)) }

    fun minimumAge(raw: String?): Int? {
        val c = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            c in ALL_AUDIENCES -> 0
            c.all { it.isDigit() } -> c.toIntOrNull()
            c == "PG" || c == "TV-Y7" || c == "TV-PG" -> 7
            c == "PG-13" || c == "TV-14" -> 13
            c == "R" -> 16
            c == "NC-17" || c == "TV-MA" -> 18
            else -> null
        }
    }

    /** Limits a Kids profile can choose: the highest minimum age it may play. 0 is all audiences only. */
    val LIMITS: List<Int> = listOf(0, 7, 12, 16)

    /**
     * Whether a title rated [minimumAge] may play under [limit]. No limit allows everything; with a
     * limit, unrated titles are blocked because an unknown rating is not safe for a Kids profile.
     */
    fun allows(limit: Int?, minimumAge: Int?): Boolean =
        limit == null || (minimumAge != null && minimumAge <= limit)

    private val ALL_AUDIENCES = setOf("A", "TP", "APTA", "G", "TV-G", "TV-Y", "ALL")
}
