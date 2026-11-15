package io.moata.pleos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.moata.pleos.data.remote.SettlementOut
import io.moata.pleos.data.remote.TripSummary
import io.moata.pleos.domain.EfficiencyRules
import io.moata.pleos.domain.Feature
import io.moata.pleos.domain.GardenRules
import io.moata.pleos.domain.TollRules
import io.moata.pleos.domain.UxPolicy

fun krw(n: Int): String = "%,d원".format(n)

fun sourceKo(s: String) = when (s) {
    "PLEOS" -> "PLEOS · 차량에서 읽은 값"; "MANUAL" -> "MANUAL · 직접 입력한 값"; "DEMO" -> "DEMO · 가상 데이터"
    "OPINET" -> "OPINET · 오피넷 평균가"; "ESTIMATE" -> "ESTIMATE · 표시연비 평균 추정"; else -> s
}
fun qualityKo(q: String) = when (q) { "VERIFIED" -> "차량 확인"; "ESTIMATED" -> "추정"; "UNAVAILABLE" -> "없음"; "INVALID" -> "이상값"; else -> q }

private val Pad = Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l)

private fun kindKo(k: String) = when (k) { "TOLL" -> "통행료"; "PARKING" -> "주차비"; else -> "기타" }
private fun allocKo(a: String) = when (a) { "EQUAL" -> "모두 똑같이"; "DISTANCE_WEIGHTED" -> "탄 거리만큼"; "DRIVER_ONLY" -> "운전자만"; else -> a }

fun kmText(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}" else "%.1f".format(v)


fun kmLabel(raw: String?): String = raw?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "?"

@Composable
fun SummaryScreen(s: UiState, vm: AppViewModel) {
    val sum = s.summary
    val parked = UxPolicy.isAllowed(Feature.SETTLEMENT_REVIEW, s.drivingState)
    val canEdit = UxPolicy.isAllowed(Feature.COST_EDIT, s.drivingState)
    var adding by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (sum == null) {
        Column(Pad, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("도착 요약", style = MaterialTheme.typography.headlineMedium, color = Paper)
            Text(if (s.offline) "서버에 연결되면 요약을 볼 수 있어요. 기록은 차량에 저장돼 있어요." else "불러오는 중…", style = MaterialTheme.typography.bodyLarge, color = Muted)
            GhostButton("다시 불러오기") { vm.refreshSummary() }
        }
        return
    }
    Row(Pad, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(sum.distanceKm?.let { "${kmLabel(it)}km를 함께 왔어요" } ?: "도착했어요", style = MaterialTheme.typography.headlineLarge, color = Paper, modifier = Modifier.asHeading())
            RideLanes(sum, Modifier.padding(top = Space.m))
            RouteMapCard(s.route, s.startPlace, s.endPlace, vm.tiles)
            val others = sum.issues
            if (others.isNotEmpty()) Section("확인이 필요해요") { others.forEach { Text("• ${it.messageKo}", style = MaterialTheme.typography.bodyMedium, color = CoralText) } }
        }

        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {

                EfficiencyRules.plainValue(sum.costRule)?.let { v -> val line = "${EfficiencyRules.label(sum.costRule?.vehicleType == "EV")} : $v"
                    Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Paper)
                    }
                }
                TollEstimateCard(s, sum, vm)
                Section("통행료 · 주차비") {
                    val items = sum.costItems
                    if (items.isEmpty()) Text("있으면 더해 주세요. 없으면 바로 정산안을 만들어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    items.forEach { it ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Column(Modifier.weight(1f)) {
                                Text("${itemTitle(it)}  ${krw(it.amountKrw)}", style = MaterialTheme.typography.titleMedium, color = Paper)
                                Text(allocKo(it.allocation), style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            if (canEdit) TextAction("삭제", Modifier.semantics { contentDescription = "${kindKo(it.kind)} ${krw(it.amountKrw)} 삭제" }) { removing = it.id to "${kindKo(it.kind)} ${krw(it.amountKrw)}" }
                        }
                    }
                }

                if (canEdit && s.trip != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    GhostButton("+ 통행료") { adding = "TOLL" }
                    GhostButton("+ 주차비") { adding = "PARKING" }
                    GhostButton("+ 기타") { adding = "OTHER" }
                } else if (!canEdit) Text("비용 수정은 정차 중에만 할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                ExpandAction(if (details) "자세히 접기" else "측정 정보 자세히", details) { details = !details }
                if (details) {
                    Section("측정 정보") {
                        KeyValue("값의 출처", sourceKo(sum.dataMode))
                        KeyValue("시작 주행거리", sum.odometerStartKm?.let { "$it km" } ?: "기록 없음")
                        KeyValue("도착 주행거리", sum.odometerEndKm?.let { "$it km" } ?: "기록 없음")
                        sum.latestOdometer?.let { KeyValue("마지막 판독", "${it.km} km · ${qualityKo(it.quality)}") }
                        sum.participants.filter { !it.isDriver && it.intervals.isNotEmpty() }.forEach { p ->
                            p.intervals.forEach { iv ->
                                Text("${p.nickname}: ${iv.odometerOnKm ?: "?"} → ${iv.odometerOffKm ?: "?"} km (${qualityKo(iv.boundaryQuality)})", style = MaterialTheme.typography.bodySmall,
                                    color = if (iv.boundaryQuality == "VERIFIED") Muted else CoralText)
                            }
                        }
                        sum.costRule?.let { r -> EfficiencyRules.valueText(r)?.let { KeyValue(EfficiencyRules.label(r.vehicleType == "EV"), it) } }
                        if (sum.dataMode == "DEMO") Text("가상 데이터로 계산한 연습용 결과예요.", color = CoralText, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        GhostButton("홈으로") { vm.go(Screen.HOME) }
                        GhostButton("이 운행 닫기", enabled = parked) { vm.finishTrip() }
                    }
                }
            }
            BigButton("정산하기", Modifier.fillMaxWidth(), enabled = parked && !s.busy && s.trip?.state != "IN_PROGRESS") { vm.previewSettlement() }
            TextAction("정산 안내 보기") { info = true }
        }
    }
    adding?.let { kind ->
        if (canEdit) AmountDialog(kindKo(kind), onCancel = { adding = null }) { amount, alloc -> adding = null; vm.addCostItem(kind, amount, alloc) }
    }
    if (info) InfoDialog("정산 안내", sum.disclaimers) { info = false }
    removing?.let { (id, label) ->
        ConfirmDialog("${label}을 뺄까요?", "정산에서 빠져요. 필요하면 다시 더할 수 있어요.", "빼기", "그대로 두기",
            onConfirm = { removing = null; vm.deleteCostItem(id) }, onCancel = { removing = null }, danger = true)
    }
}

private fun itemTitle(i: io.moata.pleos.data.remote.CostItemOut) = if (i.roadName.isBlank()) kindKo(i.kind) else "${kindKo(i.kind)} ${i.roadName}"





@Composable
private fun TollEstimateCard(s: UiState, sum: TripSummary, vm: AppViewModel) {
    val est = s.tollEstimate
    if (est == null) {
        if (s.tollFailed) Section("지나온 길 통행료") {
            Text("통행료를 계산하지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            GhostButton("다시 계산") { vm.estimateTolls() }
        }
        return
    }
    if (!est.dataAvailable) return
    val lines = TollRules.lines(est, sum.id)
    if (lines.isEmpty()) {
        if (est.unmatchedGates.isNotEmpty() || est.incomplete) Section("지나온 길 통행료") {
            if (est.unmatchedGates.isNotEmpty()) Text("지나간 영업소 ${est.unmatchedGates.size}곳의 요금을 찾지 못했어요. 필요하면 아래에서 직접 더해 주세요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (est.incomplete) Text("일부 요금은 확인하지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (est.incomplete) GhostButton("다시 계산") { vm.estimateTolls() }
        }
        return
    }
    val itemIds = sum.costItems.mapTo(HashSet()) { it.id }
    val states = lines.map { TollRules.state(it.id, itemIds, s.tollAuto, s.pending) }
    Section("지나온 길 통행료") {
        lines.forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Column(Modifier.weight(1f)) {
                    Text(line.roadName, style = MaterialTheme.typography.titleMedium, color = Paper)
                    when (states[i]) {
                        TollRules.LineState.REMOVED -> Text("정산에서 뺐어요", style = MaterialTheme.typography.bodySmall, color = Muted)
                        TollRules.LineState.PENDING -> Text(if (s.restricted) "정차하면 정산에 반영돼요" else "정산에 반영하는 중이에요", style = MaterialTheme.typography.bodySmall, color = Muted)
                        TollRules.LineState.INCLUDED -> Unit
                    }
                }
                Text(krw(line.amountKrw), style = MaterialTheme.typography.titleMedium, color = MoaYellow)
            }
        }
        if (lines.size > 1) KeyValue("합계", krw(lines.sumOf { it.amountKrw }))
        if (states.any { it == TollRules.LineState.REMOVED }) Text("뺀 항목은 다시 더하지 않아요. 필요하면 아래에서 직접 더해 주세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
        if (est.incomplete) {
            Text("일부 요금은 확인하지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            GhostButton("다시 계산") { vm.estimateTolls() }
        }
    }
}


@Composable
private fun AmountDialog(title: String, onCancel: () -> Unit, onAdd: (String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var alloc by remember { mutableStateOf("EQUAL") }
    BigDialog(onCancel) {
        Text("$title 얼마예요?", style = MaterialTheme.typography.headlineMedium, color = Paper)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                ValueCard(title, amount, "원", true)
                Text("누가 나눠 낼까요?", style = MaterialTheme.typography.bodySmall, color = Muted)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Choice(listOf("EQUAL" to "모두 똑같이", "DISTANCE_WEIGHTED" to "탄 거리만큼"), alloc, { alloc = it })
                    Choice(listOf("DRIVER_ONLY" to "운전자만"), alloc, { alloc = it })
                }
            }
            NumberPad(amount, { amount = it }, Modifier.weight(1f), maxLen = 8)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
            BigButton("더하기", Modifier.weight(1f), enabled = (amount.toIntOrNull() ?: 0) > 0) { onAdd(amount, alloc) }
        }
    }
}

@Composable
fun SettlementScreen(s: UiState, vm: AppViewModel) {
    val st: SettlementOut? = s.summary?.settlement
    val parked = UxPolicy.isAllowed(Feature.SETTLEMENT_REVIEW, s.drivingState)
    var basis by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    if (st == null) {
        Column(Pad, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("정산안", style = MaterialTheme.typography.headlineMedium, color = Paper)
            Text("정산안을 불러오는 중이에요…", style = MaterialTheme.typography.bodyLarge, color = Muted)
            GhostButton("다시 불러오기") { vm.refreshSummary() }
        }
        return
    }
    val mine = st.shares.firstOrNull { it.userId == s.userId }
    val approved = st.shares.count { it.approval == "APPROVED" }
    Row(Pad, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("이동비 합계", style = MaterialTheme.typography.titleLarge, color = Muted)
            Text(krw(st.totalKrw), style = MaterialTheme.typography.displayLarge, color = MoaYellow)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("추정 금액이에요 · 결제·송금은 없어요", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextAction("안내") { info = true }
            }
            st.warnings.forEach { Text("• ${it.messageKo}", style = MaterialTheme.typography.bodyMedium, color = CoralText) }
            Receipt(s, st)
            RideLanes(s.summary, Modifier.padding(top = Space.s))
            ExpandAction(if (basis) "계산 근거 접기" else "구간별 계산 근거", basis) { basis = !basis }
            if (basis) Section {
                Text("소비량 ${st.consumption} ${st.consumptionUnit} · 이동 ${st.distanceKm} km · ${sourceKo(st.efficiencySource)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                st.segments.forEach {
                    Text("${it.startKm}–${it.endKm} km (${it.lengthKm} km) · ${krw(it.energyKrw)} · ${it.payers.size}명이 나눔", style = MaterialTheme.typography.bodyMedium, color = Paper)
                }
            }
        }

        Column(Modifier.weight(1.05f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("각자 부담", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.weight(1f).asHeading())
                Pill("$approved/${st.shares.size}명 승인", if (approved == st.shares.size) Ink else Paper, if (approved == st.shares.size) TealText else SurfaceHi)
            }
            if (mine?.isDriver == true) Text("받을 금액 ${krw(st.shares.filter { !it.isDriver }.sumOf { it.owedToDriverKrw })} · 송금은 각자 직접", style = MaterialTheme.typography.titleMedium, color = Paper)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                st.shares.forEach { sh ->
                    Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Column(Modifier.weight(1f)) {
                            Text(sh.nickname + if (sh.isDriver) "(운전자)" else "", style = MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1)
                            routeText(s)?.let { Text("경로 : $it", style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1) }
                            Text("거리 : ${kmLabel(sh.riddenKm)} km", style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(krw(sh.totalKrw), style = MaterialTheme.typography.headlineMedium, color = MoaYellow)
                            when (sh.approval) {
                                "APPROVED" -> Pill("승인함", Ink, TealText, icon = MoaIcons.Check)
                                "DISPUTED" -> Pill("이의 제기", Ink, CoralText)
                                else -> Pill("승인 전", Paper, SurfaceHi)
                            }
                        }
                    }
                    if (sh.approval == "DISPUTED" && !sh.comment.isNullOrBlank()) Text("“${sh.comment}”", style = MaterialTheme.typography.bodySmall, color = CoralText)
                }
            }
            if (st.state == "CONFIRMED") {
                Pill("모두 금액에 합의했어요 · 송금은 각자 직접", Ink, TealText, icon = MoaIcons.Check)
                BigButton("나의 모아 보기", Modifier.fillMaxWidth()) { vm.finishTrip(openMoa = true) }
                TextAction("홈으로", leading = MoaIcons.Back) { vm.finishTrip() }
            } else if (st.shares.any { it.approval == "DISPUTED" }) {
                Column(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("이의가 있어요", style = MaterialTheme.typography.titleLarge, color = CoralText, modifier = Modifier.asHeading())
                    Text("비용을 고친 뒤 정산안을 다시 만들면, 모두에게 새 금액을 다시 물어봐요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                BigButton("비용 고치기", Modifier.fillMaxWidth(), enabled = parked && !s.busy) { vm.go(Screen.SUMMARY) }
            } else {

                if (mine != null && mine.approval == "PENDING") BigButton("승인", Modifier.fillMaxWidth(), enabled = parked && !s.busy) { vm.decide(st.id, true) }
                else Text("다른 분들의 승인을 기다리고 있어요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
                TextAction("비용 고치기", leading = MoaIcons.Back) { vm.go(Screen.SUMMARY) }
            }
        }
    }
    if (info) InfoDialog("정산 안내", st.disclaimers) { info = false }
}


private fun routeText(s: UiState): String? {
    val a = s.startPlace?.short ?: return null
    val b = s.endPlace?.short ?: return null
    return "$a → $b"
}


@Composable
private fun Receipt(s: UiState, st: SettlementOut) {
    val rule = s.summary?.costRule
    val ev = rule?.vehicleType == "EV"
    Section {
        KeyValue("거리", "${kmLabel(st.distanceKm)} km")
        EfficiencyRules.plainValue(rule)?.let { KeyValue(EfficiencyRules.label(ev), it) }
        rule?.unitPriceKrw?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()?.let {
            KeyValue(if (ev) "전기요금" else "유가", "${grouped(it)}원/${if (ev) "kWh" else "L"}")
        }
        HorizontalDivider(color = SurfaceHi, thickness = 2.dp)
        KeyValue(if (ev) "전기요금 합계" else "연료비", krw(st.energyTotalKrw))
        s.summary?.costItems.orEmpty().forEach { KeyValue(itemTitle(it), krw(it.amountKrw)) }
        HorizontalDivider(color = SurfaceHi, thickness = 2.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("합계", style = MaterialTheme.typography.titleLarge, color = Paper)
            Text(krw(st.totalKrw), style = MaterialTheme.typography.titleLarge, color = MoaYellow)
        }
    }
}

@Composable
fun GardenScreen(s: UiState, vm: AppViewModel) {
    val ch = s.character
    Row(Pad, horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Moa(
                size = 340.dp,
                mood = when (ch?.mood) { "SLEEPY" -> MoaMood.SLEEPY; "PROUD" -> MoaMood.PROUD; else -> MoaMood.HAPPY },
                animate = UxPolicy.isAllowed(Feature.MOA_ANIMATION, s.drivingState),
            )
            Text(ch?.let { "Lv.${it.level}" } ?: "…", style = MaterialTheme.typography.headlineLarge, color = MoaYellow)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("나의 모아", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
            if (ch == null) {
                Text(if (s.offline) "서버에 연결되면 모아를 만날 수 있어요." else "모아를 불러오는 중…", style = MaterialTheme.typography.bodyLarge, color = Muted)
                GhostButton("다시 불러오기") { vm.loadGarden() }
            } else {
                Section("함께 자라는 중") {
                    XpGauge(ch.xp, ch.levelXpStart, ch.nextLevelXp, Modifier.padding(vertical = Space.xs))
                    Text("경험치 ${ch.xp}${ch.nextLevelXp?.let { " / $it" } ?: ""} · 함께 마친 이동 ${ch.completedTrips}번", style = MaterialTheme.typography.bodyMedium, color = Paper)
                    Text(ch.noteKo.ifBlank { "모아는 함께 정산을 마친 이동 기록으로만 자라요. 거리·속도와는 관계없어요." }, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Section("이동 기록") {
                    if (s.history.isEmpty()) Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    else HistoryTable(s.history)
                }
                Section("배지") {
                    val badges = GardenRules.badgeRows(ch)
                    if (badges.isEmpty()) Text("아직 배지가 없어요. 함께 한 번 이동하면 첫 배지가 생겨요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    else BadgeChecklist(badges)
                }
                Section("데모 쿠폰") {
                    Pill("시연용 / 실제 교환 불가", Ink, CoralText)
                    if (s.coupons.isEmpty()) Text("레벨이 오르면 시연용 쿠폰이 나타나요. 실제 금전적 가치는 없어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    s.coupons.forEach { Text("${it.brandLabel} — ${it.disclaimerKo.ifBlank { "시연용 쿠폰입니다. 실제로 교환할 수 없습니다." }}", style = MaterialTheme.typography.bodyMedium, color = Paper) }
                }
            }
            TextAction("홈으로", leading = MoaIcons.Back) { vm.go(Screen.HOME) }
        }
    }
}
