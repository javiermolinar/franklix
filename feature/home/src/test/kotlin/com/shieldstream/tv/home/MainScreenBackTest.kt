package com.shieldstream.tv.home

import com.shieldstream.tv.ui.component.NavTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenBackTest {

    @Test
    fun `kids profiles cannot select or restore the calendar tab`() {
        assertFalse(NavTab.CALENDAR in availableNavTabs(kidsProfile = true))
        assertEquals(NavTab.entries, availableNavTabs(kidsProfile = false))
        assertEquals(NavTab.HOME, visibleNavTab(NavTab.CALENDAR, kidsProfile = true))
        assertEquals(NavTab.CALENDAR, visibleNavTab(NavTab.CALENDAR, kidsProfile = false))
    }

    @Test
    fun `exit confirmation requires the navigation rail to be focused`() {
        assertTrue(shouldRequestExit(categoryOpen = false, railFocused = true))
        assertFalse(shouldRequestExit(categoryOpen = false, railFocused = false))
        assertFalse(shouldRequestExit(categoryOpen = true, railFocused = true))
        assertFalse(shouldRequestExit(categoryOpen = true, railFocused = false))
    }
}
