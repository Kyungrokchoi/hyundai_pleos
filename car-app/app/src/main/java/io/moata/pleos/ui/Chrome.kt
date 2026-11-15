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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import io.moata.pleos.domain.HeaderRules
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.moata.pleos.BuildConfig
import io.moata.pleos.domain.DrivingState


@Composable
fun MoataRoot(vm: AppViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    MoataTheme {

        val screen = if (s.restricted && s.signedIn) Screen.DRIVING else s.screen
        BackHandler(enabled = !s.restricted && screen != Screen.HOME && screen != Screen.SIGN_IN) {
            if (screen == Screen.SETTLEMENT) vm.go(Screen.SUMMARY) else vm.goHome()
        }
        Column(Modifier.fillMaxSize().background(Ink)) {
            StatusBar(s) { vm.goHome() }
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
                    Screen.DEBUG -> if (BuildConfig.DEMO_TOOLS && s.isAdmin) DebugScreen(s, vm) else HomeScreen(s, vm)
                }
            }
        }

        s.manualPrompt?.takeIf { !s.restricted }?.let { ManualDialog(it, vm) }
    }
}


@Composable
private fun AppTitle(groupName: String?, canGoHome: Boolean, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val suffix = HeaderRules.groupSuffix(groupName)
    val title = buildAnnotatedString {
        withStyle(SpanStyle(color = MoaYellow)) { append("모아타") }
        suffix?.let { withStyle(SpanStyle(color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Medium)) { append(it) } }
    }
    val base = modifier.heightIn(min = Space.touch)
    Row(
        if (canGoHome) base.clickable(role = Role.Button, onClickLabel = "홈으로", onClick = onHome)
            .semantics(mergeDescendants = true) { contentDescription = "모아타${suffix.orEmpty()}, 홈으로" }
        else base,
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun StatusBar(s: UiState, onHome: () -> Unit) {
    val mode = s.trip?.dataMode ?: s.dataMode
    Column {
        Row(
            Modifier.fillMaxWidth().background(Surface1).padding(horizontal = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTitle(s.groupName, canGoHome = s.signedIn && !s.restricted && s.screen != Screen.HOME && s.screen != Screen.SIGN_IN, onHome = onHome, modifier = Modifier.weight(1f))

            when (mode) {
                "MANUAL" -> Pill("MANUAL · 직접 입력", Ink, Muted)
                "DEMO" -> Pill("DEMO · 가상", Ink, CoralText)
            }
            when {
                s.offline -> Pill("오프라인 · 기록 ${s.pending}건 보관 중", Ink, MoaYellow)
                s.failed > 0 -> Pill("보내지 못한 기록 ${s.failed}건", Ink, CoralText)
            }
            val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            when (s.drivingState) {
                DrivingState.PARKED -> Pill("정차 중", Ink, TealText, announce)
                DrivingState.DRIVING -> Pill("주행 중", Ink, CoralText, announce)
                DrivingState.UNKNOWN -> Pill("확인 중", Ink, CoralText, announce)
            }
        }
        if (mode == "DEMO") {
            Text(
                "DEMO / 가상 데이터 — 실제 차량 측정값이 아닙니다",
                color = Ink, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(CoralText).padding(horizontal = Space.l, vertical = 8.dp),
            )
        }
    }
}


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


@Composable
private fun ManualDialog(p: ManualPrompt, vm: AppViewModel) {
    var value by remember(p) { mutableStateOf("") }
    BigDialog(vm::dismissManual) {
        Text("주행거리를 알려주세요", style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.asHeading())
        Text(p.reason, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                ValueCard("계기판 주행거리", value, "km", true)
                Text("직접 입력한 값은 'MANUAL · 직접 입력한 값'으로 표시되고, 차량 측정값처럼 보이지 않아요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            NumberPad(value, { value = it }, Modifier.weight(1f), decimal = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            GhostButton("취소", Modifier.weight(1f), onClick = vm::dismissManual)
            BigButton("입력 완료", Modifier.weight(1f), enabled = value.toDoubleOrNull()?.let { it > 0 } == true) { vm.submitManual(p.purpose, value) }
        }
    }
}
