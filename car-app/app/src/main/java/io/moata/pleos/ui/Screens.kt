package io.moata.pleos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.moata.pleos.BuildConfig
import io.moata.pleos.domain.Feature
import io.moata.pleos.domain.UxPolicy

private val ScreenPad = Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l)

@Composable
fun SignInScreen(s: UiState, vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    Row(ScreenPad, horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
        Moa(size = 300.dp, mood = MoaMood.HAPPY, animate = !s.restricted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("안녕하세요, 모아예요", style = MaterialTheme.typography.headlineLarge, color = Paper)
            Text("함께 탄 이동을 기록하고, 이동비를 공평하게 나눠요. 닉네임만 있으면 시작할 수 있어요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
            if (s.restricted) Text("주행 중에는 시작할 수 없어요. 정차 후 이용해 주세요.", color = CoralText, style = MaterialTheme.typography.bodyLarge)
            else {
                TextInput("닉네임", name, { name = it.take(20) }, Modifier.fillMaxWidth())
                Text("저장하는 정보는 닉네임과 동의 기록뿐이에요. 전화번호·실명·차량 번호는 저장하지 않아요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                BigButton("동의하고 시작하기", Modifier.fillMaxWidth(), enabled = !s.busy && name.isNotBlank()) { vm.signIn(name) }
            }
        }
    }
}

@Composable
fun HomeScreen(s: UiState, vm: AppViewModel) {
    val parked = UxPolicy.isAllowed(Feature.TRIP_SETUP, s.drivingState)
    val active = s.trip
    Row(ScreenPad, horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
        Moa(
            size = 360.dp,
            mood = s.character?.let { if (it.mood == "SLEEPY") MoaMood.SLEEPY else if (it.mood == "PROUD") MoaMood.PROUD else MoaMood.HAPPY } ?: MoaMood.HAPPY,
            animate = UxPolicy.isAllowed(Feature.MOA_ANIMATION, s.drivingState),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("오늘도 같이 타요", style = MaterialTheme.typography.headlineLarge, color = Paper)
            when {
                s.groupId == null -> {
                    Text("먼저 함께 탈 그룹을 만들어요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
                    HeroButton("그룹 만들기", enabled = parked) { vm.go(Screen.GROUPS) }
                }
                active != null -> {
                    Text("진행 중인 운행이 있어요 · ${stateKo(active.state)}", style = MaterialTheme.typography.titleMedium, color = TealText)
                    HeroButton("이어서 하기", enabled = parked || active.state == "IN_PROGRESS") {
                        vm.go(when (active.state) { "IN_PROGRESS" -> Screen.DRIVING; "ENDED", "REVIEW", "DISPUTED" -> Screen.SUMMARY; else -> Screen.INVITE })
                    }
                }
                else -> HeroButton("함께 타기", enabled = parked) { vm.startFlow() }
            }
            if (!parked) Text("정차 후에 시작할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                GhostButton(if (s.groupId == null) "그룹 목록" else "그룹 바꾸기", enabled = parked) { vm.go(Screen.GROUPS) }
                GhostButton("모아 정원", enabled = UxPolicy.isAllowed(Feature.REWARDS, s.drivingState) && s.groupId != null) { vm.loadGarden(); vm.go(Screen.GARDEN) }
                if (BuildConfig.DEMO_TOOLS) TextAction("개발자 도구") { vm.go(Screen.DEBUG) }
            }
        }
    }
}

@Composable
fun GroupsScreen(s: UiState, vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    Column(ScreenPad.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("그룹", style = MaterialTheme.typography.headlineMedium, color = Paper)
        s.groups.forEach { g ->
            Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(32.dp)).padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleLarge, color = Paper)
                    Text("멤버 ${g.members.count { it.status == "ACTIVE" }}명", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (g.id == s.groupId) Pill("선택됨", Ink, TealText) else BigButton("선택") { vm.selectGroup(g.id) }
            }
        }
        Section(if (s.groups.isEmpty()) "첫 그룹을 만들어요" else "새 그룹") {
            TextInput("그룹 이름 (예: 금요 팀 점심)", name, { name = it.take(30) }, Modifier.fillMaxWidth())
            BigButton("그룹 만들기", Modifier.fillMaxWidth(), enabled = !s.busy && name.isNotBlank()) { vm.createGroup(name) }
        }
        TextAction("돌아가기", leading = MoaIcons.Back) { vm.go(Screen.HOME) }
    }
}

private enum class Field { PRICE, EFFICIENCY }

@Composable
fun TripSetupScreen(s: UiState, vm: AppViewModel) {
    val f = s.form
    val ev = f.vehicleType == "EV"
    val editing = s.trip != null
    val manualEff = f.efficiencySource == "MANUAL"
    var focus by remember { mutableStateOf(Field.PRICE) }
    var advanced by remember { mutableStateOf(false) }
    val target = if (focus == Field.EFFICIENCY && manualEff) Field.EFFICIENCY else Field.PRICE
    val current = if (target == Field.PRICE) f.unitPrice else f.efficiencyValue
    val ready = f.unitPrice.toDoubleOrNull()?.let { it > 0 } == true && (!manualEff || f.efficiencyValue.toDoubleOrNull()?.let { it > 0 } == true)

    Row(ScreenPad, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(if (editing) "비용 규칙 바꾸기" else "출발 전에 하나만 알려주세요", style = MaterialTheme.typography.headlineMedium, color = Paper)
            Text(
                if (manualEff) "연료비를 나누려면 ${if (ev) "전기요금" else "유가"}와 ${if (ev) "전비" else "연비"}가 필요해요."
                else "연료비를 나누려면 ${if (ev) "전기요금" else "유가"}가 필요해요. 한 번 입력하면 기억해요. ${if (ev) "전비" else "연비"}는 도착 후 차량에서 읽고, 못 읽으면 그때 물어볼게요.",
                style = MaterialTheme.typography.bodyMedium, color = Muted,
            )
            ValueCard(if (ev) "전기요금" else "오늘 유가", f.unitPrice, if (ev) "원/kWh" else "원/L", target == Field.PRICE) { focus = Field.PRICE }
            if (manualEff) ValueCard(if (ev) "전비" else "연비", f.efficiencyValue, f.efficiencyUnit, target == Field.EFFICIENCY) { focus = Field.EFFICIENCY }
            ExpandAction(if (advanced) "나누는 방법·차량 설정 접기" else "나누는 방법·차량 설정", advanced) { advanced = !advanced }
            if (advanced) Section {
                if (!editing) {
                    Text("차량 종류", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Choice(listOf("ICE" to "내연기관", "EV" to "전기차"), f.vehicleType,
                        { vm.setForm(f.copy(vehicleType = it, efficiencyUnit = if (it == "EV") "km/kWh" else "km/L", unitPrice = "", efficiencyValue = "")) })
                    Text("주행거리", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Choice(buildList {
                        add("PLEOS" to "차량에서 읽기"); add("MANUAL" to "직접 입력")
                        if (BuildConfig.DEMO_TOOLS) add("DEMO" to "DEMO (가상)")
                    }, f.dataMode, { vm.setDataMode(it); vm.setForm(f.copy(dataMode = it)) })
                    if (f.dataMode == "DEMO") Text("DEMO는 가상 데이터예요. 화면 위쪽에 항상 표시돼요.", color = CoralText, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (ev) "전비" else "연비", style = MaterialTheme.typography.bodySmall, color = Muted)
                Choice(listOf("PLEOS" to "도착 후 차량에서 읽기", "MANUAL" to "지금 입력"), f.efficiencySource, { vm.setForm(f.copy(efficiencySource = it)) })
                if (manualEff) Choice(if (ev) listOf("km/kWh" to "km/kWh", "Wh/km" to "Wh/km") else listOf("km/L" to "km/L", "L/100km" to "L/100km"), f.efficiencyUnit, { vm.setForm(f.copy(efficiencyUnit = it)) })
                Text("운전자", style = MaterialTheme.typography.bodySmall, color = Muted)
                Choice(listOf(true to "함께 부담", false to "제외"), f.driverIncluded, { vm.setForm(f.copy(driverIncluded = it)) })
                Text("나누는 방법", style = MaterialTheme.typography.bodySmall, color = Muted)
                Choice(listOf(false to "탄 거리만큼", true to "똑같이"), f.equalSplit, { vm.setForm(f.copy(equalSplit = it)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                BigButton(if (editing) "저장" else "QR 만들기", enabled = ready && !s.busy && UxPolicy.isAllowed(Feature.TRIP_SETUP, s.drivingState)) {
                    if (editing) vm.updateRule(f) else vm.createTripAndInvite(f)
                }
                TextAction("돌아가기", leading = MoaIcons.Back) { vm.go(if (editing) Screen.INVITE else Screen.HOME) }
            }
        }
        NumberPad(
            current, { v -> vm.setForm(if (target == Field.PRICE) f.copy(unitPrice = v) else f.copy(efficiencyValue = v)) },
            Modifier.weight(0.85f).padding(top = Space.xl), decimal = target == Field.EFFICIENCY, maxLen = 7,
        )
    }
}

@Composable
fun InviteScreen(s: UiState, vm: AppViewModel) {
    val allowed = UxPolicy.isAllowed(Feature.QR_INVITE, s.drivingState)
    val people = s.summary?.participants.orEmpty().filter { !it.isDriver }
    val ready = people.any { it.memberStatus == "ACTIVE" }
    val notBoarded = people.filter { it.memberStatus == "ACTIVE" && !it.onBoard }
    var rules by remember { mutableStateOf(false) }
    var departing by remember { mutableStateOf<List<io.moata.pleos.data.remote.ParticipantOut>?>(null) }   // frozen when asked
    var cancelling by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf<io.moata.pleos.data.remote.ParticipantOut?>(null) }
    Row(ScreenPad, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        Column(Modifier.weight(0.95f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val url = s.joinUrl
            if (allowed && url != null) {
                val bmp = remember(url) { QrBitmap.create(url) }
                Image(bmp.asImageBitmap(), "초대 QR 코드", Modifier.size(440.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp))
                Spacer(Modifier.height(Space.m))
                Text("동승자 폰 카메라로 스캔해요", style = MaterialTheme.typography.titleLarge, color = Paper, textAlign = TextAlign.Center)
                TextAction("새 QR 만들기", enabled = !s.busy) { vm.makeInvite() }
            } else if (!allowed) {
                Moa(size = 200.dp, mood = MoaMood.SLEEPY)
                Text("QR은 정차 중에만 보여요.", style = MaterialTheme.typography.titleLarge, color = Paper)
            } else {
                Moa(size = 200.dp, mood = MoaMood.WORRIED)
                Text("QR이 아직 없어요.", style = MaterialTheme.typography.titleLarge, color = Paper)
                BigButton("QR 만들기", enabled = !s.busy) { vm.makeInvite() }
            }
        }
        Column(Modifier.weight(1.05f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("함께 탈 사람", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.weight(1f).asHeading())
                val waiting = people.count { it.memberStatus == "PENDING" }
                val active = people.count { it.memberStatus == "ACTIVE" }
                if (waiting > 0) Pill("승인 대기 ${waiting}명", Ink, MoaYellow, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else if (active > 0) Pill("탔어요 ${active - notBoarded.size}/${active}명", Ink, TealText, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (people.isEmpty()) {
                    Text("아직 아무도 없어요. QR을 스캔하면 여기에 나타나요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
                    listOf("동승자가 폰 카메라로 QR을 스캔해요", "여기에 이름이 뜨면 승인해요", "차에 타면 각자 폰에서 '탔어요'를 눌러요").forEachIndexed { i, step ->
                        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Ink,
                                modifier = Modifier.background(MoaYellow, RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 6.dp))
                            Text(step, style = MaterialTheme.typography.titleMedium, color = Paper)
                        }
                    }
                }
                else if (notBoarded.isNotEmpty()) Text("차에 타면 각자 폰에서 '탔어요'를 눌러요. 탄 곳부터 계산돼요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                people.forEach { p ->
                    Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(horizontal = Space.m, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(p.nickname, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Paper, maxLines = 1)
                        when (p.memberStatus) {
                            "PENDING" -> {
                                BigButton("승인", Modifier.semantics { contentDescription = "${p.nickname} 승인" }, enabled = allowed) { vm.decideMember(p.userId, true) }
                                GhostButton("거절", Modifier.semantics { contentDescription = "${p.nickname} 거절" }, enabled = allowed) { rejecting = p }
                            }
                            "ACTIVE" -> if (p.onBoard) Pill("탔어요", Ink, TealText, icon = MoaIcons.Check) else {
                                Pill("아직 안 탔어요", Paper, SurfaceHi)
                                // for a passenger without their phone at hand; recorded as "운전자가 기록"
                                TextAction("탔어요로 표시", Modifier.semantics { contentDescription = "${p.nickname} 탔어요로 표시" }, enabled = allowed && !s.busy) { vm.markBoarded(p.userId) }
                            }
                            else -> Pill(p.memberStatus, Paper, SurfaceHi)
                        }
                    }
                }
            }
            val r = s.summary?.costRule
            Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(28.dp)).padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ruleLine(r, s.form), style = MaterialTheme.typography.bodyMedium, color = Paper)
                    if (rules) Text("정산은 추정치예요. 결제·송금은 하지 않고, 모두가 확인해야 확정돼요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                TextAction("바꾸기", Modifier.semantics { contentDescription = "비용 규칙 바꾸기" }, enabled = allowed) { vm.go(Screen.TRIP_SETUP) }
            }
            BigButton("출발하기", Modifier.fillMaxWidth(), enabled = ready && allowed && !s.busy) { if (notBoarded.isEmpty()) vm.startTrip() else departing = notBoarded }
            if (!ready) Text("승인된 동승자가 1명 이상 있어야 출발할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                TextAction(if (rules) "안내 접기" else "정산 안내") { rules = !rules }
                TextAction("운행 취소", enabled = allowed) { cancelling = true }
            }
        }
    }
    if (!allowed) return   // nothing may cover the screen while driving
    departing?.let { asked ->   // snapshot, so the 4 s poll can't change the list under the driver's finger
        DepartureCheck(asked.map { it.nickname }, onAllAboard = { departing = null; vm.startTrip(boardFor = asked.map { it.userId }) },
            onWithout = { departing = null; vm.startTrip() }, onWait = { departing = null })
    }
    if (cancelling) ConfirmDialog("운행을 취소할까요?", "초대 QR이 더 이상 쓰이지 않고, 이 운행은 정산 없이 끝나요.", "운행 취소", "계속 준비",
        onConfirm = { cancelling = false; vm.cancelTrip() }, onCancel = { cancelling = false }, danger = true)
    rejecting?.let { p ->
        ConfirmDialog("${p.nickname}님 요청을 거절할까요?", "거절하면 되돌릴 수 없어요. 이 사람은 이번 운행에 탈 수 없어요.", "거절", "그대로 두기",
            onConfirm = { rejecting = null; vm.decideMember(p.userId, false) }, onCancel = { rejecting = null }, danger = true)
    }
}

/** Approved passengers who haven't tapped '탔어요'; ask so nobody silently drops off the bill. */
@Composable
private fun DepartureCheck(names: List<String>, onAllAboard: () -> Unit, onWithout: () -> Unit, onWait: () -> Unit) {
    val who = names.joinToString(", ") { "${it}님" }
    BigDialog(onWait) {
        Text("${who}도 탔나요?", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
        Text("아직 폰에서 '탔어요'를 누르지 않았어요. 탔다면 운전자가 대신 표시할 수 있어요. 기록에는 '운전자가 표시'로 남아요.", style = MaterialTheme.typography.bodyLarge, color = Muted)
        BigButton("모두 탔어요 · 출발", Modifier.fillMaxWidth(), onClick = onAllAboard)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            GhostButton("안 탄 사람 빼고 출발", Modifier.weight(1f), onClick = onWithout)
            GhostButton("기다릴게요", Modifier.weight(1f), onClick = onWait)
        }
    }
}

private fun ruleLine(r: io.moata.pleos.data.remote.CostRuleOut?, f: TripForm): String {
    val ev = (r?.vehicleType ?: f.vehicleType) == "EV"
    val price = r?.unitPriceKrw ?: f.unitPrice
    val driver = r?.driverIncluded ?: f.driverIncluded
    val equal = (r?.energyPolicy ?: if (f.equalSplit) "EQUAL_SPLIT" else "SEGMENT_SHARED") == "EQUAL_SPLIT"
    val eff = if (r?.efficiencyValue != null) "${if (ev) "전비" else "연비"} ${r.efficiencyValue} ${r.efficiencyUnit.orEmpty()}" else "${if (ev) "전비" else "연비"}는 도착 후 확인"
    return "${if (ev) "전기요금" else "유가"} ${grouped(price.toString().removeSuffix(".0"))}원/${if (ev) "kWh" else "L"}(MANUAL) · $eff · ${if (driver) "운전자도 부담" else "운전자 제외"} · ${if (equal) "똑같이" else "탄 거리만큼"}"
}

@Composable
fun DrivingScreen(s: UiState, vm: AppViewModel) {
    val t = s.trip
    val inProgress = t?.state == "IN_PROGRESS"
    val onBoard = s.summary?.participants?.filter { !it.isDriver && it.onBoard }.orEmpty()
    val live = s.live
    val km = if (inProgress && t?.odometerStartKm != null && live?.usable == true) (live.value!! - t.odometerStartKm) else null
    var confirmEnd by remember { mutableStateOf(false) }
    Column(ScreenPad, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // never animated on the driving screen
        Moa(size = 180.dp, mood = if (inProgress) MoaMood.HAPPY else MoaMood.SLEEPY, animate = false)
        if (!inProgress) {
            Text(if (s.restricted) "정차 후에 이용할 수 있어요" else "대기 중", style = MaterialTheme.typography.headlineLarge, color = Paper, textAlign = TextAlign.Center)
        } else {
            Text(
                when { km != null -> "%.1f".format(km); live == null -> "읽는 중…"; else -> "읽을 수 없어요" },
                style = MaterialTheme.typography.displayLarge, color = if (km != null) Paper else CoralText, textAlign = TextAlign.Center,
            )
            Text(if (km != null) "km · 함께 이동한 거리 · ${t?.dataMode.orEmpty()}" else "주행거리", style = MaterialTheme.typography.titleLarge, color = Muted)
            Spacer(Modifier.height(Space.m))
            Text(
                if (onBoard.isEmpty()) "아직 탄 사람이 없어요" else "함께 탄 사람  " + onBoard.joinToString(" · ") { it.nickname },
                style = MaterialTheme.typography.headlineMedium, color = MoaYellow, textAlign = TextAlign.Center,
            )
            if (live != null && !live.usable) Text("차량에서 주행거리를 받지 못하고 있어요. 기록은 계속 시도해요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (s.offline) Text("서버와 연결이 끊겼어요. 기록은 차량에 안전하게 저장 중이에요.", style = MaterialTheme.typography.bodyMedium, color = MoaYellow)
        }
        Spacer(Modifier.height(Space.l))
        if (!s.restricted && inProgress) HeroButton("도착했어요", Modifier.fillMaxWidth(0.7f), enabled = !s.busy) { confirmEnd = true }
        else if (!s.restricted) GhostButton("홈으로") { vm.go(Screen.HOME) }
        else if (inProgress) Text("안전을 위해 운전 중에는 조작할 수 없어요.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
    if (confirmEnd && !s.restricted) ConfirmDialog(
        "도착했어요?", "운행을 끝내고 지금 주행거리를 기록해요. 이어서 통행료를 더하고 정산안을 만들 수 있어요.", "네, 도착했어요", "계속 운행",
        onConfirm = { confirmEnd = false; vm.endTrip() }, onCancel = { confirmEnd = false },
    )
}

fun stateKo(state: String): String = when (state) {
    "INVITING" -> "초대 중"; "READY" -> "출발 준비"; "IN_PROGRESS" -> "운행 중"; "ENDED" -> "도착"; "REVIEW" -> "정산 확인 중"
    "CONFIRMED" -> "확정"; "DISPUTED" -> "이의 제기됨"; "CANCELLED" -> "취소됨"; else -> state
}
