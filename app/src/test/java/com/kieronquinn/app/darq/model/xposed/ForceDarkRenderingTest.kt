package com.kieronquinn.app.darq.model.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class ForceDarkRenderingTest {

    @Test
    fun darkSystemCanRenderALightWindowDark() {
        assertEquals(1, ForceDarkRendering.choose(0, isDarkMode = true, isLightTheme = true))
    }

    @Test
    fun lightSystemAndNativeDarkWindowsArePreserved() {
        assertEquals(0, ForceDarkRendering.choose(0, isDarkMode = false, isLightTheme = true))
        assertEquals(0, ForceDarkRendering.choose(0, isDarkMode = true, isLightTheme = false))
    }

    @Test
    fun expandedDarkAndOtherFrameworkModesArePreserved() {
        assertEquals(1, ForceDarkRendering.choose(1, isDarkMode = true, isLightTheme = true))
        assertEquals(2, ForceDarkRendering.choose(2, isDarkMode = true, isLightTheme = true))
        assertEquals(3, ForceDarkRendering.choose(3, isDarkMode = false, isLightTheme = false))
    }
}
