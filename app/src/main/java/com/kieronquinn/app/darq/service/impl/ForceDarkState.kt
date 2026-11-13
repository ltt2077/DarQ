package com.kieronquinn.app.darq.service.impl

internal class ForceDarkState {
    var enabled = false
    var alwaysForceDark = false
    var sendAppCloses = false
    var foregroundPackage: String? = null
    val selectedPackages = mutableSetOf<String>()

    val shouldForceDark: Boolean
        get() = enabled && (alwaysForceDark || foregroundPackage in selectedPackages)

    fun updateForeground(packageName: String, foreground: Boolean) {
        if (foreground) {
            foregroundPackage = packageName
        } else if (sendAppCloses && foregroundPackage == packageName) {
            foregroundPackage = null
        }
    }
}
