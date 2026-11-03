package io.moata.pleos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.moata.pleos.data.remote.TripSummary

/** Spans are km from the trip start. `complete` is false when a boundary is missing. */
data class Lane(val name: String, val isDriver: Boolean, val spans: List<Pair<Double, Double>>, val complete: Boolean, val byDriver: Boolean = false) {
    val km: Double get() = spans.sumOf { (a, b) -> (b - a).coerceAtLeast(0.0) }
    /** Where the last ride ended, when that was before the end of the trip. */
    fun exitBefore(total: Double): Double? = spans.lastOrNull()?.second?.takeIf { !isDriver && it < total - 0.05 }
}

/** Lanes on a shared 0..total axis. Null until both ends of the trip are known. */
fun lanesFrom(sum: TripSummary): Pair<Double, List<Lane>>? {
    val start = sum.odometerStartKm?.toDoubleOrNull() ?: return null
    val total = sum.distanceKm?.toDoubleOrNull() ?: sum.odometerEndKm?.toDoubleOrNull()?.minus(start) ?: return null
    if (total <= 0.0) return null
    val lanes = sum.participants.filter { it.memberStatus == "ACTIVE" && (it.isDriver || it.intervals.isNotEmpty()) }.map { p ->
        if (p.isDriver) Lane(p.nickname, true, listOf(0.0 to total), true)
        else {
            var complete = true
            val spans = p.intervals.mapNotNull { iv ->
                val on = iv.odometerOnKm?.toDoubleOrNull()
                val off = iv.odometerOffKm?.toDoubleOrNull()
                if (on == null || off == null) { complete = false; null }
                else (on - start).coerceIn(0.0, total) to (off - start).coerceIn(0.0, total)
            }
            Lane(p.nickname, false, spans, complete, byDriver = p.intervals.any { it.openedBy == "DRIVER" })
        }
    }.sortedByDescending { it.isDriver }
    return total to lanes
}

/**
 * Who rode which part of the trip. TalkBack reads one sentence per lane ("지호, 출발부터 16km까지 탔어요"),
 * so the bars are never the only carrier of the information.
 */
@Composable
fun RideLanes(sum: TripSummary, modifier: Modifier = Modifier) {
    val (total, lanes) = lanesFrom(sum) ?: run {
        Text("탄 구간은 출발과 도착 주행거리가 모두 기록되면 보여요.", modifier, style = MaterialTheme.typography.bodyMedium, color = Muted)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        lanes.forEach { LaneRow(it, total) }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text("출발 0km", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text("도착 ${kmText(total)}km", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun LaneRow(lane: Lane, total: Double) {
    val exit = lane.exitBefore(total)
    val said = when {
        !lane.complete -> "${lane.name}, 탄 구간을 확인해야 해요"
        lane.isDriver -> "${lane.name}, 운전자, 처음부터 끝까지 ${kmText(total)}km"
        else -> "${lane.name}, " + lane.spans.joinToString(", ") { (a, b) -> "${if (a < 0.05) "출발" else "${kmText(a)}km"}부터 ${if (b >= total - 0.05) "도착" else "${kmText(b)}km"}까지" } +
            " 탔어요, 모두 ${kmText(lane.km)}km" + if (lane.byDriver) ", 탑승은 운전자가 기록" else ""
    }
    val note = listOfNotNull(exit?.let { "${kmText(it)}km에서 내림" }, "운전자가 탑승 기록".takeIf { lane.byDriver }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = said }, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(lane.name, Modifier.widthIn(max = 420.dp), style = MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                lane.isDriver -> Text("운전자", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(bottom = 2.dp))
                note.isNotEmpty() -> Text(note, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(if (lane.complete) "${kmText(lane.km)} km" else "확인 필요", style = MaterialTheme.typography.titleLarge, color = if (lane.complete) MoaYellow else CoralText)
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(SurfaceHi, cornerRadius = r)
            lane.spans.forEach { (a, b) ->
                val x0 = (a / total * size.width).toFloat()
                val w = ((b - a) / total * size.width).toFloat().coerceAtLeast(size.height)
                drawRoundRect(TealText, topLeft = Offset(x0.coerceAtMost(size.width - w), 0f), size = Size(w, size.height), cornerRadius = r)
            }
        }
    }
}
