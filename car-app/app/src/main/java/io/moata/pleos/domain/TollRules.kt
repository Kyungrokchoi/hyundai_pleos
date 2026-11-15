package io.moata.pleos.domain

import io.moata.pleos.data.remote.TollEstimateOut

object TollRules {

    const val VEHICLE_CLASS_PASSENGER = 1
    const val ITEM_KIND = "TOLL"
    const val DEFAULT_ALLOCATION = "EQUAL"
    const val ROAD_NAME_MAX = 40


    data class Line(val id: String, val roadName: String, val amountKrw: Int)

    enum class LineState { INCLUDED, REMOVED, PENDING }






    fun itemId(tripId: String, roadName: String, occurrence: Int) = "toll-est-$tripId-" + "%08x".format("$roadName#$occurrence".hashCode())


    fun lines(est: TollEstimateOut, tripId: String): List<Line> {
        val seen = HashMap<String, Int>()
        return est.segments.filter { it.amountKrw > 0 }.map { seg ->
            val name = seg.roadName.take(ROAD_NAME_MAX)
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            Line(itemId(tripId, name, n), name, seg.amountKrw)
        }
    }


    fun due(lines: List<Line>, autoAdded: Set<String>): List<Line> = lines.filter { it.id !in autoAdded }





    fun state(id: String, itemIds: Set<String>, autoAdded: Set<String>, outboxPending: Int): LineState = when {
        id in itemIds -> LineState.INCLUDED
        id in autoAdded && outboxPending == 0 -> LineState.REMOVED
        else -> LineState.PENDING
    }
}
