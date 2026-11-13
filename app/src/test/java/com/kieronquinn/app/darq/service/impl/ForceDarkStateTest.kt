package com.kieronquinn.app.darq.service.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceDarkStateTest {

    @Test
    fun disabledWinsOverSelectedAppsAndAlwaysOn() {
        val state = ForceDarkState()
        state.selectedPackages.add("example.selected")
        state.updateForeground("example.selected", foreground = true)
        assertFalse(state.shouldForceDark)

        state.alwaysForceDark = true
        assertFalse(state.shouldForceDark)
        state.enabled = true
        assertTrue(state.shouldForceDark)
    }

    @Test
    fun removingTheCurrentAppAndTurningOffAlwaysOnReapplyTheSelection() {
        val state = ForceDarkState().apply {
            enabled = true
            alwaysForceDark = true
            selectedPackages.add("example.selected")
            updateForeground("example.selected", foreground = true)
        }

        state.alwaysForceDark = false
        assertTrue(state.shouldForceDark)
        state.selectedPackages.clear()
        assertFalse(state.shouldForceDark)
    }

    @Test
    fun anOldAppClosingDoesNotClearTheCurrentApp() {
        val state = ForceDarkState().apply {
            enabled = true
            sendAppCloses = true
            selectedPackages.add("example.selected")
            updateForeground("example.selected", foreground = true)
        }

        state.updateForeground("example.previous", foreground = false)
        assertTrue(state.shouldForceDark)
        state.updateForeground("example.selected", foreground = false)
        assertFalse(state.shouldForceDark)
    }

    @Test
    fun appClosesCanBeIgnoredUntilAnotherAppOpens() {
        val state = ForceDarkState().apply {
            enabled = true
            selectedPackages.add("example.selected")
            updateForeground("example.selected", foreground = true)
        }

        state.updateForeground("example.selected", foreground = false)
        assertTrue(state.shouldForceDark)
        state.updateForeground("example.other", foreground = true)
        assertFalse(state.shouldForceDark)
    }
}
