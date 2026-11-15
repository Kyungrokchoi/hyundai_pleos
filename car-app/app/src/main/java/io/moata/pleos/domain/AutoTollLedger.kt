package io.moata.pleos.domain

import java.io.File





class AutoTollLedger(private val dir: File) {
    private fun file(tripId: String): File {
        require(tripId.matches(SAFE_ID)) { "unsafe trip id" }
        return File(dir, "toll-auto-$tripId.txt")
    }

    fun added(tripId: String): Set<String> {
        val f = file(tripId)
        return if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    }

    fun record(tripId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        dir.mkdirs()
        file(tripId).appendText(ids.joinToString(separator = "\n", postfix = "\n"))
    }

    fun clear(tripId: String) { file(tripId).delete() }

    private companion object { val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$") }
}
