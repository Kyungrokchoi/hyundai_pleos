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
import io.moata.pleos.domain.Feature
import io.moata.pleos.domain.UxPolicy

fun krw(n: Int): String = "%,d원".format(n)
/** The PLEOS | MANUAL | DEMO code always stays visible, with plain Korean next to it. */
fun sourceKo(s: String) = when (s) { "PLEOS" -> "PLEOS · 차량에서 읽은 값"; "MANUAL" -> "MANUAL · 직접 입력한 값"; "DEMO" -> "DEMO · 가상 데이터"; else -> s }
fun qualityKo(q: String) = when (q) { "VERIFIED" -> "차량 확인"; "ESTIMATED" -> "추정"; "UNAVAILABLE" -> "없음"; "INVALID" -> "이상값"; else -> q }

private val Pad = Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l)
private val EFFICIENCY_CODES = setOf("EFFICIENCY_MISSING", "FUEL_ECONOMY_UNAVAILABLE", "EV_EFFICIENCY_UNAVAILABLE")   // shown as the 연비 card instead

private fun kindKo(k: String) = when (k) { "TOLL" -> "통행료"; "PARKING" -> "주차비"; else -> "기타" }
private fun allocKo(a: String) = when (a) { "EQUAL" -> "모두 똑같이"; "DISTANCE_WEIGHTED" -> "탄 거리만큼"; "DRIVER_ONLY" -> "운전자만"; else -> a }

fun kmText(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}" else "%.1f".format(v)

/** The server's "24.000" reads like twenty-four thousand; show "24" / "16.5". */
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
        // left: trip overview
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(sum.distanceKm?.let { "${kmLabel(it)}km를 함께 왔어요" } ?: "도착했어요", style = MaterialTheme.typography.headlineLarge, color = Paper, modifier = Modifier.asHeading())
            Text("거리 · ${sourceKo(sum.dataMode)}", style = MaterialTheme.typography.bodyMedium, color = Muted)
            RideLanes(sum, Modifier.padding(top = Space.m))
            val others = sum.issues.filter { it.code !in EFFICIENCY_CODES }
            if (others.isNotEmpty()) Section("확인이 필요해요") { others.forEach { Text("• ${it.messageKo}", style = MaterialTheme.typography.bodyMedium, color = CoralText) } }
        }
        // right: costs and next step
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                val ev = sum.costRule?.vehicleType == "EV"
                val eff = sum.costRule
                if (eff != null && eff.efficiencyKmPerUnit == null) Section(if (ev) "전비" else "연비") {
                    Text("이 차량은 ${if (ev) "전비" else "연비"}를 알려주지 않아요. 평소 ${if (ev) "전비" else "연비"}를 입력하면 정산안을 만들 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    BigButton("${if (ev) "전비" else "연비"} 입력", Modifier.fillMaxWidth(), enabled = canEdit) { vm.askEfficiency() }
                } else if (eff != null) Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (ev) "전비" else "연비"}  ${eff.efficiencyValue ?: eff.efficiencyKmPerUnit} ${eff.efficiencyUnit ?: if (ev) "km/kWh" else "km/L"}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(sourceKo(eff.efficiencySource), style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (canEdit) TextAction("바꾸기", Modifier.semantics { contentDescription = "${if (ev) "전비" else "연비"} 바꾸기" }) { vm.askEfficiency() }
                }
                Section("통행료 · 주차비") {
                    val items = sum.costItems
                    if (items.isEmpty()) Text("있으면 더해 주세요. 없으면 바로 정산안을 만들어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    items.forEach { it ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Column(Modifier.weight(1f)) {
                                Text("${kindKo(it.kind)}  ${krw(it.amountKrw)}", style = MaterialTheme.typography.titleMedium, color = Paper)
                                Text("${allocKo(it.allocation)} · ${sourceKo("MANUAL")}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            if (canEdit) TextAction("삭제", Modifier.semantics { contentDescription = "${kindKo(it.kind)} ${krw(it.amountKrw)} 삭제" }) { removing = it.id to "${kindKo(it.kind)} ${krw(it.amountKrw)}" }
                        }
                    }
                    if (canEdit && s.trip != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        GhostButton("+ 통행료") { adding = "TOLL" }
                        GhostButton("+ 주차비") { adding = "PARKING" }
                        GhostButton("+ 기타") { adding = "OTHER" }
                    } else if (!canEdit) Text("비용 수정은 정차 중에만 할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
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
                        sum.costRule?.let { r -> KeyValue(if (r.vehicleType == "EV") "전비" else "연비", if (r.efficiencyValue != null) "${r.efficiencyValue} ${r.efficiencyUnit.orEmpty()} · ${sourceKo(r.efficiencySource)}" else "도착 후 차량에서 확인") }
                        if (sum.dataMode == "DEMO") Text("가상 데이터로 계산한 연습용 결과예요.", color = CoralText, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        GhostButton("홈으로") { vm.go(Screen.HOME) }
                        GhostButton("이 운행 닫기", enabled = parked) { vm.finishTrip() }
                    }
                }
            }
            BigButton("정산안 만들기", Modifier.fillMaxWidth(), enabled = parked && !s.busy && s.trip?.state != "IN_PROGRESS") { vm.previewSettlement() }
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

/** Toll / parking / other, always with an explicit allocation. */
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
        // left: total and how it was computed
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("이동비 합계", style = MaterialTheme.typography.titleLarge, color = Muted)
            Text(krw(st.totalKrw), style = MaterialTheme.typography.displayLarge, color = MoaYellow)
            Text(st.equation, style = MaterialTheme.typography.titleMedium, color = Paper)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("추정 금액이에요 · 결제·송금은 없어요", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextAction("안내") { info = true }
            }
            st.warnings.forEach { Text("• ${it.messageKo}", style = MaterialTheme.typography.bodyMedium, color = CoralText) }
            Section("숫자의 출처") {
                val ev = s.summary?.costRule?.vehicleType == "EV"
                KeyValue("거리 ${kmLabel(st.distanceKm)} km", sourceKo(s.summary?.dataMode ?: s.dataMode))
                KeyValue(if (ev) "전비" else "연비", sourceKo(st.efficiencySource))
                KeyValue(if (ev) "전기요금" else "유가", sourceKo("MANUAL"))
                if (st.itemsTotalKrw > 0) KeyValue("통행료·주차비 ${krw(st.itemsTotalKrw)}", sourceKo("MANUAL"))
            }
            s.summary?.let { RideLanes(it, Modifier.padding(top = Space.s)) }
            ExpandAction(if (basis) "계산 근거 접기" else "구간별 계산 근거", basis) { basis = !basis }
            if (basis) Section {
                Text("소비량 ${st.consumption} ${st.consumptionUnit} · 이동 ${st.distanceKm} km · ${sourceKo(st.efficiencySource)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                st.segments.forEach {
                    Text("${it.startKm}–${it.endKm} km (${it.lengthKm} km) · ${krw(it.energyKrw)} · ${it.payers.size}명이 나눔", style = MaterialTheme.typography.bodyMedium, color = Paper)
                }
            }
        }
        // right: shares and approval
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
                            Text(sh.nickname + if (sh.isDriver) "  운전자" else if (sh.userId == s.userId) "  나" else "", style = MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1)
                            Text(
                                "연료 ${krw(sh.energyKrw)}" + sh.items.joinToString("") { " + ${it.label.ifBlank { "항목" }} ${krw(it.amountKrw)}" } + " · ${sh.ridddenText()}",
                                style = MaterialTheme.typography.bodySmall, color = Muted,
                            )
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
                BigButton("모아 만나러 가기", Modifier.fillMaxWidth()) { vm.finishTrip() }
            } else if (st.shares.any { it.approval == "DISPUTED" }) {
                Column(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("이의가 있어요", style = MaterialTheme.typography.titleLarge, color = CoralText, modifier = Modifier.asHeading())
                    Text("비용이나 연비를 고친 뒤 정산안을 다시 만들면, 모두에게 새 금액을 다시 물어봐요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                BigButton("비용 고치기", Modifier.fillMaxWidth(), enabled = parked && !s.busy) { vm.go(Screen.SUMMARY) }
            } else {
                // the driver proposed it, so no dispute option; approve or change costs
                if (mine != null && mine.approval == "PENDING") BigButton("내 몫 승인", Modifier.fillMaxWidth(), enabled = parked && !s.busy) { vm.decide(st.id, true) }
                else Text("다른 분들의 승인을 기다리고 있어요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
                TextAction("비용 고치기", leading = MoaIcons.Back) { vm.go(Screen.SUMMARY) }
            }
        }
    }
    if (info) InfoDialog("정산 안내", st.disclaimers) { info = false }
}

private fun io.moata.pleos.data.remote.ShareOut.ridddenText(): String = "${kmLabel(riddenKm)} km 탑승"

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
            Text("모아 정원", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
            if (ch == null) {
                Text(if (s.offline) "서버에 연결되면 모아를 만날 수 있어요." else "모아를 불러오는 중…", style = MaterialTheme.typography.bodyLarge, color = Muted)
                GhostButton("다시 불러오기") { vm.loadGarden() }
            } else {
                Section("함께 자라는 중") {
                    val span = ((ch.nextLevelXp ?: ch.xp) - ch.levelXpStart).coerceAtLeast(1)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { ((ch.xp - ch.levelXpStart).toFloat() / span).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), color = MoaYellow, trackColor = SurfaceHi,
                    )
                    Text("경험치 ${ch.xp}${ch.nextLevelXp?.let { " / $it" } ?: ""} · 함께 마친 이동 ${ch.completedTrips}번", style = MaterialTheme.typography.bodyMedium, color = Paper)
                    Text(ch.noteKo.ifBlank { "모아는 함께 정산을 마친 이동 기록으로만 자라요. 거리·속도와는 관계없어요." }, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Section("배지") {
                    if (ch.badges.isEmpty()) Text("아직 배지가 없어요. 함께 한 번 이동하면 첫 배지가 생겨요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    ch.badges.forEach { Text("${it.nameKo} — ${it.descriptionKo}", style = MaterialTheme.typography.bodyMedium, color = Paper) }
                }
                Section("이동 기록") {
                    if (s.history.isEmpty()) Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    s.history.take(5).forEach {
                        Text("${it.startedAt?.take(10).orEmpty()} · ${kmLabel(it.distanceKm)} km · ${it.totalKrw?.let(::krw) ?: "정산 전"} · ${it.participantCount}명 · ${sourceKo(it.dataMode)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
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
