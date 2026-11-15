package io.moata.pleos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.moata.pleos.data.remote.HistoryItem
import io.moata.pleos.domain.GardenRules

private val GaugeShape = RoundedCornerShape(16.dp)

private data class HistoryRow(
    val date: String,
    val distance: String,
    val amount: String,
    val people: String,
)


@Composable
fun XpGauge(xp: Int, levelStart: Int, next: Int?, modifier: Modifier = Modifier) {
    val fraction = GardenRules.xpFraction(xp, levelStart, next)
    val text = "경험치 $xp" + (next?.let { " / $it" } ?: "")
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(GaugeShape).background(SurfaceHi)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f); contentDescription = text },
    ) {
        if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(GaugeShape).background(MoaYellow))
    }
}


@Composable
fun BadgeChecklist(rows: List<GardenRules.BadgeRow>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "${r.name}. ${r.description}"
                    stateDescription = if (r.earned) "획득함" else "아직 못 얻음"
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    Modifier.size(44.dp).background(if (r.earned) OkGreen.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .border(3.dp, if (r.earned) OkGreen else Muted, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (r.earned) Icon(MoaIcons.Check, contentDescription = null, tint = OkGreen, modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, color = if (r.earned) Paper else Muted)
                    Text(r.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}


@Composable
fun HistoryTable(items: List<HistoryItem>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val hidden = GardenRules.hiddenCount(items.size)
    val rows = remember(items, expanded) {
        GardenRules.visibleRows(items, expanded).map { item ->
            HistoryRow(
                date = GardenRules.dateLabel(item.startedAt),
                distance = "${kmLabel(item.distanceKm)} km",
                amount = item.totalKrw?.let(::krw) ?: "정산 전",
                people = "${item.participantCount}명",
            )
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("날짜", 1.6f); HeaderCell("거리", 1.1f); HeaderCell("금액", 1.4f); HeaderCell("사람", 0.9f)
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SurfaceHi, RoundedCornerShape(24.dp)).padding(horizontal = Space.m, vertical = Space.xs)
                    .semantics(mergeDescendants = true) { contentDescription = "${row.date}, ${row.distance}, ${row.amount}, ${row.people}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell(row.date, 1.6f); Cell(row.distance, 1.1f); Cell(row.amount, 1.4f); Cell(row.people, 0.9f)
            }
        }
        if (hidden > 0) ExpandAction(if (expanded) "접기" else "더 보기 ($hidden)", expanded) { expanded = !expanded }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, color = Muted)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.titleMedium, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
