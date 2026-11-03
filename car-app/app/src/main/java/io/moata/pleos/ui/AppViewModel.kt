package io.moata.pleos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.moata.pleos.AppContainer
import io.moata.pleos.BuildConfig
import io.moata.pleos.core.vehicle.DataSource
import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.SampleRules
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.local.LocalGroup
import io.moata.pleos.data.local.LocalTrip
import io.moata.pleos.data.remote.ApiJson
import io.moata.pleos.data.remote.ApiResult
import io.moata.pleos.data.remote.ApprovalIn
import io.moata.pleos.data.remote.AuthOut
import io.moata.pleos.data.remote.CharacterOut
import io.moata.pleos.data.remote.CostItemIn
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.CouponOut
import io.moata.pleos.data.remote.DrainReport
import io.moata.pleos.data.remote.GroupCreateIn
import io.moata.pleos.data.remote.GroupOut
import io.moata.pleos.data.remote.GuestAuthIn
import io.moata.pleos.data.remote.HistoryItem
import io.moata.pleos.data.remote.InviteCreateIn
import io.moata.pleos.data.remote.InviteOut
import io.moata.pleos.data.remote.MemberDecisionIn
import io.moata.pleos.data.remote.SettlementOut
import io.moata.pleos.data.remote.TripSummary
import io.moata.pleos.domain.DrivingState
import io.moata.pleos.domain.Feature
import io.moata.pleos.domain.UxPolicy
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

enum class Screen { SIGN_IN, HOME, GROUPS, TRIP_SETUP, INVITE, DRIVING, SUMMARY, SETTLEMENT, GARDEN, DEBUG }

enum class ManualPurpose { START_ODOMETER, END_ODOMETER, ECONOMY }

/** `detail` goes to the log only and is never shown. */
data class Msg(val text: String, val detail: String? = null, val error: Boolean = true, val retry: (() -> Unit)? = null, val retryLabel: String = "다시 시도")
data class ManualPrompt(val purpose: ManualPurpose, val reason: String)

data class TripForm(
    val vehicleType: String = "ICE", val dataMode: String = "PLEOS",
    val efficiencySource: String = "PLEOS",      // PLEOS = read from the car at trip end, MANUAL = typed
    val efficiencyValue: String = "", val efficiencyUnit: String = "km/L",
    val unitPrice: String = "", val driverIncluded: Boolean = true, val equalSplit: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val signedIn: Boolean = false, val nickname: String? = null, val userId: String? = null,
    val groups: List<GroupOut> = emptyList(), val groupId: String? = null,
    val trip: LocalTrip? = null, val summary: TripSummary? = null,
    val invite: InviteOut? = null, val joinUrl: String? = null,
    val character: CharacterOut? = null, val history: List<HistoryItem> = emptyList(), val coupons: List<CouponOut> = emptyList(),
    val busy: Boolean = false, val msg: Msg? = null,
    val offline: Boolean = false, val pending: Int = 0, val failed: Int = 0,
    val dataMode: String = "PLEOS", val live: VehicleSample? = null, val drivingState: DrivingState = DrivingState.UNKNOWN,
    val manualPrompt: ManualPrompt? = null,
    val form: TripForm = TripForm(),
) {
    val groupName: String? get() = groups.firstOrNull { it.id == groupId }?.name
    val restricted: Boolean get() = UxPolicy.isRestricted(drivingState)
}

class AppViewModel(private val c: AppContainer) : ViewModel() {
    private fun savedForm() = TripForm(
        vehicleType = c.session.lastVehicleType, dataMode = c.session.dataMode, efficiencySource = c.session.lastEfficiencySource,
        efficiencyValue = c.session.lastEfficiencyValue, efficiencyUnit = c.session.lastEfficiencyUnit,
        unitPrice = c.session.lastUnitPrice, driverIncluded = c.session.lastDriverIncluded, equalSplit = c.session.lastEqualSplit,
    )

    private fun saveForm(f: TripForm) {
        c.session.lastVehicleType = f.vehicleType; c.session.lastUnitPrice = f.unitPrice
        c.session.lastDriverIncluded = f.driverIncluded; c.session.lastEqualSplit = f.equalSplit
        c.session.lastEfficiencySource = f.efficiencySource; c.session.lastEfficiencyUnit = f.efficiencyUnit
        c.session.lastEfficiencyValue = if (f.efficiencySource == "MANUAL") f.efficiencyValue else ""
    }

    private val _ui = MutableStateFlow(
        UiState(signedIn = c.session.signedIn, nickname = c.session.nickname, userId = c.session.userId,
            groupId = c.session.selectedGroupId, dataMode = c.session.dataMode, form = savedForm(),
            screen = if (c.session.signedIn) Screen.HOME else Screen.SIGN_IN),
    )
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch { c.drivingState.collect { s -> _ui.update { it.copy(drivingState = s) } } }
        viewModelScope.launch { c.db.outbox().pendingCount().collect { n -> _ui.update { it.copy(pending = n) } } }
        viewModelScope.launch { c.db.outbox().failedCount().collect { n -> _ui.update { it.copy(failed = n) } } }
        viewModelScope.launch { c.recorder.live.collect { s -> _ui.update { it.copy(live = s) } } }
        viewModelScope.launch {
            c.lastDrain.collect { d -> if (d != null) _ui.update { it.copy(offline = d.stoppedBecause == DrainReport.Stop.OFFLINE) } }
        }
        viewModelScope.launch { recover(); if (c.session.signedIn) { refreshGroups(); } }
        viewModelScope.launch { // passengers join, board and approve on their phones
            while (true) {
                delay(4_000)
                val s = _ui.value
                if (s.signedIn && s.trip != null && s.screen in POLLED) refreshSummary()
            }
        }
    }

    /** After process death: reload the open trip and resume recording. Never creates a trip. */
    private suspend fun recover() {
        val t = c.repo.open() ?: return
        _ui.update { it.copy(trip = t, dataMode = t.dataMode) }
        if (t.state == "IN_PROGRESS") {
            val src = c.source(t.dataMode)
            if (src.connect().isSuccess) c.recorder.start(t.id, src)
            _ui.update { it.copy(screen = Screen.DRIVING) }
        } else if (t.state == "INVITING" || t.state == "READY") {
            _ui.update { it.copy(screen = Screen.INVITE) }
        } else {   // ENDED / REVIEW / DISPUTED
            _ui.update { it.copy(screen = Screen.SUMMARY) }
            refreshSummary()
        }
    }

    fun go(s: Screen) = _ui.update { it.copy(screen = s, msg = null) }
    fun dismissMsg() = _ui.update { it.copy(msg = null) }
    fun setForm(f: TripForm) = _ui.update { it.copy(form = f) }
    fun setDataMode(m: String) {
        if (m == "DEMO" && !BuildConfig.DEMO_TOOLS) return          // DEMO exists only in debug builds
        c.session.dataMode = m
        _ui.update { it.copy(dataMode = m, form = it.form.copy(dataMode = m)) }
    }
    fun setUxOverride(s: DrivingState?) { if (BuildConfig.DEMO_TOOLS) c.uxOverride.value = s }
    fun demoAdvance(km: Double) = c.demo.advance(km)
    fun demoReset() = c.demo.reset()
    fun runProbe(listenSec: Int) = c.runProbe(listenSec)
    val probeResult get() = c.probeResult
    val probeRunning get() = c.probeRunning
    fun requeueFailed(): Job = viewModelScope.launch { c.db.outbox().requeueFailed(); sync() }
    fun signOut() { c.session.clear(); _ui.update { UiState(screen = Screen.SIGN_IN, drivingState = it.drivingState) } }

    private fun busy(b: Boolean) = _ui.update { it.copy(busy = b) }
    private fun error(text: String, detail: String? = null, retry: (() -> Unit)? = null) {
        if (detail != null) android.util.Log.w("MOATA", "$text | $detail")   // technical detail stays in the log
        _ui.update { it.copy(busy = false, msg = Msg(text, detail, true, retry)) }
    }

    /**
     * Checked in every data-changing action, not just the screens: a retry, queued tap or stale dialog must not
     * change costs, members or the settlement while the car moves.
     */
    private fun parked(): Boolean = !_ui.value.restricted

    /** The first attempt already landed and the server reports it with one of these codes; treat as success. */
    private fun <T> alreadyDone(r: ApiResult<T>, vararg codes: String): Boolean = r is ApiResult.Rejected && r.errorCode in codes

    private fun <T> unwrap(r: ApiResult<T>, retry: (() -> Unit)? = null): T? = when (r) {
        is ApiResult.Ok -> { _ui.update { it.copy(offline = false) }; r.value }
        is ApiResult.Rejected -> { error(r.messageKo, "${r.code} ${r.errorCode.orEmpty()}".trim(), retry); null }
        is ApiResult.Offline -> {
            android.util.Log.w("MOATA", "offline: ${r.reason}")
            _ui.update { it.copy(offline = true, busy = false, msg = Msg("서버에 연결할 수 없어요. 기록은 차량에 저장돼 있어요.", r.reason, true, retry)) }; null
        }
        ApiResult.Unauthorized -> { _ui.update { it.copy(busy = false, msg = Msg("로그인이 만료되었어요. 닉네임으로 다시 시작해 주세요.", null, true, { signOut() }, "다시 로그인")) }; null }
    }

    fun signIn(nickname: String): Job = viewModelScope.launch {
        val name = nickname.trim()
        if (name.isEmpty()) { error("닉네임을 입력해 주세요."); return@launch }
        busy(true)
        val out = unwrap(c.api.post<GuestAuthIn, AuthOut>("/auth/guest", GuestAuthIn(name, CONSENT_VERSION))) { signIn(nickname) } ?: return@launch
        c.session.token = out.token; c.session.userId = out.userId; c.session.nickname = out.nickname
        _ui.update { it.copy(signedIn = true, nickname = out.nickname, userId = out.userId, busy = false, screen = Screen.HOME) }
        refreshGroups()
    }

    fun refreshGroups(): Job = viewModelScope.launch {
        val list = unwrap(c.api.get<List<GroupOut>>("/groups")) { refreshGroups() }
        if (list == null) { // offline: fall back to cached names
            val cached = c.db.trips().groups()
            _ui.update { it.copy(groups = cached.map { g -> GroupOut(g.id, g.name, "", "") }) }
            return@launch
        }
        c.db.trips().upsertGroups(list.map { LocalGroup(it.id, it.name) })
        _ui.update { s -> s.copy(groups = list, groupId = s.groupId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id) }
        _ui.value.groupId?.let { c.session.selectedGroupId = it }
    }

    /** The id is made once per tap and reused on retry, so a timed-out reply can't create two groups. */
    fun createGroup(name: String, id: String = UUID.randomUUID().toString()): Job = viewModelScope.launch {
        if (name.isBlank()) { error("그룹 이름을 입력해 주세요."); return@launch }
        busy(true)
        unwrap(c.api.post<GroupCreateIn, GroupOut>("/groups", GroupCreateIn(id, name.trim()))) { createGroup(name, id) } ?: return@launch
        c.session.selectedGroupId = id
        _ui.update { it.copy(groupId = id, busy = false, screen = Screen.HOME) }
        refreshGroups()
    }

    fun selectGroup(id: String) { c.session.selectedGroupId = id; _ui.update { it.copy(groupId = id, screen = Screen.HOME) } }

    private fun ruleFrom(f: TripForm, mode: String) = CostRuleIn(
        vehicleType = f.vehicleType,
        efficiencyValue = if (f.efficiencySource == "MANUAL") f.efficiencyValue else null,
        efficiencyUnit = if (f.efficiencySource == "MANUAL") f.efficiencyUnit else null,
        efficiencySource = if (f.efficiencySource == "MANUAL") "MANUAL" else if (mode == "DEMO") "DEMO" else "PLEOS",
        unitPriceKrw = f.unitPrice, driverIncluded = f.driverIncluded,
        energyPolicy = if (f.equalSplit) "EQUAL_SPLIT" else "SEGMENT_SHARED",
    )

    private fun invalidForm(f: TripForm): String? = when {
        f.unitPrice.toDoubleOrNull()?.let { it > 0 } != true -> if (f.vehicleType == "EV") "전기요금(원/kWh)을 입력해 주세요." else "유가(원/L)를 입력해 주세요."
        f.efficiencySource == "MANUAL" && f.efficiencyValue.toDoubleOrNull()?.let { it > 0 } != true -> "연비/전비를 0보다 큰 숫자로 입력해 주세요."
        else -> null
    }

    /** "함께 타기": one tap when the last fuel price is remembered, otherwise ask for it. */
    fun startFlow() { if (invalidForm(_ui.value.form) == null) createTripAndInvite(_ui.value.form) else go(Screen.TRIP_SETUP) }

    fun createTripAndInvite(f: TripForm): Job = viewModelScope.launch {
        val s = _ui.value
        val gid = s.groupId ?: run { error("먼저 그룹을 선택해 주세요."); return@launch }
        if (!UxPolicy.isAllowed(Feature.TRIP_SETUP, s.drivingState)) return@launch
        invalidForm(f)?.let { error(it); return@launch }
        busy(true)
        saveForm(f)
        val mode = f.dataMode
        val trip = c.repo.createTrip(UUID.randomUUID().toString(), gid, mode, f.vehicleType, ruleFrom(f, mode))
        _ui.update { it.copy(trip = trip, dataMode = trip.dataMode) }
        makeInvite(trip)
    }

    fun updateRule(f: TripForm): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        invalidForm(f)?.let { error(it); return@launch }
        busy(true)
        c.flush()
        unwrap(c.api.call<TripSummary>("PUT", "/trips/${t.id}/cost-rule", ApiJson.encodeToString(ruleFrom(f, t.dataMode)))) { updateRule(f) } ?: return@launch
        saveForm(f); busy(false); fetchSummary()
        _ui.update { it.copy(screen = Screen.INVITE) }
    }

    fun makeInvite(trip: LocalTrip? = _ui.value.trip): Job = viewModelScope.launch {
        val t = trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val drain = c.flush()                                         // the server must know the trip before it can invite to it
        if (drain.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 연결되면 다시 시도해 주세요.", null) { makeInvite(t) }; return@launch }
        val inv = unwrap(c.api.post<InviteCreateIn, InviteOut>("/groups/${t.groupId}/invites", InviteCreateIn(tripId = t.id))) { makeInvite(t) } ?: return@launch
        _ui.update { it.copy(invite = inv, joinUrl = BuildConfig.WEB_BASE_URL.trimEnd('/') + inv.joinPath, busy = false, screen = Screen.INVITE) }
        refreshSummary()
    }

    fun decideMember(userId: String, approve: Boolean): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val r = c.api.post<MemberDecisionIn, io.moata.pleos.data.remote.MemberOut>("/groups/${t.groupId}/members/$userId/decision", MemberDecisionIn(if (approve) "APPROVE" else "REJECT"))
        if (!alreadyDone(r, "MEMBER_NOT_PENDING")) unwrap(r) { decideMember(userId, approve) } ?: return@launch
        busy(false); refreshSummary()
    }

    /** Driver-confirmed boarding; the server audits it as by_driver. */
    fun markBoarded(userId: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.repo.board(t.id, userId, alight = false); sync(); delay(300); refreshSummary()
    }

    fun cancelTrip(): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.recorder.stop(); c.repo.cancel(t.id); sync()
        _ui.update { it.copy(trip = null, summary = null, invite = null, joinUrl = null, screen = Screen.HOME) }
    }

    fun refreshSummary(): Job = viewModelScope.launch { fetchSummary() }

    /** For callers that must decide on fresh server state. */
    private suspend fun fetchSummary(): TripSummary? {
        val t = _ui.value.trip ?: return null
        return when (val r = c.api.get<TripSummary>("/trips/${t.id}/summary")) {
            is ApiResult.Ok -> { _ui.update { it.copy(summary = r.value, offline = false) }; r.value }
            is ApiResult.Offline -> { _ui.update { it.copy(offline = true) }; null }
            is ApiResult.Rejected -> { if (r.code != 404) _ui.update { it.copy(msg = Msg(r.messageKo, "${r.code}")) }; null }
            ApiResult.Unauthorized -> { unwrap(r); null }
        }
    }

    private fun sync(): Job = viewModelScope.launch { c.flush() }

    /** [boardFor]: approved passengers the driver confirmed in the car without them tapping '탔어요'. */
    fun startTrip(boardFor: List<String> = emptyList()): Job = viewModelScope.launch {
        val s = _ui.value
        val t = s.trip ?: return@launch
        if (!UxPolicy.isAllowed(Feature.TRIP_START_END, s.drivingState)) return@launch
        busy(true)
        boardFor.forEach { c.repo.board(t.id, it, alight = false) }     // queued before ready/start, so they start at the first snapshot
        if (t.state == "INVITING") {                                   // READY needs the server's validation (rules + >=1 passenger)
            val d = c.flush()
            if (d.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 연결 후 다시 시도해 주세요.", null) { startTrip() }; return@launch }
            unwrap(c.api.call<Unit>("POST", "/trips/${t.id}/ready")) { startTrip() } ?: return@launch
            c.repo.markReady(t.id)
        }
        val src = c.source(t.dataMode)
        val conn = src.connect()
        if (conn.isFailure) { error("차량 데이터에 연결하지 못했어요.", conn.exceptionOrNull()?.message) { startTrip() }; return@launch }
        val odo = saneOdometer(src, t)
        if (odo.usable && t.dataMode != "MANUAL" && SampleRules.implausibleOdometer(odo.value!!, c.db.trips().highestOdometer(t.id, t.dataMode))) {
            error("차량 주행거리를 제대로 읽지 못했어요. 잠시 후 다시 눌러 주세요. 임의의 값으로 대체하지 않았어요.", "start odometer ${odo.value}") { startTrip() }
            return@launch
        }
        if (!odo.usable) {
            // never fall back silently; only offer explicit manual entry
            if (t.dataMode == "MANUAL") { _ui.update { it.copy(busy = false, manualPrompt = ManualPrompt(ManualPurpose.START_ODOMETER, "계기판의 현재 주행거리(km)를 입력해 주세요.")) } }
            else error("차량 주행거리를 읽을 수 없어요. 임의의 값으로 대체하지 않았어요.", odo.error) { startTrip() }
            return@launch
        }
        val started = c.repo.start(t.id, odo) ?: return@launch
        c.recorder.start(started.id, src)
        sync()
        _ui.update { it.copy(trip = started, busy = false, screen = Screen.DRIVING) }
        refreshSummary()
    }

    fun endTrip(): Job = viewModelScope.launch {
        val s = _ui.value
        val t = s.trip ?: return@launch
        if (!UxPolicy.isAllowed(Feature.TRIP_START_END, s.drivingState)) return@launch
        busy(true)
        val src = c.source(t.dataMode)
        val odo = saneOdometer(src, t)
        val start = t.odometerStartKm
        if (odo.usable && t.dataMode != "MANUAL" && (odo.value!! <= 0.0 || (start != null && odo.value < start))) {
            error("도착 주행거리를 제대로 읽지 못했어요. 잠시 후 다시 눌러 주세요. 임의의 값으로 대체하지 않았어요.", "end odometer ${odo.value} start $start") { endTrip() }
            return@launch
        }
        if (!odo.usable) {
            if (t.dataMode == "MANUAL") _ui.update { it.copy(busy = false, manualPrompt = ManualPrompt(ManualPurpose.END_ODOMETER, "도착 시 계기판의 주행거리(km)를 입력해 주세요.")) }
            else error("도착 주행거리를 읽을 수 없어요. 임의의 값으로 대체하지 않았어요.", odo.error) { endTrip() }
            return@launch
        }
        c.recorder.stop()
        val ended = c.repo.end(t.id, odo)
        _ui.update { it.copy(trip = ended, busy = false, screen = Screen.SUMMARY) }
        c.flush()
        autoFillEfficiency()          // never asks the driver here
    }

    /** Stores the car's fuel economy / EV efficiency if it has one. Never asks or guesses. */
    private suspend fun autoFillEfficiency() {
        val t = _ui.value.trip ?: return
        val sum = fetchSummary() ?: return
        if (sum.costRule?.efficiencyKmPerUnit != null) return
        val type = if (t.vehicleType == "EV") SignalType.EV_EFFICIENCY else SignalType.FUEL_ECONOMY
        val sample = c.source(t.dataMode).read(type)
        if (sample.usable) putEfficiency(sample.value!!.toString(), sample.unit, sample.source.name)
        else android.util.Log.w("MOATA", "${type.name} not available from the vehicle: ${sample.error}")
    }

    /** Re-reads briefly while the SDK returns an implausible odometer (see [SampleRules.implausibleOdometer]). */
    private suspend fun saneOdometer(src: io.moata.pleos.core.vehicle.VehicleDataSource, t: LocalTrip): VehicleSample {
        if (t.dataMode == "MANUAL") return src.read(SignalType.ODOMETER)
        val floor = maxOf(c.db.trips().highestOdometer(t.id, t.dataMode) ?: 0.0, t.odometerStartKm ?: 0.0).takeIf { it > 0.0 }
        var s = src.read(SignalType.ODOMETER)
        repeat(3) {
            if (!s.usable || !SampleRules.implausibleOdometer(s.value!!, floor)) return s
            android.util.Log.w("MOATA", "implausible odometer ${s.value} (floor $floor), reading again")
            delay(700); s = src.read(SignalType.ODOMETER)
        }
        return s
    }

    fun askEfficiency() {
        val t = _ui.value.trip ?: return
        if (!parked()) return
        _ui.update { it.copy(manualPrompt = ManualPrompt(ManualPurpose.ECONOMY, efficiencyReason(t.vehicleType == "EV"))) }
    }

    private fun efficiencyReason(ev: Boolean): String {
        val w = if (ev) "전비" else "연비"
        return "이 차량은 ${w}를 알려주지 않아요. 평소 ${w}를 입력해 주세요. 정산 계산에만 쓰여요."
    }

    /**
     * Uses fresh server state so a MANUAL value typed at setup is never overwritten by a car reading.
     * Asks for manual entry if the car can't supply one.
     */
    private suspend fun ensureEfficiency(): Boolean {
        val t = _ui.value.trip ?: return false
        val sum = fetchSummary() ?: return false                      // offline: do not guess
        if (sum.costRule?.efficiencyKmPerUnit != null) return true
        val type = if (t.vehicleType == "EV") SignalType.EV_EFFICIENCY else SignalType.FUEL_ECONOMY
        val sample = c.source(t.dataMode).read(type)
        if (sample.usable) return putEfficiency(sample.value!!.toString(), sample.unit, sample.source.name)
        android.util.Log.w("MOATA", "${type.name} not available from the vehicle: ${sample.error}")
        _ui.update { it.copy(manualPrompt = ManualPrompt(ManualPurpose.ECONOMY, efficiencyReason(type == SignalType.EV_EFFICIENCY))) }
        return false
    }

    fun submitManual(purpose: ManualPurpose, value: String, unit: String = ""): Job = viewModelScope.launch {
        val v = value.toDoubleOrNull()
        if (v == null || v <= 0 && purpose != ManualPurpose.START_ODOMETER) { error("0보다 큰 숫자를 입력해 주세요."); return@launch }
        _ui.update { it.copy(manualPrompt = null) }
        when (purpose) {
            ManualPurpose.START_ODOMETER, ManualPurpose.END_ODOMETER -> { c.manual.setOdometer(v); if (purpose == ManualPurpose.START_ODOMETER) startTrip() else endTrip() }
            ManualPurpose.ECONOMY -> { putEfficiency(value, unit, "MANUAL") }
        }
    }

    fun dismissManual() = _ui.update { it.copy(manualPrompt = null) }

    private suspend fun putEfficiency(value: String, unit: String, source: String): Boolean {
        val s = _ui.value
        val t = s.trip ?: return false
        if (!parked()) return false
        val old = s.summary?.costRule
        c.flush()
        val rule = CostRuleIn(
            vehicleType = t.vehicleType, efficiencyValue = value, efficiencyUnit = unit, efficiencySource = source,
            unitPriceKrw = old?.unitPriceKrw ?: s.form.unitPrice, driverIncluded = old?.driverIncluded ?: s.form.driverIncluded,
            energyPolicy = old?.energyPolicy ?: "SEGMENT_SHARED",
        )
        val ok = unwrap(c.api.call<TripSummary>("PUT", "/trips/${t.id}/cost-rule", ApiJson.encodeToString(rule))) { viewModelScope.launch { putEfficiency(value, unit, source) } } != null
        fetchSummary()
        return ok
    }

    fun addCostItem(kind: String, amount: String, allocation: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        val krw = amount.toIntOrNull()
        if (krw == null || krw <= 0) { error("금액은 1원 이상의 정수로 입력해 주세요."); return@launch }
        c.repo.addCostItem(t.id, CostItemIn(UUID.randomUUID().toString(), kind, krw, allocation))
        sync(); delay(300); refreshSummary()
    }

    fun deleteCostItem(id: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.repo.deleteCostItem(t.id, id); sync(); delay(300); refreshSummary()
    }

    fun previewSettlement(): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val d = c.flush()
        if (d.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 기록은 안전하게 저장돼 있어요.", null) { previewSettlement() }; return@launch }
        if (!ensureEfficiency()) { busy(false); return@launch }       // waiting for manual entry, or offline
        unwrap(c.api.call<SettlementOut>("POST", "/trips/${t.id}/settlements/preview")) { previewSettlement() } ?: return@launch
        busy(false); fetchSummary()
        _ui.update { it.copy(screen = Screen.SETTLEMENT) }
    }

    fun decide(settlementId: String, approve: Boolean): Job = viewModelScope.launch {
        if (!parked()) return@launch
        busy(true)
        val r = c.api.post<ApprovalIn, SettlementOut>("/settlements/$settlementId/approvals", ApprovalIn(if (approve) "APPROVE" else "DISPUTE"))
        if (!alreadyDone(r, "TRIP_LOCKED")) unwrap(r) { decide(settlementId, approve) } ?: return@launch
        busy(false)
        if (fetchSummary()?.state == "CONFIRMED") c.repo.close(_ui.value.trip!!.id, confirmed = true)   // fresh state, not a stale snapshot
    }

    /** Confirmed trips close as CONFIRMED; others just stop being offered locally. */
    fun finishTrip(): Job = viewModelScope.launch {
        val t = _ui.value.trip
        t?.let { c.repo.close(it.id, confirmed = _ui.value.summary?.state == "CONFIRMED") }
        val dropped = t?.let { c.db.outbox().dropFailedFor(it.id) } ?: 0
        if (dropped > 0) android.util.Log.w("MOATA", "dropped $dropped refused outbox events of closed trip ${t?.id}")
        _ui.update { it.copy(trip = null, summary = null, invite = null, joinUrl = null, screen = Screen.HOME,
            msg = if (dropped > 0) Msg("이 운행에서 서버가 받지 않은 기록 ${dropped}건을 정리했어요.", error = false) else it.msg) }
        loadGarden()
    }

    fun loadGarden(): Job = viewModelScope.launch {
        val gid = _ui.value.groupId ?: return@launch
        unwrap(c.api.get<CharacterOut>("/groups/$gid/character"))?.let { ch -> _ui.update { it.copy(character = ch) } }
        unwrap(c.api.get<List<HistoryItem>>("/groups/$gid/history"))?.let { h -> _ui.update { it.copy(history = h) } }
        unwrap(c.api.get<List<CouponOut>>("/groups/$gid/rewards"))?.let { r -> _ui.update { it.copy(coupons = r) } }
    }

    companion object {
        const val CONSENT_VERSION = "2026-11-v1"
        private val POLLED = setOf(Screen.INVITE, Screen.DRIVING, Screen.SUMMARY, Screen.SETTLEMENT)
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(c) as T
        }
    }
}
