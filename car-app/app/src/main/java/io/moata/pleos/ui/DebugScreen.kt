package io.moata.pleos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.moata.pleos.BuildConfig
import io.moata.pleos.domain.DrivingState

/** Developer controls; only routed to when BuildConfig.DEMO_TOOLS is set. */
@Composable
fun DebugScreen(s: UiState, vm: AppViewModel) {
    val probe by vm.probeResult.collectAsStateWithLifecycle()
    val running by vm.probeRunning.collectAsStateWithLifecycle()
    Column(Modifier.padding(Space.l).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("개발자 도구 (DEBUG 빌드 전용)", style = MaterialTheme.typography.headlineMedium, color = CoralText)
        Section("백엔드") {
            KeyValue("API", BuildConfig.API_BASE_URL); KeyValue("웹(QR)", BuildConfig.WEB_BASE_URL)
            KeyValue("동기화 대기/오류", "${s.pending} / ${s.failed}")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                GhostButton("오류 이벤트 다시 보내기", enabled = s.failed > 0) { vm.requeueFailed() }
                GhostButton("로그아웃(세션 삭제)") { vm.signOut() }
            }
        }
        Section("기본 데이터 모드") {
            Choice(listOf("PLEOS" to "PLEOS", "MANUAL" to "직접 입력", "DEMO" to "DEMO"), s.dataMode, { vm.setDataMode(it) })
            Text("DEMO는 가상 데이터이며 자동으로 선택되지 않아요. SDK 실패 시에도 DEMO로 바뀌지 않아요.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Section("Pleos SDK 프로브 (AND-02~09)") {
            Text("initialize → 능력 확인 → Odometer/Fuel/EvBattery 읽기 → 리스너 10초 → release. 결과는 filesDir/probe-result.json 과 logcat(MOATA_PROBE).", style = MaterialTheme.typography.bodySmall, color = Muted)
            BigButton(if (running) "실행 중…" else "프로브 실행", enabled = !running) { vm.runProbe(10) }
            probe?.let { r ->
                Text("판정: ${r.verdict}", style = MaterialTheme.typography.titleMedium, color = Paper)
                Text("initialize: ${r.initialize?.outcome} ${r.initialize?.error.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = Muted)
                r.steps.forEach { st ->
                    Text("${st.id}: ${st.outcome}${st.value?.let { " = $it ${st.documentedUnit.orEmpty()}" } ?: ""}${if (st.valueIsNull) " (null)" else ""}${st.error?.let { " — $it".take(120) } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = if (st.outcome == "COMPLETE") TealText else CoralText)
                }
                r.listener?.let { Text("리스너 업데이트 ${it.updates.size}건, 실패 ${it.failures.size}건, 해제 ${it.unregistered}", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
        }
        Section("주행 상태 강제 (비-차량 이미지 시험용)") {
            Text("현재: ${s.drivingState}. 차량이 알려 주는 값이 아니라 개발용 덮어쓰기예요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                GhostButton("정차") { vm.setUxOverride(DrivingState.PARKED) }
                GhostButton("주행") { vm.setUxOverride(DrivingState.DRIVING) }
                GhostButton("해제") { vm.setUxOverride(null) }
            }
        }
        Section("DEMO 주행 시뮬레이션") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                GhostButton("+5 km") { vm.demoAdvance(5.0) }
                GhostButton("+10 km") { vm.demoAdvance(10.0) }
                GhostButton("처음으로") { vm.demoReset() }
            }
        }
        GhostButton("돌아가기") { vm.go(Screen.HOME) }
    }
}
