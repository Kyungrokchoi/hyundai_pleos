package io.moata.pleos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.moata.pleos.BuildConfig
import io.moata.pleos.domain.DrivingState

/** App shell: status bar, message bar, and the single screen the driving state allows. */
@Composable
fun MoataRoot(vm: AppViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    MoataTheme {
        // driving or unknown: only the read-only driving view is reachable
        val screen = if (s.restricted && s.signedIn) Screen.DRIVING else s.screen
        BackHandler(enabled = !s.restricted && screen != Screen.HOME && screen != Screen.SIGN_IN) {
            vm.go(if (screen == Screen.SETTLEMENT) Screen.SUMMARY else Screen.HOME)
        }
        Column(Modifier.fillMaxSize().background(Ink)) {
            StatusBar(s)
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MoaYellow, trackColor = SurfaceHi)
            s.msg?.let { MessageBar(it, s.restricted, vm::dismissMsg) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    Screen.SIGN_IN -> SignInScreen(s, vm)
                    Screen.HOME -> HomeScreen(s, vm)
                    Screen.GROUPS -> GroupsScreen(s, vm)
                    Screen.TRIP_SETUP -> TripSetupScreen(s, vm)
                    Screen.INVITE -> InviteScreen(s, vm)
                    Screen.DRIVING -> DrivingScreen(s, vm)
                    Screen.SUMMARY -> SummaryScreen(s, vm)
                    Screen.SETTLEMENT -> SettlementScreen(s, vm)
                    Screen.GARDEN -> GardenScreen(s, vm)
                    Screen.DEBUG -> if (BuildConfig.DEMO_TOOLS) DebugScreen(s, vm) else HomeScreen(s, vm)
                }
            }
        }
        // dialogs only while parked; nothing may cover the driving view
        s.manualPrompt?.takeIf { !s.restricted }?.let { ManualDialog(it, s.trip?.vehicleType == "EV", vm) }
    }
}

@Composable
private fun StatusBar(s: UiState) {
    val mode = s.trip?.dataMode ?: s.dataMode
    Column {
        Row(
            Modifier.fillMaxWidth().background(Surface1).padding(horizontal = Space.l, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("모아타", style = MaterialTheme.typography.titleLarge, color = MoaYellow)
            Text(s.groupName?.let { "· $it" }.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Muted, modifier = Modifier.weight(1f), maxLines = 1)
            // session data source; each number also shows its own source where displayed
            when (mode) {
                "PLEOS" -> Pill("PLEOS · 차량 데이터", Ink, RouteTeal)
                "MANUAL" -> Pill("MANUAL · 직접 입력", Ink, Muted)
                "DEMO" -> Pill("DEMO · 가상", Ink, CoralText)
            }
            when {   // only sync trouble gets a chip; a few pending events is normal
                s.offline -> Pill("오프라인 · 기록 ${s.pending}건 보관 중", Ink, MoaYellow)
                s.failed > 0 -> Pill("보내지 못한 기록 ${s.failed}건", Ink, CoralText)
            }
            val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }   // TalkBack hears the switch to restricted mode
            when (s.drivingState) {
                DrivingState.PARKED -> Pill("정차 중", Ink, TealText, announce)
                DrivingState.DRIVING -> Pill("주행 중 · 기능 제한", Ink, CoralText, announce)
                DrivingState.UNKNOWN -> Pill("확인 중 · 기능 제한", Ink, CoralText, announce)
            }
        }
        if (mode == "DEMO") {   // simulated values must never pass as car measurements
            Text(
                "DEMO / 가상 데이터 — 실제 차량 측정값이 아닙니다",
                color = Ink, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(CoralText).padding(horizontal = Space.l, vertical = 8.dp),
            )
        }
    }
}

/** [Msg.detail] never reaches the driver. No buttons while driving, so a retry can't change data. */
@Composable
private fun MessageBar(m: Msg, restricted: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (m.error) CoralText else TealText).padding(horizontal = Space.l, vertical = Space.s)
            .semantics { liveRegion = if (m.error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.text, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.labelMedium)
        if (!restricted) {
            m.retry?.let { r -> GhostButton(m.retryLabel, content = Ink, outline = Ink, onClick = { onDismiss(); r() }) }
            GhostButton("닫기", content = Ink, outline = Ink, onClick = onDismiss)
        }
    }
}

/** Manual odometer / fuel economy entry; defaults to km/L rather than the SDK's L/100km. */
@Composable
private fun ManualDialog(p: ManualPrompt, ev: Boolean, vm: AppViewModel) {
    var value by remember(p) { mutableStateOf("") }
    var unit by remember(p) { mutableStateOf(if (ev) "km/kWh" else "km/L") }
    val economy = p.purpose == ManualPurpose.ECONOMY
    BigDialog(vm::dismissManual) {
        Text(if (economy) (if (ev) "전비를 알려주세요" else "연비를 알려주세요") else "주행거리를 알려주세요", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
        Text(p.reason, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                ValueCard(if (economy) "직접 입력" else "계기판 주행거리", value, if (economy) unit else "km", true)
                if (economy) Choice(if (ev) listOf("km/kWh" to "km/kWh", "Wh/km" to "Wh/km") else listOf("km/L" to "km/L", "L/100km" to "L/100km"), unit, { unit = it })
                Text("직접 입력한 값은 'MANUAL · 직접 입력한 값'으로 표시되고, 차량 측정값처럼 보이지 않아요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            NumberPad(value, { value = it }, Modifier.weight(1f), decimal = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            GhostButton("취소", Modifier.weight(1f), onClick = vm::dismissManual)
            BigButton("입력 완료", Modifier.weight(1f), enabled = value.toDoubleOrNull()?.let { it > 0 } == true) { vm.submitManual(p.purpose, value, unit) }
        }
    }
}
