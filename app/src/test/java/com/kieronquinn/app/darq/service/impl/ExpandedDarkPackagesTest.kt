package com.kieronquinn.app.darq.service.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedDarkPackagesTest {

    @Test
    fun preservesAnOverrideThatExistedBeforeDarq() {
        val current = "com.example.original"
        val selected = setOf("com.example.original", "com.example.darq")
        val enabled = ExpandedDarkPackages.merge(current, emptySet(), selected)
        assertEquals("com.example.darq,com.example.original", enabled.csv)
        assertEquals(setOf("com.example.darq"), enabled.owned)

        val disabled = ExpandedDarkPackages.merge(enabled.csv, enabled.owned, emptySet())
        assertEquals(current, disabled.csv)
        assertTrue(disabled.owned.isEmpty())
    }

    @Test
    fun refreshesTheSelectedPackageAfterAnExternalRemoval() {
        val selected = setOf("com.example.darq")
        val result = ExpandedDarkPackages.merge("", selected, selected)
        assertEquals("com.example.darq", result.csv)
        assertEquals(selected, result.owned)
    }

    @Test
    fun rejectsUnexpectedSettingsOutput() {
        assertFalse(ExpandedDarkPackages.isPackageList("Permission denied"))
        assertFalse(ExpandedDarkPackages.isPackageList("com.example.one,"))
        assertTrue(ExpandedDarkPackages.isPackageList("com.example.one,com.example.two"))
    }
}
