package io.moata.pleos.domain

import io.moata.pleos.data.remote.CharacterOut
import java.time.Instant
import java.time.ZoneId


object GardenRules {

    const val COLLAPSED_ROWS = 3

    data class BadgeRow(val id: String, val name: String, val description: String, val earned: Boolean)


    fun xpFraction(xp: Int, levelStart: Int, next: Int?): Float {
        if (next == null) return 1f
        val span = (next - levelStart).coerceAtLeast(1)
        return ((xp - levelStart).toFloat() / span).coerceIn(0f, 1f)
    }

    fun <T> visibleRows(items: List<T>, expanded: Boolean): List<T> = if (expanded) items else items.take(COLLAPSED_ROWS)

    fun hiddenCount(total: Int): Int = (total - COLLAPSED_ROWS).coerceAtLeast(0)


    fun badgeRows(ch: CharacterOut): List<BadgeRow> =
        if (ch.allBadges.isNotEmpty()) ch.allBadges.map { BadgeRow(it.id, it.nameKo, it.descriptionKo, it.earned) }
        else ch.badges.map { BadgeRow(it.id, it.nameKo, it.descriptionKo, true) }


    fun dateLabel(startedAt: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (startedAt.isNullOrBlank()) return "-"
        val d = runCatching { Instant.parse(startedAt).atZone(zone).toLocalDate() }.getOrNull()
            ?: return startedAt.take(10).replace('-', '.')
        return "%04d.%02d.%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
}

object HeaderRules {

    fun groupSuffix(groupName: String?): String? = groupName?.trim()?.takeIf { it.isNotEmpty() }?.let { " with $it" }

    enum class HomeAction { GO_HOME, FINISH_TRIP }


    fun homeAction(hasTrip: Boolean, summaryState: String?): HomeAction =
        if (hasTrip && summaryState == "CONFIRMED") HomeAction.FINISH_TRIP else HomeAction.GO_HOME
}
