package com.shieldstream.tv.domain

import com.shieldstream.tv.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeRatingsTest {

    @Test
    fun `maps US and Spanish certifications to minimum ages`() {
        assertEquals(0, AgeRatings.minimumAge("G"))
        assertEquals(0, AgeRatings.minimumAge("tp"))
        assertEquals(0, AgeRatings.minimumAge("TV-Y"))
        assertEquals(7, AgeRatings.minimumAge("PG"))
        assertEquals(7, AgeRatings.minimumAge("7"))
        assertEquals(12, AgeRatings.minimumAge(" 12 "))
        assertEquals(13, AgeRatings.minimumAge("PG-13"))
        assertEquals(13, AgeRatings.minimumAge("TV-14"))
        assertEquals(16, AgeRatings.minimumAge("R"))
        assertEquals(18, AgeRatings.minimumAge("NC-17"))
        assertEquals(18, AgeRatings.minimumAge("TV-MA"))
    }

    @Test
    fun `unknown or blank certifications have no age`() {
        assertNull(AgeRatings.minimumAge(null))
        assertNull(AgeRatings.minimumAge(""))
        assertNull(AgeRatings.minimumAge("NR"))
        assertNull(AgeRatings.minimumAge("X"))
    }

    @Test
    fun `pick falls back to the next country when the first has no usable rating`() {
        val ratings = mapOf("ES" to "", "US" to "PG-13")
        assertEquals(13, AgeRatings.pick(AgeRatings.countries(english = false)) { ratings[it] })
        assertEquals(16, AgeRatings.pick(listOf("ES", "US")) { mapOf("ES" to "16", "US" to "R")[it] })
        assertNull(AgeRatings.pick(listOf("ES", "US")) { null })
    }

    @Test
    fun `interface language decides the country order`() {
        assertEquals(listOf("US", "ES"), AgeRatings.countries(english = true))
        assertEquals(listOf("ES", "US"), AgeRatings.countries(english = false))
    }

    @Test
    fun `a limit allows ratings up to it and blocks higher or unrated titles`() {
        assertTrue(AgeRatings.allows(limit = null, minimumAge = null))
        assertTrue(AgeRatings.allows(limit = null, minimumAge = 18))
        assertTrue(AgeRatings.allows(limit = 12, minimumAge = 0))
        assertTrue(AgeRatings.allows(limit = 12, minimumAge = 12))
        assertFalse(AgeRatings.allows(limit = 12, minimumAge = 13))
        assertFalse(AgeRatings.allows(limit = 0, minimumAge = 7))
        assertFalse(AgeRatings.allows(limit = 16, minimumAge = null))
    }

    @Test
    fun `only Kids profiles apply their age limit`() {
        assertEquals(12, Profile("k", "Kids", 0, kids = true, maxAge = 12).ageLimit)
        assertNull(Profile("a", "Adult", 0, kids = false, maxAge = 12).ageLimit)
        assertNull(Profile("k", "Kids", 0, kids = true).ageLimit)
    }
}
