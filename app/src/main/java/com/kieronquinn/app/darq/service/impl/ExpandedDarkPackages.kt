package com.kieronquinn.app.darq.service.impl

internal object ExpandedDarkPackages {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    data class Merge(val csv: String, val owned: Set<String>)

    fun isPackageList(csv: String): Boolean =
        csv.isEmpty() || csv.split(',').all(packageName::matches)

    fun merge(csv: String, owned: Set<String>, desired: Set<String>): Merge {
        val existing = csv.split(',').filter(String::isNotEmpty).toSet()
        val selected = desired.filter(packageName::matches).toSet()
        val added = selected - existing
        val removed = owned - selected
        val next = existing - removed + added
        return Merge(
            csv = if (next == existing) csv else next.sorted().joinToString(","),
            owned = (owned intersect selected) + added
        )
    }
}
