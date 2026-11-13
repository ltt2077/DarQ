package com.kieronquinn.app.darq.model.xposed

internal object ForceDarkRendering {
    // android.graphics.ForceDarkType values in AOSP.
    const val NONE = 0
    const val FORCE_DARK = 1

    fun choose(frameworkMode: Int, isDarkMode: Boolean, isLightTheme: Boolean): Int {
        return if (frameworkMode == NONE && isDarkMode && isLightTheme) FORCE_DARK else frameworkMode
    }
}
